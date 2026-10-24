import 'dart:async';
import 'dart:io';
import 'package:flutter/material.dart';
import 'package:image_picker/image_picker.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/features/auth/domain/entities/auth_state.dart';
import 'package:rewit_mobile/features/auth/presentation/state/auth_notifier.dart';
import 'package:rewit_mobile/features/place/domain/repositories/place_repository.dart';
import 'package:rewit_mobile/features/place/domain/services/pending_place_registration_store.dart';
import 'package:rewit_mobile/features/place/presentation/widgets/manual_place_registration_sheet.dart';
import 'package:rewit_mobile/features/place/presentation/widgets/pending_registration_notices.dart';
import 'package:rewit_mobile/features/product/domain/repositories/product_repository.dart';
import 'package:rewit_mobile/features/product/presentation/widgets/manual_product_registration_sheet.dart';
import 'package:rewit_mobile/features/search/domain/entities/search_entities.dart';
import 'package:rewit_mobile/features/search/domain/repositories/search_repository.dart';
import 'package:rewit_mobile/features/search/presentation/widgets/target_type_style.dart';

/// Tipo de alvo que o usuário quer avaliar; filtra os resultados da busca unificada no próprio app.
enum TargetKindFilter { all, place, product }

/// Seletor de busca textual interativo de alvos (Search V1) com debounce,
/// cache de sessão em memória, proteção contra respostas fora de ordem
/// e distinção visual entre Local (Place) e Produto (Product).
class TargetSearchSelectorWidget extends StatefulWidget {
  final SearchRepository searchRepository;
  final PlaceRepository? placeRepository;

  /// Com ele, o seletor oferece o cadastro de um produto inexistente, que já volta selecionado.
  final ProductRepository? productRepository;
  final int targetIndex;
  final bool enabled;
  final ValueChanged<SearchResultItem> onTargetSelected;
  final bool Function(String targetId)? isTargetSelectedElsewhere;
  final ValueChanged<String>? onManualTargetIdEntered;
  final ValueChanged<File>? onManualPlacePhotoSelected;
  final Future<File?> Function(ImageSource source)? onPickManualPlacePhoto;
  final String? currentUserId;
  final AuthNotifier? authNotifier;

  /// Store das operações pendentes; o app real injeta o persistente (ver `main.dart`).
  final PendingPlaceRegistrationStore? pendingRegistrationStore;

  const TargetSearchSelectorWidget({
    super.key,
    required this.searchRepository,
    this.placeRepository,
    this.productRepository,
    required this.targetIndex,
    this.enabled = true,
    required this.onTargetSelected,
    this.isTargetSelectedElsewhere,
    this.onManualTargetIdEntered,
    this.onManualPlacePhotoSelected,
    this.onPickManualPlacePhoto,
    this.currentUserId,
    this.authNotifier,
    this.pendingRegistrationStore,
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
  TargetKindFilter _kindFilter = TargetKindFilter.all;
  bool _isOpeningProductRegistration = false;

  /// Resultados do tipo escolhido; a busca é a mesma, só a exibição muda.
  List<SearchResultItem>? get _visibleResults {
    final results = _results;
    if (results == null) return null;
    switch (_kindFilter) {
      case TargetKindFilter.all:
        return results;
      case TargetKindFilter.place:
        return results.where((item) => item.isPlace).toList();
      case TargetKindFilter.product:
        return results.where((item) => item.isProduct).toList();
    }
  }

  bool get _canRegisterPlace => widget.placeRepository != null && _kindFilter != TargetKindFilter.product;
  bool get _canRegisterProduct => widget.productRepository != null && _kindFilter != TargetKindFilter.place;

  // Cache em memória durante a sessão da tela para evitar requisições idênticas
  final Map<String, List<SearchResultItem>> _queryCache = {};
  PendingPlaceRegistration? _pendingRegistration;
  String? _scopedUserId;
  int _pendingLoadGeneration = 0;

  PendingPlaceRegistrationStore get _store =>
      widget.pendingRegistrationStore ?? PendingPlaceRegistrationStore.processLocal;

  @override
  void initState() {
    super.initState();
    _scopedUserId = _getCurrentUserId();
    widget.authNotifier?.addListener(_onAuthChanged);
    _reloadPending();
  }

  @override
  void didUpdateWidget(covariant TargetSearchSelectorWidget oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.authNotifier != widget.authNotifier) {
      oldWidget.authNotifier?.removeListener(_onAuthChanged);
      widget.authNotifier?.addListener(_onAuthChanged);
    }
    if (oldWidget.currentUserId != widget.currentUserId ||
        oldWidget.authNotifier != widget.authNotifier ||
        oldWidget.pendingRegistrationStore != widget.pendingRegistrationStore) {
      _onIdentityMaybeChanged(force: oldWidget.pendingRegistrationStore != widget.pendingRegistrationStore);
    }
  }

  String? _getCurrentUserId() {
    if (widget.currentUserId != null) return widget.currentUserId;
    final authState = widget.authNotifier?.state;
    if (authState is Authenticated) {
      return authState.user.id;
    }
    return null;
  }

  void _onAuthChanged() => _onIdentityMaybeChanged();

  /// Troca de identidade descarta imediatamente o estado exibido e recarrega o da nova conta.
  void _onIdentityMaybeChanged({bool force = false}) {
    final newUserId = _getCurrentUserId();
    if (!force && newUserId == _scopedUserId) return;
    setState(() {
      _scopedUserId = newUserId;
      _pendingRegistration = null;
    });
    _reloadPending();
  }

  /// Carrega a operação pendente da conta atual. Resultados de leituras anteriores (outra conta ou
  /// leitura superada) são descartados: nada da sessão anterior é restaurado na atual (MB-1d).
  Future<void> _reloadPending() async {
    final generation = ++_pendingLoadGeneration;
    final userId = _scopedUserId;
    final loaded = userId == null ? null : await _store.loadLatest(userId);
    if (!mounted || generation != _pendingLoadGeneration || userId != _getCurrentUserId()) return;
    setState(() {
      _pendingRegistration = loaded;
    });
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
      pendingRegistrationStore: _store,
      authNotifier: widget.authNotifier,
      onPickPhoto: widget.onPickManualPlacePhoto,
    );

    if (!mounted || result == null) return;

    // MB-1a: Se a sessão mudou durante o await (logout ou troca de usuário), descarta o resultado
    final postWaitUid = _getCurrentUserId();
    if (postWaitUid != currentUid) return;

    await _handleRegistrationResult(result, currentUid);
  }

