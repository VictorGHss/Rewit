import 'package:flutter/material.dart';
import 'package:rewit_mobile/app/router/app_router.dart';
import 'package:rewit_mobile/features/feed/domain/entities/feed_entities.dart';
import 'package:rewit_mobile/features/feed/presentation/widgets/review_card.dart';
import 'package:rewit_mobile/shared/widgets/error_view.dart';
import 'package:rewit_mobile/shared/widgets/loading_indicator.dart';
import '../../domain/entities/place_detail.dart';
import '../../domain/repositories/place_repository.dart';
import '../state/place_detail_notifier.dart';
import '../state/place_detail_state.dart';

/// Tela completa de detalhe de um Local Físico (Place) no catálogo Rewit (C5.2).
///
/// Exibe informações cadastrais, status de verificação, estatísticas agregadas de avaliação,
/// botão de ação para avaliar o local com pré-seleção e a listagem paginada de avaliações.
class PlaceDetailScreen extends StatefulWidget {
  final String placeId;
  final PlaceRepository? repository;
  final PlaceDetailNotifier? notifier;

  const PlaceDetailScreen({
    super.key,
    required this.placeId,
    this.repository,
    this.notifier,
  });

  @override
  State<PlaceDetailScreen> createState() => _PlaceDetailScreenState();
}

class _PlaceDetailScreenState extends State<PlaceDetailScreen> {
  late final PlaceDetailNotifier _notifier;
  bool _ownsNotifier = false;
  final ScrollController _scrollController = ScrollController();

  @override
  void initState() {
    super.initState();
    if (widget.notifier != null) {
      _notifier = widget.notifier!;
    } else if (widget.repository != null) {
      _notifier = PlaceDetailNotifier(
        repository: widget.repository!,
        placeId: widget.placeId,
      );
      _ownsNotifier = true;
    } else {
      throw StateError('PlaceRepository ou PlaceDetailNotifier deve ser fornecido para PlaceDetailScreen.');
    }

    _scrollController.addListener(_onScroll);

    if (_notifier.state is PlaceDetailInitial) {
      _notifier.loadPlace();
    }
  }

  @override
  void dispose() {
    _scrollController.removeListener(_onScroll);
    _scrollController.dispose();
    if (_ownsNotifier) {
      _notifier.dispose();
    }
    super.dispose();
  }

  void _onScroll() {
    if (!_scrollController.hasClients) return;
    final maxScroll = _scrollController.position.maxScrollExtent;
    final currentScroll = _scrollController.position.pixels;

    // Dispara carregamento das próximas avaliações próximo ao fim da lista (200px)
    if (maxScroll - currentScroll <= 200) {
      _notifier.loadMoreReviews();
    }
  }

  Future<void> _navigateToCreateReview(BuildContext context, PlaceDetail place) async {
    await Navigator.of(context).pushNamed(
      AppRouter.reviewCreate,
      arguments: {
        'targetId': place.id,
        'targetName': place.name,
        'targetType': 'PLACE',
        'category': place.category,
      },
    );
    if (mounted) {
      _notifier.refresh();
    }
  }

  void _navigateToReviewDetail(FeedReview review) {
    Navigator.of(context).pushNamed(
      AppRouter.reviewDetail,
      arguments: review,
    );
  }

