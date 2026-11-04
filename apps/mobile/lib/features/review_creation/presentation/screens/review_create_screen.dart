import 'dart:io';
import 'package:flutter/material.dart';
import 'package:image_picker/image_picker.dart';
import 'package:rewit_mobile/app/theme/rewit_semantic_colors.dart';
import 'package:rewit_mobile/features/auth/presentation/state/auth_notifier.dart';
import 'package:rewit_mobile/features/feed/domain/entities/feed_entities.dart';
import 'package:rewit_mobile/features/place/domain/repositories/place_repository.dart';
import 'package:rewit_mobile/features/place/domain/services/pending_place_registration_store.dart';
import 'package:rewit_mobile/features/place_discovery/domain/repositories/place_discovery_repository.dart';
import 'package:rewit_mobile/features/product/domain/repositories/product_repository.dart';
import 'package:rewit_mobile/features/review_creation/domain/services/location_service.dart';
import 'package:rewit_mobile/features/review_creation/domain/services/media_picker_service.dart';
import 'package:rewit_mobile/features/review_detail/domain/repositories/review_media_repository.dart';
import 'package:rewit_mobile/features/search/domain/repositories/search_repository.dart';
import '../../domain/entities/review_creation_input.dart';
import '../../domain/entities/review_privacy_rules.dart';
import '../../domain/repositories/review_creation_repository.dart';
import '../state/review_create_notifier.dart';
import '../state/review_create_state.dart';
import '../widgets/review_media_picker_section.dart';
import '../widgets/target_item_input_widget.dart';

/// Tela completa de criação de publicações de avaliação multi-alvo (Step 11.0 / Step 25.3 / C4 Mídia / C5.10 Check-in).
class ReviewCreateScreen extends StatefulWidget {
  final ReviewCreationRepository? repository;
  final ReviewMediaRepository? mediaRepository;
  final MediaPickerService? mediaPickerService;
  final SearchRepository? searchRepository;
  final PlaceRepository? placeRepository;

  /// Habilita o cadastro de produto inexistente na busca de alvos.
  final ProductRepository? productRepository;

  /// Habilita a busca em fonte externa e a adoção de local na busca de alvos.
  final PlaceDiscoveryRepository? placeDiscoveryRepository;
  final LocationService? locationService;
  final ReviewCreateNotifier? notifier;
  final ValueChanged<FeedReview>? onReviewCreated;
  final String? initialTargetId;
  final String? initialTargetName;
  final String? initialTargetType;
  final String? initialCategory;
  final File? initialPhoto;
  final Future<File?> Function(ImageSource source)? onPickManualPlacePhoto;

  final AuthNotifier? authNotifier;
  final String? currentUserId;
  final PendingPlaceRegistrationStore? pendingRegistrationStore;

  const ReviewCreateScreen({
    super.key,
    this.repository,
    this.mediaRepository,
    this.mediaPickerService,
    this.searchRepository,
    this.placeRepository,
    this.productRepository,
    this.placeDiscoveryRepository,
    this.locationService,
    this.notifier,
    this.onReviewCreated,
    this.initialTargetId,
    this.initialTargetName,
    this.initialTargetType,
    this.initialCategory,
    this.initialPhoto,
    this.onPickManualPlacePhoto,
    this.authNotifier,
    this.currentUserId,
    this.pendingRegistrationStore,
  });

  @override
  State<ReviewCreateScreen> createState() => _ReviewCreateScreenState();
}

class _ReviewCreateScreenState extends State<ReviewCreateScreen> {
  late final ReviewCreateNotifier _notifier;
  bool _ownsNotifier = false;

  final TextEditingController _contextPlaceIdController =
      TextEditingController();
  final TextEditingController _experienceTextController =
      TextEditingController();

