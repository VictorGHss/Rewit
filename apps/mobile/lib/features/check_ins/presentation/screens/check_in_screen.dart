import 'dart:async';
import 'package:flutter/material.dart';
import 'package:rewit_mobile/app/theme/rewit_semantic_colors.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/features/feed/domain/entities/feed_entities.dart';
import 'package:rewit_mobile/features/review_creation/domain/entities/device_location.dart';
import 'package:rewit_mobile/features/review_creation/domain/repositories/review_creation_repository.dart';
import 'package:rewit_mobile/features/review_creation/domain/services/location_service.dart';
import 'package:rewit_mobile/features/review_creation/presentation/state/review_create_notifier.dart';
import 'package:rewit_mobile/features/review_creation/presentation/state/review_create_state.dart';
import 'package:rewit_mobile/features/review_creation/presentation/widgets/rating_bar_widget.dart';
import 'package:rewit_mobile/features/search/domain/entities/search_entities.dart';
import 'package:rewit_mobile/shared/widgets/error_banner.dart';

import '../../domain/entities/check_in_entities.dart';
import '../../domain/repositories/check_in_repository.dart';

/// Check-in rápido: uma avaliação mínima (só as estrelas) de um local próximo, publicada com a localização do
/// momento. Não existe check-in avulso: o backend decide, ao receber a avaliação, se a presença foi verificada, e
/// esta tela só mostra o que ele respondeu. O fluxo completo continua em Nova Avaliação (botão +).
class CheckInScreen extends StatefulWidget {
  final ReviewCreationRepository? reviewCreationRepository;
  final LocationService? locationService;
  final CheckInRepository? checkInRepository;

  /// Abre o histórico de check-ins verificados.
  final VoidCallback? onOpenHistory;

  /// Chamado com a avaliação publicada (ex.: recarregar o feed).
  final ValueChanged<FeedReview>? onReviewCreated;

  /// Quando `true`, renderiza apenas o conteúdo interno sem `Scaffold` nem `AppBar` próprios.
  final bool embedded;

  const CheckInScreen({
    super.key,
    required this.reviewCreationRepository,
    required this.locationService,
    required this.checkInRepository,
    this.onOpenHistory,
    this.onReviewCreated,
    this.embedded = false,
  });

  @override
  State<CheckInScreen> createState() => _CheckInScreenState();
}