  void _navigateToAuthorProfile(String authorId) {
    Navigator.of(context).pushNamed(
      AppRouter.profile,
      arguments: authorId,
    );
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return Scaffold(
      appBar: AppBar(
        title: const Text('Detalhes do Local'),
      ),
      body: ListenableBuilder(
        listenable: _notifier,
        builder: (context, _) {
          final state = _notifier.state;

          if (state is PlaceDetailLoading || state is PlaceDetailInitial) {
            return const Center(
              child: LoadingIndicator(message: 'Carregando detalhes do local...'),
            );
          }

          if (state is PlaceDetailNotFound) {
            return Center(
              child: Padding(
                padding: const EdgeInsets.all(24.0),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Icon(
                      Icons.location_off_outlined,
                      size: 64,
                      color: theme.colorScheme.onSurface.withAlpha(120),
                    ),
                    const SizedBox(height: 16),
                    Text(
                      'Local indisponível',
                      style: theme.textTheme.titleLarge?.copyWith(
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                    const SizedBox(height: 8),
                    Text(
                      'Este local não foi encontrado ou não está mais ativo na comunidade.',
                      textAlign: TextAlign.center,
                      style: TextStyle(
                        color: theme.colorScheme.onSurface.withAlpha(160),
                      ),
                    ),
                    const SizedBox(height: 24),
                    if (Navigator.of(context).canPop())
                      OutlinedButton(
                        onPressed: () => Navigator.of(context).pop(),
                        child: const Text('Voltar'),
                      ),
                  ],
                ),
              ),
            );
          }

          if (state is PlaceDetailError) {
            return Center(
              child: ErrorView(
                title: 'Não foi possível carregar o local',
                message: state.message,
                onRetry: () => _notifier.loadPlace(),
              ),
            );
          }

          if (state is PlaceDetailLoaded) {
            return _buildLoadedContent(context, state, theme);
          }

          return const SizedBox.shrink();
        },
      ),
    );
  }