  @override
  void initState() {
    super.initState();
    if (widget.notifier != null) {
      _notifier = widget.notifier!;
    } else {
      _notifier = ReviewCreateNotifier(
        repository: widget.repository ?? _FallbackReviewCreationRepository(),
        mediaRepository: widget.mediaRepository,
        mediaPickerService: widget.mediaPickerService,
        locationService: widget.locationService,
        initialTargetId: widget.initialTargetId,
        initialTargetName: widget.initialTargetName,
        initialTargetType: widget.initialTargetType,
        initialCategory: widget.initialCategory,
        initialPhoto: widget.initialPhoto,
      );
      _ownsNotifier = true;
    }

    if (_notifier.contextPlaceId != null &&
        _notifier.contextPlaceId!.isNotEmpty) {
      _contextPlaceIdController.text = _notifier.contextPlaceId!;
    }

    _notifier.addListener(_handleStateChange);
  }

  String? _lastHandledSuccessReviewId;

  void _handleStateChange() {
    if (!mounted) return;
    if (_contextPlaceIdController.text != (_notifier.contextPlaceId ?? '')) {
      _contextPlaceIdController.text = _notifier.contextPlaceId ?? '';
    }
    final state = _notifier.state;
    if (state is ReviewCreateSuccess) {
      if (_lastHandledSuccessReviewId == state.createdReview.id) {
        return;
      }
      _lastHandledSuccessReviewId = state.createdReview.id;
      final createdReview = state.createdReview;
      final semantic = RewitSemanticColors.of(context);

      if (state.hasMediaFailures) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(
              'Avaliação criada com sucesso! Porém ${state.failedMediaCount} anexo(s) falharam no upload.',
              style: TextStyle(color: semantic.warning.onContainer),
            ),
            backgroundColor: semantic.warning.container,
            duration: const Duration(seconds: 5),
          ),
        );
      } else {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(
              state.uploadedMediaCount > 0
                  ? 'Avaliação publicada com ${state.uploadedMediaCount} foto(s) anexada(s)!'
                  : 'Avaliação publicada com sucesso!',
              style: TextStyle(color: semantic.success.onContainer),
            ),
            backgroundColor: semantic.success.container,
          ),
        );
      }

      // Limpa os controladores e reinicia o estado do formulário para evitar reutilização no IndexedStack
      _contextPlaceIdController.clear();
      _experienceTextController.clear();
      _notifier.reset();

      widget.onReviewCreated?.call(createdReview);
    }
  }

  @override
  void dispose() {
    _notifier.removeListener(_handleStateChange);
    if (_ownsNotifier) {
      _notifier.dispose();
    }
    _contextPlaceIdController.dispose();
    _experienceTextController.dispose();
    super.dispose();
  }

  Future<void> _requestLocationConsent() async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) {
        final dialogScheme = Theme.of(ctx).colorScheme;
        return AlertDialog(
          title: Row(
            children: [
              Icon(Icons.location_on_outlined, color: dialogScheme.primary),
              const SizedBox(width: 8),
              const Text('Validação de Presença'),
            ],
          ),
          content: const Text(
            'Para validar que você está no local, o Rewit precisa usar sua localização atual por alguns instantes.\n\nA localização não será acompanhada em segundo plano.',
          ),
          actions: [
            TextButton(
              key: const Key('location_consent_cancel_button'),
              onPressed: () => Navigator.of(ctx).pop(false),
              child: const Text('Cancelar'),
            ),
            FilledButton(
              key: const Key('location_consent_confirm_button'),
              onPressed: () => Navigator.of(ctx).pop(true),
              child: const Text('Continuar'),
            ),
          ],
        );
      },
    );

    if (confirmed == true && mounted) {
      await _notifier.captureLocation();
    }
  }

  Future<void> _submit() async {
    FocusScope.of(context).unfocus();
    await _notifier.submit();
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final semantic = RewitSemanticColors.of(context);

    return Scaffold(
      appBar: AppBar(
        title: const Text('Criar Avaliação'),
      ),
      body: ListenableBuilder(
        listenable: _notifier,
        builder: (context, _) {
          final state = _notifier.state;
          final isSubmitting = state.isSubmitting;

          return SingleChildScrollView(
            padding: const EdgeInsets.all(16.0),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                // Banner de Erro RFC 7807 / Validação Local
                if (state is ReviewCreateError) ...[
                  Card(
                    color: scheme.errorContainer,
                    elevation: 0,
                    margin: const EdgeInsets.only(bottom: 16),
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(12),
                      side: BorderSide(color: scheme.error.withAlpha(80)),
                    ),
                    child: Padding(
                      padding: const EdgeInsets.all(14.0),
                      child: Row(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Icon(
                            Icons.error_outline,
                            color: scheme.error,
                            size: 24,
                          ),
                          const SizedBox(width: 12),
                          Expanded(
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Row(
                                  children: [
                                    Text(
                                      state.isRateLimited
                                          ? 'Limite Excedido (429)'
                                          : 'Falha na Validação',
                                      style: TextStyle(
                                        fontWeight: FontWeight.bold,
                                        fontSize: 14,
                                        color: scheme.onErrorContainer,
                                      ),
                                    ),
                                    if (state.errorCode != null) ...[
                                      const SizedBox(width: 6),
                                      Container(
                                        padding: const EdgeInsets.symmetric(
                                          horizontal: 6,
                                          vertical: 2,
                                        ),
                                        decoration: BoxDecoration(
                                          color: scheme.error.withAlpha(30),
                                          borderRadius:
                                              BorderRadius.circular(4),
                                        ),
                                        child: Text(
                                          state.errorCode!,
                                          style: TextStyle(
                                            fontSize: 10,
                                            fontWeight: FontWeight.bold,
                                            color: scheme.error,
                                          ),
                                        ),
                                      ),
                                    ],
                                  ],
                                ),
                                const SizedBox(height: 4),
                                Text(
                                  state.message,
                                  style: TextStyle(
                                    fontSize: 13,
                                    color: scheme.onErrorContainer,
                                  ),
                                ),
                                if (state.hasFieldErrors) ...[
                                  const SizedBox(height: 6),
                                  ...state.fieldErrors.entries.map(
                                    (e) => Padding(
                                      padding: const EdgeInsets.only(top: 2.0),
                                      child: Text(
                                        '• ${e.key}: ${e.value}',
                                        style: TextStyle(
                                          fontSize: 12,
                                          fontWeight: FontWeight.w500,
                                          color: scheme.onErrorContainer,
                                        ),
                                      ),
                                    ),
                                  ),
                                ],
                                if (state.retryAfterSeconds != null) ...[
                                  const SizedBox(height: 4),
                                  Text(
                                    'Por favor, aguarde ${state.retryAfterSeconds} segundos antes de tentar novamente.',
                                    style: TextStyle(
                                      fontSize: 12,
                                      fontWeight: FontWeight.w600,
                                      color: scheme.error,
                                    ),
                                  ),
                                ],
                              ],
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),
                ],

                // Seção: Alvos Avaliados (Multi-alvo)
                Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    Text(
                      'Alvos da Avaliação *',
                      style: theme.textTheme.titleMedium?.copyWith(
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                    Text(
                      '${_notifier.targets.length} alvo(s)',
                      style: TextStyle(
                        fontSize: 12,
                        color: scheme.onSurfaceVariant,
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 4),
                Text(
                  'Avalie o estabelecimento e/ou itens e serviços específicos.',
                  style: TextStyle(
                    fontSize: 12,
                    color: scheme.onSurfaceVariant,
                  ),
                ),
                const SizedBox(height: 12),

                ...List.generate(_notifier.targets.length, (index) {
                  final target = _notifier.targets[index];
                  return TargetItemInputWidget(
                    key: ValueKey('target_item_$index'),
                    index: index,
                    target: target,
                    canRemove: _notifier.targets.length > 1,
                    enabled: !isSubmitting,
                    searchRepository: widget.searchRepository,
                    placeRepository: widget.placeRepository,
                    productRepository: widget.productRepository,
                    placeDiscoveryRepository: widget.placeDiscoveryRepository,
                    locationService: widget.locationService,
                    isTargetSelectedElsewhere: (targetId) => _notifier
                        .isTargetAlreadySelected(targetId, excludeIndex: index),
                    onTargetSelected: (item) {
                      _notifier.selectTarget(index, item);
                    },
                    onClearSelection: () {
                      _notifier.clearTargetSelection(index);
                    },
                    onTargetIdChanged: (val) {
                      _notifier.updateTarget(index, targetId: val);
                    },
                    onRatingChanged: (val) {
                      _notifier.updateTarget(index, rating: val);
                    },
                    onSpecificCommentChanged: (val) {
                      _notifier.updateTarget(index, specificComment: val);
                    },
                    onRemove: () {
                      _notifier.removeTarget(index);
                    },
                    onPickManualPlacePhoto: widget.onPickManualPlacePhoto,
                    authNotifier: widget.authNotifier,
                    currentUserId: widget.currentUserId,
                    pendingRegistrationStore: widget.pendingRegistrationStore,
                  );
                }),

                OutlinedButton.icon(
                  onPressed: isSubmitting
                      ? null
                      : () {
                          _notifier.addTarget();
                        },
                  icon: const Icon(Icons.add, size: 18),
                  label: const Text('Adicionar Outro Alvo / Item'),
                  style: OutlinedButton.styleFrom(
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(8),
                    ),
                  ),
                ),
                const SizedBox(height: 20),

                // Seção: Estabelecimento de Contexto
                Text(
                  'Local de Contexto (Opcional)',
                  style: theme.textTheme.titleSmall?.copyWith(
                    fontWeight: FontWeight.bold,
                  ),
                ),
                const SizedBox(height: 6),
                if (_notifier.contextPlaceName != null &&
                    _notifier.contextPlaceName!.isNotEmpty)
                  Container(
                    key: const Key('selected_context_place_banner'),
                    padding: const EdgeInsets.symmetric(
                      horizontal: 12,
                      vertical: 10,
                    ),
                    decoration: BoxDecoration(
                      color: scheme.secondaryContainer.withAlpha(110),
                      borderRadius: BorderRadius.circular(8),
                      border: Border.all(color: scheme.outlineVariant),
                    ),
                    child: Row(
                      children: [
                        Icon(
                          Icons.place_outlined,
                          size: 20,
                          color: scheme.primary,
                        ),
                        const SizedBox(width: 8),
                        Expanded(
                          child: Text(
                            'Estabelecimento vinculado: ${_notifier.contextPlaceName!}',
                            style: const TextStyle(
                              fontSize: 13,
                              fontWeight: FontWeight.w600,
                            ),
                          ),
                        ),
                      ],
                    ),
                  )
                else
                  TextFormField(
                    controller: _contextPlaceIdController,
                    enabled: !isSubmitting,
                    decoration: const InputDecoration(
                      labelText: 'Identificador do Local (UUID)',
                      hintText: 'ex: 00000000-0000-0000-0000-000000000002',
                      prefixIcon: Icon(Icons.place_outlined, size: 20),
                      isDense: true,
                      border: OutlineInputBorder(),
                    ),
                    onChanged: (val) {
                      _notifier.setContextPlaceId(val);
                    },
                  ),
                const SizedBox(height: 20),

                // Seção: Relato Geral da Experiência
                Text(
                  'Relato da Experiência (Opcional)',
                  style: theme.textTheme.titleSmall?.copyWith(
                    fontWeight: FontWeight.bold,
                  ),
                ),
                const SizedBox(height: 6),
                TextFormField(
                  controller: _experienceTextController,
                  enabled: !isSubmitting,
                  maxLines: 4,
                  maxLength: 2000,
                  decoration: const InputDecoration(
                    labelText: 'Conte sua experiência geral...',
                    hintText:
                        'O que achou do ambiente, tempo de espera, custo-benefício?',
                    border: OutlineInputBorder(),
                    alignLabelWithHint: true,
                  ),
                  onChanged: (val) {
                    _notifier.setExperienceText(val);
                  },
                ),
                const SizedBox(height: 12),

                // Seção: Visibilidade e Privacidade
                Text(
                  'Visibilidade e Privacidade',
                  style: theme.textTheme.titleSmall?.copyWith(
                    fontWeight: FontWeight.bold,
                  ),
                ),
                const SizedBox(height: 8),

                DropdownButtonFormField<String>(
                  key: const Key('create_visibility_dropdown'),
                  initialValue: _notifier.visibility,
                  decoration: const InputDecoration(
                    labelText: 'Quem pode ver sua avaliação',
                    prefixIcon: Icon(Icons.visibility_outlined, size: 20),
                    isDense: true,
                    border: OutlineInputBorder(),
                  ),
                  items: [
                    const DropdownMenuItem(
                      value: 'PUBLIC',
                      child: Text('Público (visível a toda a comunidade)'),
                    ),
                    DropdownMenuItem(
                      value: 'FOLLOWERS',
                      enabled: _notifier.isVisibilityOptionAvailable(
                        'FOLLOWERS',
                      ),
                      child: const Text('Seguidores (apenas quem te segue)'),
                    ),
                    const DropdownMenuItem(
                      value: 'PRIVATE',
                      child: Text('Privado (apenas você)'),
                    ),
                  ],
                  onChanged: isSubmitting
                      ? null
                      : (val) {
                          if (val != null) {
                            _notifier.setVisibility(val);
                          }
                        },
                ),
                const SizedBox(height: 12),

                if (_notifier.isAnonymous) ...[
                  const SizedBox(height: 6),
                  Text(
                    ReviewPrivacyRules.followersUnavailableForAnonymous,
                    key: const Key('create_followers_unavailable_hint'),
                    style: TextStyle(
                      fontSize: 12,
                      color: scheme.onSurfaceVariant,
                    ),
                  ),
                ],
                const SizedBox(height: 6),

                SwitchListTile(
                  key: const Key('create_anonymous_switch'),
                  contentPadding: EdgeInsets.zero,
                  value: _notifier.isAnonymous,
                  // Ligar o anonimato com "Seguidores" fica bloqueado; desligar é sempre possível
                  onChanged: isSubmitting ||
                          (!_notifier.isAnonymous &&
                              !_notifier.canEnableAnonymous)
                      ? null
                      : (val) {
                          _notifier.setAnonymous(val);
                        },
                  title: const Text('Publicar como Anônimo'),
                  subtitle: Text(
                    !_notifier.isAnonymous && !_notifier.canEnableAnonymous
                        ? ReviewPrivacyRules.anonymousUnavailableForFollowers
                        : 'Seu nome e perfil não serão exibidos publicamente na avaliação.',
                    key: const Key('create_anonymous_switch_subtitle'),
                    style: const TextStyle(fontSize: 12),
                  ),
                ),
                const SizedBox(height: 12),

                // Seção: Presença Geográfica e Check-in no Local (C5.10)
                _buildLocationSection(theme, semantic, isSubmitting),
                const SizedBox(height: 16),

                // Seção de Seleção e Upload de Fotos/Mídias
                ReviewMediaPickerSection(
                  notifier: _notifier,
                  enabled: !isSubmitting,
                ),
                const SizedBox(height: 12),

                // Botão de Submissão com proteção anti-duplo clique e bloqueio durante captura
                SizedBox(
                  height: 50,
                  child: ElevatedButton(
                    onPressed:
                        (!isSubmitting && !_notifier.isRequestingLocation)
                            ? _submit
                            : null,
                    style: ElevatedButton.styleFrom(
                      shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(12),
                      ),
                    ),
                    child: isSubmitting
                        ? SizedBox(
                            width: 22,
                            height: 22,
                            child: CircularProgressIndicator(
                              strokeWidth: 2.5,
                              color: scheme.onSurfaceVariant,
                            ),
                          )
                        : const Row(
                            mainAxisAlignment: MainAxisAlignment.center,
                            children: [
                              Icon(Icons.send_rounded, size: 20),
                              SizedBox(width: 8),
                              Text(
                                'Publicar Avaliação',
                                style: TextStyle(
                                  fontSize: 16,
                                  fontWeight: FontWeight.bold,
                                ),
                              ),
                            ],
                          ),
                  ),
                ),
                const SizedBox(height: 24),
              ],
            ),
          );
        },
      ),
    );
  }

  Widget _buildLocationSection(
    ThemeData theme,
    RewitSemanticColors semantic,
    bool isSubmitting,
  ) {
    final scheme = theme.colorScheme;
    final status = _notifier.locationStatus;
    final hasLocation = _notifier.hasLocation;

    return Card(
      elevation: 0,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(12),
        side: BorderSide(color: scheme.outlineVariant),
      ),
      child: Padding(
        padding: const EdgeInsets.all(16.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Row(
              children: [
                Icon(
                  Icons.my_location,
                  size: 20,
                  color: hasLocation ? semantic.verified.accent : scheme.primary,
                ),
                const SizedBox(width: 8),
                Text(
                  'Presença e Check-in no Local (Opcional)',
                  style: theme.textTheme.titleSmall?.copyWith(
                    fontWeight: FontWeight.bold,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 6),
            Text(
              'Valide sua presença no estabelecimento no momento da avaliação. '
              'A localização é obtida sob demanda e nunca rastreada em segundo plano.',
              style: TextStyle(
                fontSize: 12,
                color: scheme.onSurfaceVariant,
              ),
            ),
            const SizedBox(height: 12),
            if (status == LocationCaptureStatus.requesting) ...[
              Container(
                padding: const EdgeInsets.symmetric(
                  vertical: 14,
                  horizontal: 16,
                ),
                decoration: BoxDecoration(
                  color: scheme.surfaceContainerHighest,
                  borderRadius: BorderRadius.circular(8),
                ),
                child: const Row(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    SizedBox(
                      width: 18,
                      height: 18,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    ),
                    SizedBox(width: 12),
                    Text(
                      'Obtendo localização...',
                      style: TextStyle(
                        fontSize: 13,
                        fontWeight: FontWeight.w500,
                      ),
                    ),
                  ],
                ),
              ),
            ] else if (status == LocationCaptureStatus.captured &&
                hasLocation) ...[
              if (_notifier.hasContextPlace)
                Container(
                  padding: const EdgeInsets.all(12),
                  decoration: BoxDecoration(
                    color: semantic.verified.container,
                    borderRadius: BorderRadius.circular(8),
                    border: Border.all(color: semantic.verified.border),
                  ),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(
                        children: [
                          Icon(
                            Icons.check_circle,
                            color: semantic.verified.accent,
                            size: 20,
                          ),
                          const SizedBox(width: 8),
                          Text(
                            'Presença capturada',
                            style: TextStyle(
                              fontWeight: FontWeight.bold,
                              fontSize: 14,
                              color: semantic.verified.onContainer,
                            ),
                          ),
                          const Spacer(),
                          if (_notifier.locationAccuracyMeters != null)
                            Text(
                              'Precisão aproximada: ${_notifier.locationAccuracyMeters!.round()} m',
                              style: TextStyle(
                                fontSize: 12,
                                color: semantic.verified.onContainer,
                              ),
                            ),
                        ],
                      ),
                      if (_notifier.isApproximateLocation) ...[
                        const SizedBox(height: 8),
                        Container(
                          padding: const EdgeInsets.all(8),
                          decoration: BoxDecoration(
                            color: semantic.warning.container,
                            borderRadius: BorderRadius.circular(6),
                            border: Border.all(color: semantic.warning.border),
                          ),
                          child: Row(
                            children: [
                              Icon(
                                Icons.info_outline,
                                size: 16,
                                color: semantic.warning.accent,
                              ),
                              const SizedBox(width: 8),
                              Expanded(
                                child: Text(
                                  'Localização aproximada concedida. A precisão pode não ser suficiente para validar presença no local.',
                                  style: TextStyle(
                                    fontSize: 11,
                                    color: semantic.warning.onContainer,
                                  ),
                                ),
                              ),
                            ],
                          ),
                        ),
                      ],
                    ],
                  ),
                )
              else
                Container(
                  padding: const EdgeInsets.all(12),
                  decoration: BoxDecoration(
                    color: scheme.surfaceContainerHighest,
                    borderRadius: BorderRadius.circular(8),
                    border: Border.all(color: scheme.outlineVariant),
                  ),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(
                        children: [
                          Icon(
                            Icons.info_outline,
                            color: scheme.primary,
                            size: 20,
                          ),
                          const SizedBox(width: 8),
                          const Expanded(
                            child: Text(
                              'Local de contexto necessário para check-in',
                              style: TextStyle(
                                fontWeight: FontWeight.bold,
                                fontSize: 13,
                              ),
                            ),
                          ),
                        ],
                      ),
                      const SizedBox(height: 6),
                      Text(
                        'Sua localização foi obtida sob demanda, mas a validação de presença no local exige informar o estabelecimento de contexto da avaliação.',
                        style: TextStyle(
                          fontSize: 12,
                          color: scheme.onSurfaceVariant,
                        ),
                      ),
                    ],
                  ),
                ),
              const SizedBox(height: 10),
              Row(
                children: [
                  Expanded(
                    child: OutlinedButton.icon(
                      key: const Key('update_location_button'),
                      onPressed:
                          isSubmitting ? null : () => _notifier.captureLocation(),
                      icon: const Icon(Icons.refresh, size: 16),
                      label: const Text(
                        'Atualizar localização',
                        style: TextStyle(fontSize: 12),
                      ),
                    ),
                  ),
                  const SizedBox(width: 8),
                  Expanded(
                    child: OutlinedButton.icon(
                      key: const Key('remove_checkin_button'),
                      onPressed: isSubmitting
                          ? null
                          : () => _notifier.clearCoordinates(),
                      icon: const Icon(Icons.close, size: 16),
                      label: const Text(
                        'Remover check-in',
                        style: TextStyle(fontSize: 12),
                      ),
                    ),
                  ),
                ],
              ),
            ] else if (status == LocationCaptureStatus.error) ...[
              Container(
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: scheme.errorContainer,
                  borderRadius: BorderRadius.circular(8),
                  border: Border.all(color: scheme.error.withAlpha(80)),
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Icon(
                          Icons.location_off,
                          size: 20,
                          color: scheme.error,
                        ),
                        const SizedBox(width: 8),
                        Expanded(
                          child: Text(
                            _notifier.locationErrorMessage ??
                                'Não foi possível obter sua localização agora.',
                            style: TextStyle(
                              fontSize: 13,
                              color: scheme.onErrorContainer,
                              fontWeight: FontWeight.w500,
                            ),
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 10),
                    Row(
                      children: [
                        Expanded(
                          child: OutlinedButton(
                            key: const Key('retry_location_button'),
                            onPressed: isSubmitting
                                ? null
                                : () => _notifier.captureLocation(),
                            child: const Text(
                              'Tentar novamente',
                              style: TextStyle(fontSize: 12),
                            ),
                          ),
                        ),
                        if (_notifier.locationFailureReason ==
                            LocationFailureReason.permissionDeniedForever) ...[
                          const SizedBox(width: 8),
                          Expanded(
                            child: OutlinedButton(
                              key: const Key('open_settings_button'),
                              onPressed: isSubmitting
                                  ? null
                                  : () => _notifier.openAppSettings(),
                              child: const Text(
                                'Abrir configurações',
                                style: TextStyle(fontSize: 12),
                              ),
                            ),
                          ),
                        ],
                        const SizedBox(width: 8),
                        TextButton(
                          onPressed: isSubmitting
                              ? null
                              : () => _notifier.clearCoordinates(),
                          child: const Text(
                            'Ignorar',
                            style: TextStyle(fontSize: 12),
                          ),
                        ),
                      ],
                    ),
                  ],
                ),
              ),
            ] else ...[
              Row(
                children: [
                  Container(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 10,
                      vertical: 4,
                    ),
                    decoration: BoxDecoration(
                      color: scheme.surfaceContainerHighest,
                      borderRadius: BorderRadius.circular(16),
                    ),
                    child: Text(
                      'Sem check-in',
                      style: TextStyle(
                        fontSize: 12,
                        color: scheme.onSurfaceVariant,
                      ),
                    ),
                  ),
                  const Spacer(),
                  FilledButton.tonalIcon(
                    key: const Key('validate_presence_button'),
                    onPressed: isSubmitting ? null : _requestLocationConsent,
                    icon: const Icon(Icons.pin_drop_outlined, size: 18),
                    label: const Text('Validar minha presença'),
                  ),
                ],
              ),
            ],
          ],
        ),
      ),
    );
  }
}

class _FallbackReviewCreationRepository implements ReviewCreationRepository {
  @override
  Future<FeedReview> createReview(CreateReviewInput input) async {
    throw UnimplementedError(
      'Nenhum repositório de criação de avaliações fornecido.',
    );
  }
}