class _CheckInScreenState extends State<CheckInScreen>
    with WidgetsBindingObserver {
  ReviewCreateNotifier? _notifier;
  final TextEditingController _commentController = TextEditingController();

  List<NearbyPlace>? _nearby;
  bool _loadingNearby = false;
  String? _nearbyError;
  int _nearbyRequest = 0;
  NearbyPlace? _selected;
  bool _ratingChosen = false;
  String? _reportedReviewId;
  bool _awaitingSettingsReturn = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    final repository = widget.reviewCreationRepository;
    if (repository != null && widget.checkInRepository != null) {
      _notifier = ReviewCreateNotifier(repository: repository, locationService: widget.locationService)
        ..addListener(_onNotifierChanged);
    }
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _awaitingSettingsReturn = false;
    _nearbyRequest++;
    _notifier?.removeListener(_onNotifierChanged);
    _notifier?.dispose();
    _commentController.dispose();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state != AppLifecycleState.resumed) return;
    unawaited(_handleResumedFromSettings());
  }

  Future<void> _handleResumedFromSettings() async {
    if (!mounted || !_awaitingSettingsReturn) return;
    _awaitingSettingsReturn = false;

    final notifier = _notifier;
    if (notifier == null ||
        notifier.isDisposed ||
        notifier.isSubmitting ||
        notifier.state is ReviewCreateSuccess ||
        notifier.isRequestingLocation ||
        _loadingNearby ||
        _selected != null) {
      return;
    }

    final shouldCapture = await notifier.prepareLocationResumeFromSettings();
    if (!mounted ||
        notifier.isDisposed ||
        notifier.isSubmitting ||
        notifier.state is ReviewCreateSuccess ||
        notifier.isRequestingLocation ||
        _loadingNearby ||
        _selected != null) {
      return;
    }

    if (shouldCapture) {
      await _locate();
    } else if (notifier.locationStatus == LocationCaptureStatus.error &&
        (_nearby != null || _nearbyError != null || _selected != null)) {
      setState(() {
        _nearbyRequest++;
        _nearby = null;
        _nearbyError = null;
        _selected = null;
      });
    }
  }

  Future<void> _openSettings({required bool deviceLocationSettings}) async {
    final notifier = _notifier;
    if (notifier == null ||
        notifier.isSubmitting ||
        notifier.isRequestingLocation) {
      return;
    }
    _awaitingSettingsReturn = true;
    final opened = deviceLocationSettings
        ? await notifier.openLocationSettings()
        : await notifier.openAppSettings();
    if (!mounted) return;
    if (!opened) {
      _awaitingSettingsReturn = false;
    }
  }

  void _onNotifierChanged() {
    if (!mounted) return;
    final state = _notifier!.state;
    if (state is ReviewCreateSuccess && state.createdReview.id != _reportedReviewId) {
      _reportedReviewId = state.createdReview.id;
      widget.onReviewCreated?.call(state.createdReview);
    }
    setState(() {});
  }

  Future<void> _locate() async {
    final notifier = _notifier!;
    _awaitingSettingsReturn = false;
    setState(() {
      _nearby = null;
      _nearbyError = null;
      _selected = null;
    });
    if (await notifier.captureLocation()) {
      await _loadNearby();
    }
  }

  Future<void> _loadNearby() async {
    final notifier = _notifier!;
    final latitude = notifier.userLatitude;
    final longitude = notifier.userLongitude;
    if (latitude == null || longitude == null) return;
    final request = ++_nearbyRequest;
    setState(() {
      _loadingNearby = true;
      _nearbyError = null;
    });
    try {
      final places = await widget.checkInRepository!.getNearbyPlaces(latitude: latitude, longitude: longitude);
      if (!mounted || request != _nearbyRequest) return;
      setState(() => _nearby = places);
    } catch (e) {
      if (!mounted || request != _nearbyRequest) return;
      setState(() => _nearbyError = e is NetworkException
          ? 'Sem conexão com o servidor. Verifique sua internet e tente novamente.'
          : e is ApiException
              ? e.detail
              : 'Não foi possível buscar locais próximos.');
    } finally {
      if (mounted && request == _nearbyRequest) setState(() => _loadingNearby = false);
    }
  }

  void _select(NearbyPlace place) {
    _notifier!.selectTarget(
      0,
      SearchResultItem(
        id: place.id,
        name: place.name,
        category: place.category,
        targetType: TargetType.place,
        rawTargetType: 'PLACE',
        status: 'ACTIVE',
      ),
    );
    setState(() {
      _selected = place;
      _ratingChosen = false;
    });
  }

  void _changePlace() {
    _notifier!.clearTargetSelection(0);
    setState(() {
      _selected = null;
      _ratingChosen = false;
    });
  }

  Future<void> _publish() async {
    final notifier = _notifier!;
    final comment = _commentController.text.trim();
    notifier.setExperienceText(comment.isEmpty ? null : comment);
    await notifier.submit();
  }

  void _startOver() {
    _awaitingSettingsReturn = false;
    _notifier!.reset();
    _commentController.clear();
    setState(() {
      _nearby = null;
      _nearbyError = null;
      _selected = null;
      _ratingChosen = false;
    });
  }

  @override
  Widget build(BuildContext context) {
    final notifier = _notifier;
    final body = notifier == null
        ? const Center(child: Text('Check-in indisponível nesta versão do app.'))
        : ListView(
            padding: const EdgeInsets.fromLTRB(16, 16, 16, 96),
            children: [
              if (widget.embedded && widget.onOpenHistory != null)
                Align(
                  alignment: Alignment.centerRight,
                  child: TextButton.icon(
                    key: const Key('checkin_history_button'),
                    onPressed: widget.onOpenHistory,
                    icon: const Icon(Icons.history, size: 18),
                    label: const Text('Meus check-ins'),
                  ),
                ),
              ..._buildContent(context, notifier),
            ],
          );

    if (widget.embedded) {
      return body;
    }

    return Scaffold(
      appBar: AppBar(
        title: const Text('Check-in'),
        actions: [
          if (widget.onOpenHistory != null)
            IconButton(
              key: const Key('checkin_history_button'),
              icon: const Icon(Icons.history),
              tooltip: 'Meus check-ins',
              onPressed: widget.onOpenHistory,
            ),
        ],
      ),
      body: body,
    );
  }

  List<Widget> _buildContent(BuildContext context, ReviewCreateNotifier notifier) {
    final state = notifier.state;
    if (state is ReviewCreateSuccess) {
      return [_ResultCard(review: state.createdReview, onOpenHistory: widget.onOpenHistory, onStartOver: _startOver)];
    }
    if (_selected != null) {
      return _buildForm(context, notifier, _selected!);
    }
    if (notifier.hasLocation) {
      return _buildNearby(context, notifier);
    }
    return _buildLocationStep(context, notifier);
  }

  List<Widget> _buildLocationStep(BuildContext context, ReviewCreateNotifier notifier) {
    final theme = Theme.of(context);
    return [
      Text('Faça check-in num local', style: theme.textTheme.titleLarge),
      const SizedBox(height: 8),
      const Text(
        'O check-in é uma avaliação rápida do local onde você está. Usamos sua localização só agora, para '
        'mostrar os locais próximos e para o Rewit conferir sua presença ao publicar. Nada é acompanhado em '
        'segundo plano.',
      ),
      const SizedBox(height: 16),
      if (notifier.locationStatus == LocationCaptureStatus.error) ...[
        ErrorBanner(
          key: const Key('checkin_location_error'),
          margin: const EdgeInsets.only(bottom: 12),
          message: _locationErrorMessage(notifier),
        ),
        if (notifier.locationFailureReason == LocationFailureReason.permissionDeniedForever)
          Padding(
            padding: const EdgeInsets.only(bottom: 8),
            child: OutlinedButton.icon(
              key: const Key('checkin_open_settings'),
              onPressed: () => _openSettings(deviceLocationSettings: false),
              icon: const Icon(Icons.settings_outlined),
              label: const Text('Abrir configurações'),
            ),
          )
        else if (notifier.locationFailureReason == LocationFailureReason.serviceDisabled)
          Padding(
            padding: const EdgeInsets.only(bottom: 8),
            child: OutlinedButton.icon(
              key: const Key('checkin_open_location_settings'),
              onPressed: () => _openSettings(deviceLocationSettings: true),
              icon: const Icon(Icons.location_on_outlined),
              label: const Text('Ativar localização'),
            ),
          ),
      ],
      FilledButton.icon(
        key: const Key('checkin_locate_button'),
        onPressed: notifier.isRequestingLocation ? null : _locate,
        icon: notifier.isRequestingLocation
            ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
            : const Icon(Icons.my_location),
        label: Text(notifier.isRequestingLocation
            ? 'Obtendo sua localização...'
            : notifier.locationStatus == LocationCaptureStatus.error
                ? 'Tentar novamente'
                : 'Usar minha localização'),
      ),
    ];
  }

  static String _locationErrorMessage(ReviewCreateNotifier notifier) {
    return switch (notifier.locationFailureReason) {
      LocationFailureReason.permissionDenied =>
        'Sem permissão de localização não é possível fazer check-in. Você ainda pode publicar uma avaliação completa pelo botão + (Nova Avaliação).',
      LocationFailureReason.permissionDeniedForever =>
        'A permissão de localização foi negada. Libere-a nas configurações do aparelho para fazer check-in.',
      LocationFailureReason.serviceDisabled =>
        'A localização do aparelho está desligada. Ative-a nas configurações do dispositivo para fazer check-in.',
      LocationFailureReason.timeout => 'Não foi possível obter sua localização a tempo.',
      _ => notifier.locationErrorMessage ?? 'Não foi possível obter sua localização agora.',
    };
  }

  List<Widget> _buildNearby(BuildContext context, ReviewCreateNotifier notifier) {
    final theme = Theme.of(context);
    final nearby = _nearby;
    return [
      Row(
        children: [
          Expanded(child: Text('Locais próximos', style: theme.textTheme.titleLarge)),
          TextButton.icon(
            key: const Key('checkin_relocate_button'),
            onPressed: notifier.isRequestingLocation || _loadingNearby ? null : _locate,
            icon: const Icon(Icons.refresh, size: 18),
            label: const Text('Atualizar'),
          ),
        ],
      ),
      if (notifier.isApproximateLocation)
        _buildApproximateWarning(
          context,
          notifier,
          bannerKey: const Key('checkin_approximate_hint'),
          showActions: true,
        ),
      const SizedBox(height: 8),
      if (_loadingNearby)
        const Padding(
          padding: EdgeInsets.all(24),
          child: Center(child: CircularProgressIndicator(key: Key('checkin_nearby_loading'))),
        )
      else if (_nearbyError != null) ...[
        ErrorBanner(key: const Key('checkin_nearby_error'), message: _nearbyError!),
        const SizedBox(height: 8),
        OutlinedButton(
          key: const Key('checkin_nearby_retry'),
          onPressed: _loadNearby,
          child: const Text('Tentar novamente'),
        ),
      ] else if (nearby != null && nearby.isEmpty)
        const Padding(
          key: Key('checkin_nearby_empty'),
          padding: EdgeInsets.symmetric(vertical: 24),
          child: Text(
            'Nenhum local cadastrado no Rewit por perto. Você pode cadastrar o local em Explorar ou pelo botão + (Nova Avaliação).',
            textAlign: TextAlign.center,
          ),
        )
      else if (nearby != null)
        for (final place in nearby)
          Card(
            elevation: 0,
            child: ListTile(
              key: Key('checkin_place_${place.id}'),
              leading: const Icon(Icons.storefront_outlined),
              title: Text(place.name),
              subtitle: Text([
                if (place.category != null && place.category!.isNotEmpty) place.category!,
                'a ${_formatDistance(place.distanceMeters)}',
              ].join(' · ')),
              onTap: () => _select(place),
            ),
          ),
    ];
  }

  static String _formatDistance(double meters) {
    if (meters < 1000) return '${meters.round()} m';
    return '${(meters / 1000).toStringAsFixed(1).replaceAll('.', ',')} km';
  }

  List<Widget> _buildForm(BuildContext context, ReviewCreateNotifier notifier, NearbyPlace place) {
    final theme = Theme.of(context);
    final state = notifier.state;
    final isSubmitting = notifier.isSubmitting;
    final rating = notifier.targets.first.rating;
    return [
      Text(place.name, style: theme.textTheme.titleLarge),
      if (place.addressText != null && place.addressText!.isNotEmpty)
        Padding(padding: const EdgeInsets.only(top: 2), child: Text(place.addressText!)),
      Align(
        alignment: Alignment.centerLeft,
        child: TextButton(
          key: const Key('checkin_change_place'),
          onPressed: isSubmitting ? null : _changePlace,
          child: const Text('Trocar local'),
        ),
      ),
      const SizedBox(height: 8),
      Text('Sua nota (obrigatória)', style: theme.textTheme.titleSmall),
      const SizedBox(height: 4),
      RatingBarWidget(
        key: const Key('checkin_rating'),
        rating: _ratingChosen ? rating : 0,
        enabled: !isSubmitting,
        onRatingChanged: (value) {
          notifier.updateTarget(0, rating: value);
          setState(() => _ratingChosen = true);
        },
      ),
      if (!_ratingChosen)
        Padding(
          padding: const EdgeInsets.only(top: 4),
          child: Text('Toque nas estrelas para dar sua nota.', style: theme.textTheme.bodySmall),
        ),
      const SizedBox(height: 16),
      TextField(
        key: const Key('checkin_comment'),
        controller: _commentController,
        enabled: !isSubmitting,
        maxLength: 2000,
        maxLines: 3,
        decoration: const InputDecoration(
          labelText: 'Comentário (opcional)',
          border: OutlineInputBorder(),
        ),
      ),
      if (state is ReviewCreateError) ...[
        const SizedBox(height: 8),
        ErrorBanner(key: const Key('checkin_submit_error'), message: state.message),
      ],
      if (notifier.isApproximateLocation) ...[
        const SizedBox(height: 8),
        _buildApproximateWarning(
          context,
          notifier,
          bannerKey: const Key('checkin_form_approximate_hint'),
          showActions: false,
        ),
      ],
      const SizedBox(height: 12),
      FilledButton(
        key: const Key('checkin_publish_button'),
        onPressed: _ratingChosen && !isSubmitting ? _publish : null,
        child: isSubmitting
            ? const SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2))
            : Text(state is ReviewCreateError ? 'Tentar novamente' : 'Publicar check-in'),
      ),
    ];
  }

  Widget _buildApproximateWarning(
    BuildContext context,
    ReviewCreateNotifier notifier, {
    required Key bannerKey,
    required bool showActions,
  }) {
    final semantic = RewitSemanticColors.of(context);
    final accuracyMeters = notifier.locationAccuracyMeters?.round();
    final limitMeters = DeviceLocation.approximateAccuracyThresholdMeters.round();
    final message = notifier.isPermissionApproximate
        ? 'Você concedeu apenas localização aproximada${accuracyMeters != null ? ' ($accuracyMeters m)' : ''}. '
            'Sua avaliação pode ser publicada normalmente, mas o check-in não será verificado até liberar a localização exata (até $limitMeters m).'
        : 'Sua localização está com precisão aproximada${accuracyMeters != null ? ' ($accuracyMeters m, limite de $limitMeters m)' : ''}. '
            'Você pode publicar a avaliação normalmente, mas não há garantia de check-in verificado.';

    return Container(
      key: bannerKey,
      margin: const EdgeInsets.only(top: 4, bottom: 8),
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: semantic.warning.container,
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: semantic.warning.border),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Icon(Icons.warning_amber_rounded, size: 20, color: semantic.warning.accent),
              const SizedBox(width: 8),
              Expanded(
                child: Text(
                  message,
                  style: TextStyle(fontSize: 13, color: semantic.warning.onContainer),
                ),
              ),
            ],
          ),
          if (showActions) ...[
            const SizedBox(height: 6),
            Wrap(
              spacing: 8,
              runSpacing: 4,
              children: [
                TextButton.icon(
                  key: const Key('checkin_approximate_retry'),
                  onPressed: notifier.isRequestingLocation || _loadingNearby ? null : _locate,
                  icon: const Icon(Icons.refresh, size: 16),
                  label: const Text('Tentar novamente'),
                ),
                if (notifier.isPermissionApproximate)
                  TextButton.icon(
                    key: const Key('checkin_approximate_open_settings'),
                    onPressed: notifier.isRequestingLocation || _loadingNearby
                        ? null
                        : () => _openSettings(deviceLocationSettings: false),
                    icon: const Icon(Icons.settings_outlined, size: 16),
                    label: const Text('Abrir configurações'),
                  ),
              ],
            ),
          ],
        ],
      ),
    );
  }
}