  /// Cadastra um produto inexistente e o seleciona como alvo; nota, comentários, demais alvos e mídia ficam no
  /// formulário da avaliação, que este seletor não toca.
  Future<void> _openManualProductRegistration() async {
    if (!widget.enabled || widget.productRepository == null || _isOpeningProductRegistration) return;
    _isOpeningProductRegistration = true;
    try {
      final currentUid = _getCurrentUserId();
      final created = await ManualProductRegistrationSheet.show(
        context,
        productRepository: widget.productRepository!,
        initialName: _searchController.text.trim(),
      );
      if (!mounted || created == null) return;
      // Sessão trocada durante o cadastro: o produto existe no catálogo, mas não vira alvo da outra conta
      if (_getCurrentUserId() != currentUid) return;

      final createdItem = SearchResultItem(
        id: created.id,
        name: created.name,
        category: created.category.isEmpty ? null : created.category,
        targetType: TargetType.product,
        rawTargetType: 'PRODUCT',
        status: created.status,
      );
      final normalizedQuery = _searchController.text.trim().toLowerCase();
      if (normalizedQuery.isNotEmpty) {
        final existing = _queryCache[normalizedQuery] ?? const [];
        _queryCache[normalizedQuery] = [createdItem, ...existing.where((e) => e.id != createdItem.id)];
      }
      widget.onTargetSelected(createdItem);
    } finally {
      _isOpeningProductRegistration = false;
    }
  }

  Future<void> _resumePendingRegistration() async {
    if (!widget.enabled || widget.placeRepository == null || _pendingRegistration == null) return;

    final currentUid = _getCurrentUserId();
    final pending = _pendingRegistration!;

    File? photoToUse = pending.photoFile;
    if (pending.photoUnavailable || (photoToUse != null && !photoToUse.existsSync())) {
      photoToUse = null;
      if (mounted) {
        PendingRegistrationNotices.show(
          context,
          currentUid,
          SnackBar(
            content: Text(
              'A fotografia anterior do local "${pending.place.name}" não está mais disponível no dispositivo. Selecione uma nova foto.',
            ),
            backgroundColor: Colors.orange.shade900,
          ),
        );
      }
    }

    final result = await ManualPlaceRegistrationSheet.show(
      context,
      placeRepository: widget.placeRepository!,
      initialName: pending.place.name,
      initialPendingPlace: pending.place,
      initialUploadIdempotencyKey: pending.uploadIdempotencyKey,
      initialPhoto: photoToUse,
      currentUserId: currentUid,
      pendingRegistrationStore: _store,
      authNotifier: widget.authNotifier,
      onPickPhoto: widget.onPickManualPlacePhoto,
    );

    if (!mounted || result == null) return;

    // MB-1a: Se a sessão mudou durante o await (logout ou troca de usuário), descarta o resultado
    final postWaitUid = _getCurrentUserId();
    if (postWaitUid != currentUid) return;

    await _handleRegistrationResult(result, currentUid);
  }

