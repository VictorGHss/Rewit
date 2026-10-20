import 'dart:async';
import 'dart:io';
import 'package:flutter/material.dart';
import 'package:image_picker/image_picker.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/features/auth/domain/entities/auth_state.dart';
import 'package:rewit_mobile/features/auth/presentation/state/auth_notifier.dart';
import 'package:rewit_mobile/features/place/domain/repositories/place_repository.dart';
import 'package:rewit_mobile/features/place/presentation/widgets/manual_place_registration_sheet.dart';
import 'package:rewit_mobile/features/search/domain/entities/search_entities.dart';
import 'package:rewit_mobile/features/search/domain/repositories/search_repository.dart';

/// Seletor de busca textual interativo de alvos (Search V1) com debounce,
/// cache de sessão em memória, proteção contra respostas fora de ordem
/// e distinção visual entre Local (Place) e Produto (Product).
class TargetSearchSelectorWidget extends StatefulWidget {
  final SearchRepository searchRepository;
  final PlaceRepository? placeRepository;
  final int targetIndex;
  final bool enabled;
  final ValueChanged<SearchResultItem> onTargetSelected;
  final bool Function(String targetId)? isTargetSelectedElsewhere;
  final ValueChanged<String>? onManualTargetIdEntered;
  final ValueChanged<File>? onManualPlacePhotoSelected;
  final Future<File?> Function(ImageSource source)? onPickManualPlacePhoto;
  final String? currentUserId;
  final AuthNotifier? authNotifier;

  const TargetSearchSelectorWidget({
    super.key,
    required this.searchRepository,
    this.placeRepository,
    required this.targetIndex,
    this.enabled = true,
    required this.onTargetSelected,
    this.isTargetSelectedElsewhere,
    this.onManualTargetIdEntered,
    this.onManualPlacePhotoSelected,
    this.onPickManualPlacePhoto,
    this.currentUserId,
    this.authNotifier,
  });

  @override
  State<TargetSearchSelectorWidget> createState() => _TargetSearchSelectorWidgetState();
}

class _TargetSearchSelectorWidgetState extends State<TargetSearchSelectorWidget> {
  final TextEditingController _searchController = TextEditingController();
  final TextEditingController _manualUuidController = TextEditingController();

  Timer? _debounceTimer;
  int _searchSequence = 0;
  bool _isLoading = false;
  String? _errorMessage;
  int? _retryAfterSeconds;
  List<SearchResultItem>? _results;
  bool _showManualInput = false;

  // Cache em memória durante a sessão da tela para evitar requisições idênticas
  final Map<String, List<SearchResultItem>> _queryCache = {};
  PendingPlaceRegistration? _pendingRegistration;
  String? _scopedUserId;

  @override
  void initState() {
    super.initState();
    _scopedUserId = _getCurrentUserId();
    widget.authNotifier?.addListener(_onAuthChanged);
  }

  String? _getCurrentUserId() {
    if (widget.currentUserId != null) return widget.currentUserId;
    final authState = widget.authNotifier?.state;
    if (authState is Authenticated) {
      return authState.user.id;
    }
    return null;
  }

  void _onAuthChanged() {
    final newUserId = _getCurrentUserId();
    if (newUserId != _scopedUserId) {
      setState(() {
        _scopedUserId = newUserId;
        _pendingRegistration = null;
      });
    }
  }

  @override
  void dispose() {
    _debounceTimer?.cancel();
    widget.authNotifier?.removeListener(_onAuthChanged);
    _searchController.dispose();
    _manualUuidController.dispose();
    super.dispose();
  }

  void _onSearchQueryChanged(String query) {
    _debounceTimer?.cancel();
    final trimmed = query.trim();

    if (trimmed.length < 2) {
      setState(() {
        _isLoading = false;
        _errorMessage = null;
        _retryAfterSeconds = null;
        _results = null;
      });
      return;
    }

    // Debounce de ~350ms para evitar rajadas de requisições
    _debounceTimer = Timer(const Duration(milliseconds: 350), () {
      _executeSearch(trimmed);
    });
  }