  Widget _buildLoadedContent(
    BuildContext context,
    PlaceDetailLoaded state,
    ThemeData theme,
  ) {
    final place = state.place;
    final stats = state.stats;

    return RefreshIndicator(
      onRefresh: () => _notifier.refresh(),
      child: ListView(
        controller: _scrollController,
        physics: const AlwaysScrollableScrollPhysics(),
        padding: const EdgeInsets.all(16.0),
        children: [
          // 1. Cabeçalho do Local
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      place.name,
                      style: theme.textTheme.headlineSmall?.copyWith(
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                    const SizedBox(height: 8),
                    Wrap(
                      spacing: 8,
                      runSpacing: 4,
                      crossAxisAlignment: WrapCrossAlignment.center,
                      children: [
                        Chip(
                          label: Text(
                            place.category,
                            style: const TextStyle(fontSize: 12),
                          ),
                          visualDensity: VisualDensity.compact,
                          padding: EdgeInsets.zero,
                          materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
                        ),
                        if (place.isVerified)
                          const Row(
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              Icon(Icons.verified, color: Colors.blue, size: 16),
                              SizedBox(width: 4),
                              Text(
                                'Verificado',
                                style: TextStyle(
                                  color: Colors.blue,
                                  fontWeight: FontWeight.w600,
                                  fontSize: 12,
                                ),
                              ),
                            ],
                          ),
                      ],
                    ),
                  ],
                ),
              ),
            ],
          ),

          const SizedBox(height: 16),

          // 2. Estatísticas Agregadas (Nota e Total de Avaliações)
          Card(
            elevation: 0,
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(12),
              side: BorderSide(color: theme.colorScheme.outlineVariant.withAlpha(80)),
            ),
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16.0, vertical: 12.0),
              child: Row(
                children: [
                  const Icon(Icons.star_rounded, color: Colors.amber, size: 32),
                  const SizedBox(width: 8),
                  if (stats != null && stats.hasReviews) ...[
                    Text(
                      stats.formattedRating,
                      style: theme.textTheme.titleLarge?.copyWith(
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                    const SizedBox(width: 8),
                    Text(
                      '(${stats.reviewsCount} ${stats.reviewsCount == 1 ? 'avaliação' : 'avaliações'})',
                      style: TextStyle(
                        color: theme.colorScheme.onSurface.withAlpha(160),
                        fontSize: 14,
                      ),
                    ),
                  ] else ...[
                    Text(
                      'Sem avaliações ainda',
                      style: TextStyle(
                        color: theme.colorScheme.onSurface.withAlpha(160),
                        fontSize: 14,
                      ),
                    ),
                  ],
                ],
              ),
            ),
          ),

          const SizedBox(height: 16),

          // 3. Ação Principal: Avaliar este Local
          SizedBox(
            width: double.infinity,
            child: ElevatedButton.icon(
              onPressed: () => _navigateToCreateReview(context, place),
              icon: const Icon(Icons.rate_review_outlined),
              label: const Text('Avaliar este local'),
              style: ElevatedButton.styleFrom(
                padding: const EdgeInsets.symmetric(vertical: 14),
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(10),
                ),
              ),
            ),
          ),

          const SizedBox(height: 20),

          // 4. Detalhes Físicos e Geográficos do Local
          Card(
            elevation: 0,
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(12),
              side: BorderSide(color: theme.colorScheme.outlineVariant.withAlpha(80)),
            ),
            child: Padding(
              padding: const EdgeInsets.all(16.0),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    'Informações do Local',
                    style: theme.textTheme.titleMedium?.copyWith(
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                  if (place.description != null && place.description!.trim().isNotEmpty) ...[
                    const SizedBox(height: 10),
                    Text(
                      place.description!.trim(),
                      style: TextStyle(
                        color: theme.colorScheme.onSurface.withAlpha(200),
                        height: 1.3,
                      ),
                    ),
                  ],
                  const SizedBox(height: 12),
                  Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Icon(
                        Icons.location_on_outlined,
                        size: 20,
                        color: theme.colorScheme.primary,
                      ),
                      const SizedBox(width: 8),
                      Expanded(
                        child: Text(
                          place.formattedAddress,
                          style: TextStyle(
                            color: theme.colorScheme.onSurface.withAlpha(200),
                            fontSize: 14,
                          ),
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 8),
                  Row(
                    children: [
                      Icon(
                        Icons.radar_outlined,
                        size: 20,
                        color: theme.colorScheme.primary,
                      ),
                      const SizedBox(width: 8),
                      Text(
                        'Raio de validação presencial: ${place.validationRadiusMeters} metros',
                        style: TextStyle(
                          color: theme.colorScheme.onSurface.withAlpha(160),
                          fontSize: 13,
                        ),
                      ),
                    ],
                  ),
                ],
              ),
            ),
          ),

          const SizedBox(height: 24),

          // 5. Seção de Avaliações
          Text(
            'Avaliações da Comunidade',
            style: theme.textTheme.titleMedium?.copyWith(
              fontWeight: FontWeight.bold,
            ),
          ),
          const SizedBox(height: 8),

          if (state.reviews.isEmpty) ...[
            Card(
              elevation: 0,
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(12),
                side: BorderSide(color: theme.colorScheme.outlineVariant.withAlpha(80)),
              ),
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 16.0, vertical: 32.0),
                child: Column(
                  children: [
                    Icon(
                      Icons.rate_review_outlined,
                      size: 48,
                      color: theme.colorScheme.primary.withAlpha(120),
                    ),
                    const SizedBox(height: 12),
                    Text(
                      'Nenhuma avaliação ainda',
                      style: theme.textTheme.titleMedium?.copyWith(
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                    const SizedBox(height: 4),
                    Text(
                      'Seja a primeira pessoa a compartilhar sua experiência sobre este local!',
                      textAlign: TextAlign.center,
                      style: TextStyle(
                        color: theme.colorScheme.onSurface.withAlpha(150),
                        fontSize: 14,
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ] else ...[
            ...state.reviews.map(
              (review) => Padding(
                padding: const EdgeInsets.only(bottom: 8.0),
                child: ReviewCard(
                  review: review,
                  onTap: () => _navigateToReviewDetail(review),
                  onAuthorTap: (authorId) => _navigateToAuthorProfile(authorId),
                ),
              ),
            ),

            if (state.isLoadingMore)
              const Padding(
                padding: EdgeInsets.symmetric(vertical: 16.0),
                child: Center(child: CircularProgressIndicator()),
              ),

            if (state.loadMoreError != null)
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 8.0),
                child: Column(
                  children: [
                    Text(
                      state.loadMoreError!,
                      style: TextStyle(color: theme.colorScheme.error, fontSize: 13),
                    ),
                    TextButton(
                      onPressed: () => _notifier.loadMoreReviews(),
                      child: const Text('Tentar carregar mais'),
                    ),
                  ],
                ),
              ),
          ],
        ],
      ),
    );
  }
}