/// Resultado devolvido pelo backend depois da publicação.
class _ResultCard extends StatelessWidget {
  final FeedReview review;
  final VoidCallback? onOpenHistory;
  final VoidCallback onStartOver;

  const _ResultCard({required this.review, required this.onOpenHistory, required this.onStartOver});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final checkIn = review.checkIn;
    final verified = checkIn?.isVerified ?? false;
    final notRequested = checkIn?.status == CheckInResultStatus.notRequested;
    final title = verified
        ? 'Presença verificada'
        : notRequested
            ? 'Avaliação publicada (check-in não solicitado)'
            : 'Avaliação publicada sem check-in verificado';
    final message = verified
        ? 'O Rewit confirmou que você estava no local. Sua avaliação foi publicada com o selo de presença.'
        : notRequested
            ? 'Sua avaliação foi publicada sem envio de localização para verificação de presença.'
            : (checkIn?.notVerifiedReasonMessage ??
                'Sua avaliação foi publicada, mas a presença no local não foi verificada.');
    final cardKey = verified
        ? const Key('checkin_result_verified')
        : notRequested
            ? const Key('checkin_result_not_requested')
            : const Key('checkin_result_not_verified');
    return Card(
      key: cardKey,
      elevation: 0,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(12),
        side: BorderSide(color: theme.colorScheme.outlineVariant),
      ),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text(title, style: theme.textTheme.titleMedium),
            const SizedBox(height: 8),
            Text(message, key: const Key('checkin_result_message')),
            const SizedBox(height: 16),
            if (onOpenHistory != null)
              OutlinedButton(
                key: const Key('checkin_result_history'),
                onPressed: onOpenHistory,
                child: const Text('Ver meus check-ins'),
              ),
            const SizedBox(height: 8),
            FilledButton(
              key: const Key('checkin_start_over'),
              onPressed: onStartOver,
              child: const Text('Fazer outro check-in'),
            ),
          ],
        ),
      ),
    );
  }
}