  Future<void> _executeSearch(String query) async {
    final normalized = query.toLowerCase();

    // 1. Checa cache de sessão em memória
    if (_queryCache.containsKey(normalized)) {
      if (mounted) {
        setState(() {
          _results = _queryCache[normalized];
          _isLoading = false;
          _errorMessage = null;
          _retryAfterSeconds = null;
        });
      }
      return;
    }

    final currentSeq = ++_searchSequence;

    setState(() {
      _isLoading = true;
      _errorMessage = null;
      _retryAfterSeconds = null;
    });

    try {
      final page = await widget.searchRepository.search(
        query: query,
        page: 0,
        size: 20,
      );

      // Descarta se uma busca mais recente já foi disparada (stale response)
      if (!mounted || currentSeq != _searchSequence) return;

      _queryCache[normalized] = page.items;

      setState(() {
        _isLoading = false;
        _results = page.items;
      });
    } on ApiException catch (e) {
      if (!mounted || currentSeq != _searchSequence) return;
      setState(() {
        _isLoading = false;
        _errorMessage = e.detail;
        _retryAfterSeconds = e.retryAfterSeconds;
      });
    } on NetworkException catch (e) {
      if (!mounted || currentSeq != _searchSequence) return;
      setState(() {
        _isLoading = false;
        _errorMessage = e.message;
      });
    } catch (_) {
      if (!mounted || currentSeq != _searchSequence) return;
      setState(() {
        _isLoading = false;
        _errorMessage = 'Não foi possível buscar alvos no momento.';
      });
    }
  }

  void _clearSearch() {
    _searchController.clear();
    _debounceTimer?.cancel();
    setState(() {
      _isLoading = false;
      _errorMessage = null;
      _retryAfterSeconds = null;
      _results = null;
    });
  }

  Future<void> _openManualPlaceRegistration({String? initialQuery}) async {
    if (!widget.enabled || widget.placeRepository == null) return;

    final currentUid = _getCurrentUserId();
    final nameToUse = (initialQuery ?? _searchController.text).trim();

    // MB-1b: Novo cadastro de local NUNCA herda estado pendente anterior
    final result = await ManualPlaceRegistrationSheet.show(
      context,
      placeRepository: widget.placeRepository!,
      initialName: nameToUse,
      initialPendingPlace: null,
      initialUploadIdempotencyKey: null,
      initialPhoto: null,
      currentUserId: currentUid,
      onPickPhoto: widget.onPickManualPlacePhoto,
    );

    if (!mounted || result == null) return;

    // MB-1a: Se a sessão mudou durante o await (logout ou troca de usuário), descarta o resultado
    final postWaitUid = _getCurrentUserId();
    if (postWaitUid != currentUid) return;

    _handleRegistrationResult(result, currentUid);
  }

  Future<void> _resumePendingRegistration() async {
    if (!widget.enabled || widget.placeRepository == null || _pendingRegistration == null) return;

    final currentUid = _getCurrentUserId();
    final pending = _pendingRegistration!;

    final result = await ManualPlaceRegistrationSheet.show(
      context,
      placeRepository: widget.placeRepository!,
      initialName: pending.place.name,
      initialPendingPlace: pending.place,
      initialUploadIdempotencyKey: pending.uploadIdempotencyKey,
      initialPhoto: pending.photoFile,
      currentUserId: currentUid,
      onPickPhoto: widget.onPickManualPlacePhoto,
    );

    if (!mounted || result == null) return;

    // MB-1a: Se a sessão mudou durante o await (logout ou troca de usuário), descarta o resultado
    final postWaitUid = _getCurrentUserId();
    if (postWaitUid != currentUid) return;

    _handleRegistrationResult(result, currentUid);
  }