  Future<void> _handleRegistrationResult(ManualPlaceRegistrationResult result, String? currentUid) async {
    if (result.isPhotoPending) {
      final pending = PendingPlaceRegistration(
        place: result.place,
        uploadIdempotencyKey: result.uploadIdempotencyKey ?? generateUuidV4(),
        photoFile: result.photoFile,
        userId: currentUid,
      );
      // A operação já foi gravada pelo sheet sob a conta de origem, no instante da falha (P2-3)
      if (!mounted || _getCurrentUserId() != currentUid) return;
      _pendingLoadGeneration++;
      setState(() {
        _pendingRegistration = pending;
      });
    } else {
      // Concluída: o sheet já removeu somente a operação deste local; outras pendências continuam
      if (!mounted || _getCurrentUserId() != currentUid) return;
      setState(() {
        _pendingRegistration = null;
      });
      await _reloadPending();
      if (!mounted || _getCurrentUserId() != currentUid) return;
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

    // Sem ação no aviso: o local criado vira o alvo e este seletor é desmontado em seguida, então a
    // retomada fica no aviso de pendência do seletor (botão "Trocar" do alvo ou nova avaliação).
    if (result.isPhotoPending && mounted) {
      PendingRegistrationNotices.show(
        context,
        currentUid,
        SnackBar(
          content: Text(
            'O local "${result.place.name}" foi registrado, mas o envio da fotografia ficou pendente. '
            'Retome o envio pelo aviso de pendência na busca de alvos.',
          ),
          backgroundColor: Colors.orange.shade800,
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
                    _pendingRegistration!.photoUnavailable
                        ? 'A fotografia de "${_pendingRegistration!.place.name}" não está mais disponível neste dispositivo. '
                            'Retome o cadastro selecionando uma nova foto para o mesmo local.'
                        : 'Envio de fotografia pendente para "${_pendingRegistration!.place.name}".',
                    key: _pendingRegistration!.photoUnavailable
                        ? const Key('target_pending_photo_unavailable_text')
                        : const Key('target_pending_photo_pending_text'),
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
        const SizedBox(height: 8),

        // Tipo do alvo: a mesma busca, filtrada para locais ou produtos
        SegmentedButton<TargetKindFilter>(
          key: const Key('target_kind_filter'),
          showSelectedIcon: false,
          segments: const [
            ButtonSegment(value: TargetKindFilter.all, label: Text('Todos')),
            ButtonSegment(
              value: TargetKindFilter.place,
              icon: Icon(Icons.storefront_rounded, size: 16),
              label: Text('Locais'),
            ),
            ButtonSegment(
              value: TargetKindFilter.product,
              icon: Icon(Icons.inventory_2_outlined, size: 16),
              label: Text('Produtos'),
            ),
          ],
          selected: {_kindFilter},
          onSelectionChanged: widget.enabled
              ? (selection) => setState(() => _kindFilter = selection.first)
              : null,
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

        // Nenhum resultado encontrado (para o tipo escolhido)
        if (!_isLoading &&
            _errorMessage == null &&
            _visibleResults != null &&
            _visibleResults!.isEmpty) ...[
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
                            switch (_kindFilter) {
                              TargetKindFilter.all => 'Nenhum resultado para "${_searchController.text.trim()}".',
                              TargetKindFilter.place => 'Nenhum local para "${_searchController.text.trim()}".',
                              TargetKindFilter.product => 'Nenhum produto para "${_searchController.text.trim()}".',
                            },
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
                if (_canRegisterPlace) ...[
                  const SizedBox(height: 12),
                  FilledButton.tonalIcon(
                    key: const Key('register_manual_place_button'),
                    onPressed: widget.enabled ? _openManualPlaceRegistration : null,
                    icon: const Icon(Icons.add_location_alt_outlined, size: 18),
                    label: const Text('Cadastrar local manualmente'),
                  ),
                ],
                if (_canRegisterProduct) ...[
                  const SizedBox(height: 8),
                  FilledButton.tonalIcon(
                    key: const Key('register_manual_product_button'),
                    onPressed: widget.enabled ? _openManualProductRegistration : null,
                    icon: const Icon(Icons.add_box_outlined, size: 18),
                    label: const Text('Cadastrar produto'),
                  ),
                ],
              ],
            ),
          ),
        ],

        // Lista de resultados encontrados
        if (_visibleResults != null && _visibleResults!.isNotEmpty) ...[
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
                itemCount: _visibleResults!.length,
                separatorBuilder: (context, _) => Divider(
                  height: 1,
                  color: theme.colorScheme.outlineVariant.withAlpha(60),
                ),
                itemBuilder: (context, index) {
                  final item = _visibleResults![index];
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
          if (_canRegisterPlace) ...[
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
          if (_canRegisterProduct)
            Align(
              alignment: Alignment.centerRight,
              child: TextButton.icon(
                key: const Key('register_manual_product_footer_button'),
                onPressed: widget.enabled ? _openManualProductRegistration : null,
                icon: const Icon(Icons.add_box_outlined, size: 16),
                label: const Text(
                  'Não encontrou o produto? Cadastrar',
                  style: TextStyle(fontSize: 12),
                ),
              ),
            ),
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
    // Ícone e cores distintivas por tipo de alvo
    final style = TargetTypeStyle.of(item.targetType, theme.colorScheme);
    final IconData typeIcon = style.icon;
    final Color badgeColor = style.color;

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
