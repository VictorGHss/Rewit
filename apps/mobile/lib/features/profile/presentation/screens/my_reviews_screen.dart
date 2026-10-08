import 'package:flutter/material.dart';
import 'package:rewit_mobile/app/router/app_router.dart';
import 'package:rewit_mobile/features/feed/domain/entities/feed_entities.dart';
import 'package:rewit_mobile/features/feed/presentation/widgets/review_card.dart';
import 'package:rewit_mobile/features/profile/domain/repositories/user_profile_repository.dart';
import 'package:rewit_mobile/features/profile/presentation/state/my_reviews_notifier.dart';
import 'package:rewit_mobile/features/profile/presentation/state/my_reviews_state.dart';
import 'package:rewit_mobile/features/review_detail/domain/repositories/review_media_repository.dart';

/// Tela dedicada para listagem de avaliações do próprio usuário autenticado ("Minhas avaliações").
/// Suporta paginação incremental, pull-to-refresh, deduplicação e sincronização com edição/exclusão.
class MyReviewsScreen extends StatefulWidget {
  final UserProfileRepository? repository;
  final MyReviewsNotifier? notifier;
  final ReviewMediaRepository? mediaRepository;

  const MyReviewsScreen({
    super.key,
    this.repository,
    this.notifier,
    this.mediaRepository,
  });

  @override
  State<MyReviewsScreen> createState() => _MyReviewsScreenState();
}

class _MyReviewsScreenState extends State<MyReviewsScreen> {
  late final MyReviewsNotifier _notifier;
  late final ScrollController _scrollController;
  bool _ownsNotifier = false;

  @override
  void initState() {
    super.initState();
    _scrollController = ScrollController();
    _scrollController.addListener(_onScroll);

    if (widget.notifier != null) {
      _notifier = widget.notifier!;
      _ownsNotifier = false;
    } else if (widget.repository != null) {
      _notifier = MyReviewsNotifier(repository: widget.repository!);
      _ownsNotifier = true;
      _notifier.loadInitial();
    } else {
      throw ArgumentError('É obrigatório fornecer repository ou notifier para MyReviewsScreen.');
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
    if (currentScroll >= maxScroll - 200) {
      _notifier.loadMore();
    }
  }

  Future<void> _openReviewDetail(FeedReview review) async {
    final result = await Navigator.of(context).pushNamed(
      AppRouter.reviewDetail,
      arguments: review,
    );

    if (!mounted || result == null) return;

    if (result is FeedReview) {
      _notifier.updateReview(result);
    } else if (result is Map && result['deleted'] == true) {
      final id = result['reviewId'] as String? ?? review.id;
      _notifier.removeReview(id);
    }
  }

  void _navigateToTarget(String targetId, String targetType) {
    if (targetType == 'PLACE') {
      Navigator.of(context).pushNamed(
        AppRouter.placeDetail,
        arguments: targetId,
      );
    } else if (targetType == 'PRODUCT') {
      Navigator.of(context).pushNamed(
        AppRouter.productDetail,
        arguments: targetId,
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return Scaffold(
      appBar: AppBar(
        title: const Text('Minhas avaliações'),
      ),
      body: ListenableBuilder(
        listenable: _notifier,
        builder: (context, _) {
          final state = _notifier.state;

          if (state is MyReviewsInitial || state is MyReviewsLoading) {
            return const Center(
              child: CircularProgressIndicator(
                key: Key('my_reviews_loading'),
              ),
            );
          }

          if (state is MyReviewsError) {
            return Center(
              child: Padding(
                padding: const EdgeInsets.all(24.0),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Icon(
                      Icons.error_outline,
                      size: 48,
                      color: theme.colorScheme.error,
                    ),
                    const SizedBox(height: 16),
                    Text(
                      state.message,
                      textAlign: TextAlign.center,
                      style: theme.textTheme.bodyLarge?.copyWith(
                        color: theme.colorScheme.onSurface,
                      ),
                    ),
                    const SizedBox(height: 20),
                    FilledButton.icon(
                      key: const Key('my_reviews_retry_button'),
                      onPressed: _notifier.retry,
                      icon: const Icon(Icons.refresh),
                      label: const Text('Tentar novamente'),
                    ),
                  ],
                ),
              ),
            );
          }

          if (state is MyReviewsEmpty) {
            return RefreshIndicator(
              onRefresh: _notifier.refresh,
              child: LayoutBuilder(
                builder: (context, constraints) => SingleChildScrollView(
                  physics: const AlwaysScrollableScrollPhysics(),
                  child: ConstrainedBox(
                    constraints: BoxConstraints(minHeight: constraints.maxHeight),
                    child: Center(
                      child: Padding(
                        padding: const EdgeInsets.all(24.0),
                        child: Column(
                          key: const Key('my_reviews_empty'),
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            Icon(
                              Icons.rate_review_outlined,
                              size: 64,
                              color: theme.colorScheme.onSurface.withAlpha(120),
                            ),
                            const SizedBox(height: 16),
                            Text(
                              'Nenhuma avaliação encontrada',
                              style: theme.textTheme.titleMedium?.copyWith(
                                fontWeight: FontWeight.bold,
                              ),
                            ),
                            const SizedBox(height: 8),
                            Text(
                              'Você ainda não publicou nenhuma avaliação.',
                              textAlign: TextAlign.center,
                              style: theme.textTheme.bodyMedium?.copyWith(
                                color: theme.colorScheme.onSurface.withAlpha(160),
                              ),
                            ),
                          ],
                        ),
                      ),
                    ),
                  ),
                ),
              ),
            );
          }

          if (state is MyReviewsLoaded) {
            final hasFooter = state.isLoadingMore || state.loadMoreError != null;
            final itemCount = state.reviews.length + (hasFooter ? 1 : 0);

            return RefreshIndicator(
              onRefresh: _notifier.refresh,
              child: ListView.separated(
                controller: _scrollController,
                physics: const AlwaysScrollableScrollPhysics(),
                padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
                itemCount: itemCount,
                separatorBuilder: (context, index) => const SizedBox(height: 12),
                itemBuilder: (context, index) {
                  if (index >= state.reviews.length) {
                    if (state.isLoadingMore) {
                      return const Padding(
                        padding: EdgeInsets.symmetric(vertical: 16.0),
                        child: Center(
                          child: CircularProgressIndicator(strokeWidth: 2.5),
                        ),
                      );
                    }
                    if (state.loadMoreError != null) {
                      return Padding(
                        padding: const EdgeInsets.symmetric(vertical: 12.0),
                        child: Center(
                          child: Column(
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              Text(
                                state.loadMoreError!,
                                style: TextStyle(
                                  color: theme.colorScheme.error,
                                  fontSize: 13,
                                ),
                              ),
                              TextButton.icon(
                                onPressed: _notifier.loadMore,
                                icon: const Icon(Icons.refresh, size: 16),
                                label: const Text('Tentar novamente'),
                              ),
                            ],
                          ),
                        ),
                      );
                    }
                    return const SizedBox.shrink();
                  }

                  final review = state.reviews[index];
                  return ReviewCard(
                    key: Key('review_card_${review.id}'),
                    review: review,
                    mediaRepository: widget.mediaRepository,
                    onTap: () => _openReviewDetail(review),
                    onTargetTap: _navigateToTarget,
                  );
                },
              ),
            );
          }

          return const SizedBox.shrink();
        },
      ),
    );
  }
}