  void _handleRegistrationResult(ManualPlaceRegistrationResult result, String? currentUid) {
    if (result.isPhotoPending) {
      setState(() {
        _pendingRegistration = PendingPlaceRegistration(
          place: result.place,
          uploadIdempotencyKey: result.uploadIdempotencyKey ?? generateUuidV4(),
          photoFile: result.photoFile,
          userId: currentUid,
        );
      });
    } else {
      setState(() {
        _pendingRegistration = null;
      });
    }

    final createdItem = SearchResultItem(
      id: result.place.id,
      name: result.place.name,
      slug: result.place.slug,
      category: result.place.category,
      targetType: TargetType.place,
      rawTargetType: 'PLACE',
      status: result.place.status,
    );

    final normalizedQuery = _searchController.text.trim().toLowerCase();
    if (normalizedQuery.isNotEmpty) {
      final existing = _queryCache[normalizedQuery] ?? const [];
      _queryCache[normalizedQuery] = [
        createdItem,
        ...existing.where((e) => e.id != createdItem.id),
      ];
    }

    if (result.isPhotoPending && mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            'O local "${result.place.name}" foi registrado, mas o envio da fotografia ficou pendente.',
          ),
          backgroundColor: Colors.orange.shade800,
          action: SnackBarAction(
            label: 'Retomar',
            textColor: Colors.white,
            onPressed: () => _resumePendingRegistration(),
          ),
        ),
      );
    }

    widget.onTargetSelected(createdItem);
    if (result.photoFile != null) {
      widget.onManualPlacePhotoSelected?.call(result.photoFile!);
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    if (_showManualInput) {
      return Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          TextFormField(
            controller: _manualUuidController,
            enabled: widget.enabled,
            decoration: const InputDecoration(
              labelText: 'Identificador do Alvo (UUID) *',
              hintText: 'ex: 00000000-0000-0000-0000-000000000001',
              prefixIcon: Icon(Icons.qr_code_2_outlined, size: 20),
              isDense: true,
              border: OutlineInputBorder(),
            ),
            onChanged: (val) {
              widget.onManualTargetIdEntered?.call(val);
            },
          ),
          const SizedBox(height: 4),
          Align(
            alignment: Alignment.centerRight,
            child: TextButton.icon(
              onPressed: () {
                setState(() {
                  _showManualInput = false;
                });
              },
              icon: const Icon(Icons.search, size: 16),
              label: const Text('Voltar para busca por nome'),
              style: TextButton.styleFrom(
                visualDensity: VisualDensity.compact,
                padding: EdgeInsets.zero,
              ),
            ),
          ),
        ],
      );
    }

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        // Banner de fotografia pendente para retomada explícita (MB-1b)
        if (_pendingRegistration != null) ...[
          Container(
            key: const Key('target_pending_registration_banner'),
            margin: const EdgeInsets.only(bottom: 8),
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
            decoration: BoxDecoration(
              color: Colors.orange.shade50,
              borderRadius: BorderRadius.circular(8),
              border: Border.all(color: Colors.orange.shade300),
            ),
            child: Row(
              children: [
                Icon(Icons.warning_amber_rounded, size: 20, color: Colors.orange.shade800),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    'Envio de fotografia pendente para "${_pendingRegistration!.place.name}".',
                    style: TextStyle(
                      fontSize: 12,
                      color: Colors.orange.shade900,
                      fontWeight: FontWeight.w500,
                    ),
                  ),
                ),
                TextButton(
                  key: const Key('target_resume_pending_registration_button'),
                  onPressed: widget.enabled ? _resumePendingRegistration : null,
                  child: const Text('Retomar'),
                ),
              ],
            ),
          ),
        ],

        // Campo de entrada da busca
        TextFormField(
          controller: _searchController,
          enabled: widget.enabled,
          decoration: InputDecoration(
            labelText: 'O que você quer avaliar? *',
            hintText: 'Digite o nome do estabelecimento ou produto...',
            prefixIcon: const Icon(Icons.search_rounded, size: 22),
            suffixIcon: _isLoading
                ? const Padding(
                    padding: EdgeInsets.all(12.0),
                    child: SizedBox(
                      width: 16,
                      height: 16,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    ),
                  )
                : _searchController.text.isNotEmpty
                    ? IconButton(
                        icon: const Icon(Icons.clear, size: 18),
                        tooltip: 'Limpar busca',
                        onPressed: widget.enabled ? _clearSearch : null,
                      )
                    : null,
            isDense: true,
            border: const OutlineInputBorder(),
          ),
          onChanged: _onSearchQueryChanged,
        ),
        const SizedBox(height: 4),

        // Atalho opcional para inserção direta de UUID
        Align(
          alignment: Alignment.centerRight,
          child: TextButton.icon(
            onPressed: widget.enabled
                ? () {
                    setState(() {
                      _showManualInput = true;
                    });
                  }
                : null,
            icon: const Icon(Icons.pin_outlined, size: 15),
            label: const Text(
              'Inserir UUID manualmente',
              style: TextStyle(fontSize: 11),
            ),
            style: TextButton.styleFrom(
              visualDensity: VisualDensity.compact,
              padding: EdgeInsets.zero,
            ),
          ),
        ),

        // Exibição de estado de carregamento
        if (_isLoading && (_results == null || _results!.isEmpty)) ...[
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 12.0),
            child: Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                const SizedBox(
                  width: 16,
                  height: 16,
                  child: CircularProgressIndicator(strokeWidth: 2),
                ),
                const SizedBox(width: 10),
                Text(
                  'Buscando alvos...',
                  style: TextStyle(
                    fontSize: 13,
                    color: theme.colorScheme.onSurface.withAlpha(160),
                  ),
                ),
              ],
            ),
          ),
        ],

        // Exibição de erro RFC 7807 / Rede
        if (_errorMessage != null) ...[
          Container(
            margin: const EdgeInsets.only(top: 8),
            padding: const EdgeInsets.all(12),
            decoration: BoxDecoration(
              color: theme.colorScheme.errorContainer.withAlpha(120),
              borderRadius: BorderRadius.circular(8),
              border: Border.all(color: theme.colorScheme.error.withAlpha(80)),
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Icon(Icons.error_outline, size: 18, color: theme.colorScheme.error),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Text(
                        _errorMessage!,
                        style: TextStyle(
                          fontSize: 12,
                          color: theme.colorScheme.onErrorContainer,
                          fontWeight: FontWeight.w500,
                        ),
                      ),
                    ),
                  ],
                ),
                if (_retryAfterSeconds != null) ...[
                  const SizedBox(height: 4),
                  Text(
                    'Aguarde $_retryAfterSeconds segundos antes de tentar novamente.',
                    style: TextStyle(
                      fontSize: 11,
                      color: theme.colorScheme.error,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                ],
                const SizedBox(height: 6),
                Align(
                  alignment: Alignment.centerRight,
                  child: OutlinedButton.icon(
                    onPressed: () {
                      if (_searchController.text.trim().isNotEmpty) {
                        _executeSearch(_searchController.text.trim());
                      }
                    },
                    icon: const Icon(Icons.refresh, size: 14),
                    label: const Text('Tentar novamente', style: TextStyle(fontSize: 11)),
                    style: OutlinedButton.styleFrom(
                      visualDensity: VisualDensity.compact,
                      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
                    ),
                  ),
                ),
              ],
            ),
          ),
        ],

        // Mensagem inicial de instrução
        if (!_isLoading &&
            _errorMessage == null &&
            _results == null &&
            _searchController.text.trim().length < 2) ...[
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 8.0, horizontal: 4.0),
            child: Row(
              children: [
                Icon(
                  Icons.info_outline,
                  size: 16,
                  color: theme.colorScheme.onSurface.withAlpha(130),
                ),
                const SizedBox(width: 6),
                Expanded(
                  child: Text(
                    'Digite ao menos 2 caracteres para buscar locais e produtos.',
                    style: TextStyle(
                      fontSize: 11,
                      color: theme.colorScheme.onSurface.withAlpha(140),
                    ),
                  ),
                ),
              ],
            ),
          ),
        ],

        // Nenhum resultado encontrado
        if (!_isLoading &&
            _errorMessage == null &&
            _results != null &&
            _results!.isEmpty) ...[
          Container(
            margin: const EdgeInsets.only(top: 8),
            padding: const EdgeInsets.all(14),
            decoration: BoxDecoration(
              color: theme.colorScheme.surfaceContainerHighest.withAlpha(80),
              borderRadius: BorderRadius.circular(8),
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Row(
                  children: [
                    Icon(
                      Icons.search_off_rounded,
                      size: 20,
                      color: theme.colorScheme.onSurface.withAlpha(150),
                    ),
                    const SizedBox(width: 10),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            'Nenhum resultado para "${_searchController.text.trim()}".',
                            style: const TextStyle(
                              fontSize: 12,
                              fontWeight: FontWeight.bold,
                            ),
                          ),
                          const SizedBox(height: 2),
                          Text(
                            'Verifique a digitação ou tente outro nome.',
                            style: TextStyle(
                              fontSize: 11,
                              color: theme.colorScheme.onSurface.withAlpha(150),
                            ),
                          ),
                        ],
                      ),
                    ),
                  ],
                ),
                if (widget.placeRepository != null) ...[
                  const SizedBox(height: 12),
                  FilledButton.tonalIcon(
                    key: const Key('register_manual_place_button'),
                    onPressed: widget.enabled ? _openManualPlaceRegistration : null,
                    icon: const Icon(Icons.add_location_alt_outlined, size: 18),
                    label: const Text('Cadastrar local manualmente'),
                  ),
                ],
              ],
            ),
          ),
        ],

        // Lista de resultados encontrados
        if (_results != null && _results!.isNotEmpty) ...[
          Container(
            margin: const EdgeInsets.only(top: 8),
            constraints: const BoxConstraints(maxHeight: 280),
            child: Material(
              color: theme.colorScheme.surface,
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(8),
                side: BorderSide(color: theme.colorScheme.outlineVariant.withAlpha(100)),
              ),
              child: ListView.separated(
                shrinkWrap: true,
                padding: const EdgeInsets.symmetric(vertical: 4),
                itemCount: _results!.length,
                separatorBuilder: (context, _) => Divider(
                  height: 1,
                  color: theme.colorScheme.outlineVariant.withAlpha(60),
                ),
                itemBuilder: (context, index) {
                  final item = _results![index];
                  final isAlreadySelected =
                      widget.isTargetSelectedElsewhere?.call(item.id) ?? false;

                  return _buildResultTile(
                    context: context,
                    theme: theme,
                    item: item,
                    isAlreadySelected: isAlreadySelected,
                  );
                },
              ),
            ),
          ),
          if (widget.placeRepository != null) ...[
            const SizedBox(height: 6),
            Align(
              alignment: Alignment.centerRight,
              child: TextButton.icon(
                key: const Key('register_manual_place_footer_button'),
                onPressed: widget.enabled ? _openManualPlaceRegistration : null,
                icon: const Icon(Icons.add_location_alt_outlined, size: 16),
                label: const Text(
                  'Não encontrou o local? Cadastrar manualmente',
                  style: TextStyle(fontSize: 12),
                ),
              ),
            ),
          ],
        ],
      ],
    );
  }

  Widget _buildResultTile({
    required BuildContext context,
    required ThemeData theme,
    required SearchResultItem item,
    required bool isAlreadySelected,
  }) {
    final isPlace = item.isPlace;
    final isProduct = item.isProduct;

    // Ícone e cores distintivas para Place vs Product
    final IconData typeIcon = isPlace
        ? Icons.storefront_rounded
        : isProduct
            ? Icons.inventory_2_outlined
            : Icons.category_outlined;

    final Color badgeColor = isPlace
        ? theme.colorScheme.primary
        : isProduct
            ? Colors.teal
            : theme.colorScheme.secondary;

    return ListTile(
      dense: true,
      enabled: widget.enabled,
      leading: Container(
        width: 36,
        height: 36,
        decoration: BoxDecoration(
          color: badgeColor.withAlpha(25),
          shape: BoxShape.circle,
        ),
        child: Icon(typeIcon, size: 20, color: badgeColor),
      ),
      title: Text(
        item.name,
        style: TextStyle(
          fontSize: 13,
          fontWeight: FontWeight.bold,
          color: isAlreadySelected
              ? theme.colorScheme.onSurface.withAlpha(100)
              : theme.colorScheme.onSurface,
        ),
      ),
      subtitle: Row(
        children: [
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 1.5),
            decoration: BoxDecoration(
              color: badgeColor.withAlpha(25),
              borderRadius: BorderRadius.circular(4),
            ),
            child: Text(
              item.targetType.displayName,
              style: TextStyle(
                fontSize: 10,
                fontWeight: FontWeight.bold,
                color: badgeColor,
              ),
            ),
          ),
          if (item.category != null && item.category!.isNotEmpty) ...[
            const SizedBox(width: 6),
            Flexible(
              child: Text(
                '• ${item.category!}',
                style: TextStyle(
                  fontSize: 11,
                  color: theme.colorScheme.onSurface.withAlpha(140),
                ),
                overflow: TextOverflow.ellipsis,
              ),
            ),
          ],
        ],
      ),
      trailing: isAlreadySelected
          ? Container(
              padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
              decoration: BoxDecoration(
                color: theme.colorScheme.surfaceContainerHighest,
                borderRadius: BorderRadius.circular(4),
              ),
              child: Text(
                'Já adicionado',
                style: TextStyle(
                  fontSize: 10,
                  fontWeight: FontWeight.w600,
                  color: theme.colorScheme.onSurface.withAlpha(140),
                ),
              ),
            )
          : TextButton(
              onPressed: widget.enabled ? () => widget.onTargetSelected(item) : null,
              style: TextButton.styleFrom(
                visualDensity: VisualDensity.compact,
                padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 2),
              ),
              child: const Text('Selecionar', style: TextStyle(fontSize: 12)),
            ),
      onTap: isAlreadySelected
          ? () {
              ScaffoldMessenger.of(context).showSnackBar(
                SnackBar(
                  content: Text('O alvo "${item.name}" já foi adicionado a esta avaliação.'),
                  duration: const Duration(seconds: 2),
                ),
              );
            }
          : () {
              widget.onTargetSelected(item);
            },
    );
  }
}
