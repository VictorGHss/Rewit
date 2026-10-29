import 'package:flutter/material.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/features/feed/domain/entities/feed_entities.dart';
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
/// esta tela só mostra o que ele respondeu. O fluxo completo continua na aba Avaliar.
class CheckInScreen extends StatefulWidget {
  final ReviewCreationRepository? reviewCreationRepository;
  final LocationService? locationService;
  final CheckInRepository? checkInRepository;

  /// Abre o histórico de check-ins verificados.
  final VoidCallback? onOpenHistory;

  /// Chamado com a avaliação publicada (ex.: recarregar o feed).
  final ValueChanged<FeedReview>? onReviewCreated;

  const CheckInScreen({
    super.key,
    required this.reviewCreationRepository,
    required this.locationService,
    required this.checkInRepository,
    this.onOpenHistory,
    this.onReviewCreated,
  });

  @override
  State<CheckInScreen> createState() => _CheckInScreenState();
}

class _CheckInScreenState extends State<CheckInScreen> {
  ReviewCreateNotifier? _notifier;
  final TextEditingController _commentController = TextEditingController();

  List<NearbyPlace>? _nearby;
  bool _loadingNearby = false;
  String? _nearbyError;
  int _nearbyRequest = 0;
  NearbyPlace? _selected;
  bool _ratingChosen = false;
  String? _reportedReviewId;

  @override
  void initState() {
    super.initState();
    final repository = widget.reviewCreationRepository;
    if (repository != null && widget.checkInRepository != null) {
      _notifier = ReviewCreateNotifier(repository: repository, locationService: widget.locationService)
        ..addListener(_onNotifierChanged);
    }
  }

  @override
  void dispose() {
    _notifier?.removeListener(_onNotifierChanged);
    _notifier?.dispose();
    _commentController.dispose();
    super.dispose();
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
      body: notifier == null
          ? const Center(child: Text('Check-in indisponível nesta versão do app.'))
          : ListView(
              padding: const EdgeInsets.all(16),
              children: _buildContent(context, notifier),
            ),
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
        if (notifier.locationFailureReason == LocationFailureReason.permissionDeniedForever ||
            notifier.locationFailureReason == LocationFailureReason.serviceDisabled)
          Padding(
            padding: const EdgeInsets.only(bottom: 8),
            child: OutlinedButton.icon(
              key: const Key('checkin_open_settings'),
              onPressed: notifier.openAppSettings,
              icon: const Icon(Icons.settings_outlined),
              label: const Text('Abrir configurações'),
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
        'Sem permissão de localização não é possível fazer check-in. Você ainda pode avaliar pela aba Avaliar.',
      LocationFailureReason.permissionDeniedForever =>
        'A permissão de localização foi negada. Libere-a nas configurações do aparelho para fazer check-in.',
      LocationFailureReason.serviceDisabled => 'A localização do aparelho está desligada.',
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
        const Padding(
          padding: EdgeInsets.only(top: 4, bottom: 8),
          child: Text(
            'Sua localização está aproximada. Você pode publicar, mas o Rewit pode não conseguir confirmar a '
            'presença no local.',
            key: Key('checkin_approximate_hint'),
          ),
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
            'Nenhum local cadastrado no Rewit por perto. Você pode cadastrar o local pela aba Avaliar.',
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
    final title = verified ? 'Presença verificada' : 'Avaliação publicada sem check-in verificado';
    final message = verified
        ? 'O Rewit confirmou que você estava no local. Sua avaliação foi publicada com o selo de presença.'
        : _reasonMessage(checkIn);
    return Card(
      key: Key(verified ? 'checkin_result_verified' : 'checkin_result_not_verified'),
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

  static String _reasonMessage(CheckInResult? checkIn) {
    if (checkIn == null || checkIn.status != CheckInResultStatus.notVerified) {
      return 'Sua avaliação foi publicada, mas a presença no local não foi verificada.';
    }
    return switch (checkIn.reason) {
      'ACCURACY_TOO_LOW' =>
        'A precisão do GPS estava baixa demais para confirmar sua presença. Tente de novo em um lugar aberto.',
      'ACCURACY_UNAVAILABLE' =>
        'O aparelho não informou a precisão da localização, então a presença não pôde ser confirmada.',
      'OUTSIDE_RADIUS' => 'Sua localização estava fora da área do local, então a presença não foi confirmada.',
      'RECENT_CHECK_IN' => 'Você já tem um check-in verificado recente neste local; este não conta como um novo.',
      'PLACE_WITHOUT_COORDINATES' =>
        'Este local não tem localização cadastrada, então a presença não pôde ser verificada.',
      _ => 'Sua avaliação foi publicada, mas a presença no local não foi verificada.',
    };
  }
}
