import 'package:flutter/material.dart';
import 'package:rewit_mobile/features/feed/domain/entities/feed_entities.dart';
import 'package:rewit_mobile/features/feed/presentation/state/feed_notifier.dart';
import 'package:rewit_mobile/features/feed/presentation/state/feed_state.dart';
import 'package:rewit_mobile/features/feed/presentation/widgets/review_card.dart';
import 'package:rewit_mobile/shared/widgets/error_view.dart';
import 'package:rewit_mobile/shared/widgets/loading_indicator.dart';

/// Visão principal da timeline do Feed V2 com suporte a paginação infinita e pull-to-refresh.
class FeedView extends StatefulWidget {
  final FeedNotifier feedNotifier;
  final void Function(FeedReview review)? onReviewTap;

  const FeedView({
    super.key,
    required this.feedNotifier,
    this.onReviewTap,
  });

  @override
  State<FeedView> createState() => _FeedViewState();
}

class _FeedViewState extends State<FeedView> {
  final ScrollController _scrollController = ScrollController();

  @override
  void initState() {
    super.initState();
    _scrollController.addListener(_onScroll);
    // Dispara carregamento inicial caso ainda não tenha sido iniciado
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (widget.feedNotifier.state is FeedInitial) {
        widget.feedNotifier.loadInitial();
      }
    });
  }

  @override
  void dispose() {
    _scrollController.removeListener(_onScroll);
    _scrollController.dispose();
    super.dispose();
  }

  void _onScroll() {
    if (!_scrollController.hasClients) return;
    final maxScroll = _scrollController.position.maxScrollExtent;
    final currentScroll = _scrollController.position.pixels;
    // Carrega mais quando o usuário estiver a 200 pixels do final
    if (currentScroll >= maxScroll - 200) {
      widget.feedNotifier.loadMore();
    }
  }

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: widget.feedNotifier,
      builder: (context, _) {
        final state = widget.feedNotifier.state;

        if (state is FeedLoading || state is FeedInitial) {
          return const Center(
            child: LoadingIndicator(message: 'Carregando feed...'),
          );
        }

        if (state is FeedError) {
          return Center(
            child: ErrorView(
              title: 'Não foi possível carregar o feed',
              message: state.message,
              onRetry: () => widget.feedNotifier.retry(),
            ),
          );
        }

        if (state is FeedEmpty) {
          return RefreshIndicator(
            onRefresh: () => widget.feedNotifier.refresh(),
            child: ListView(
              physics: const AlwaysScrollableScrollPhysics(),
              children: [
                SizedBox(height: MediaQuery.of(context).size.height * 0.2),
                Center(
                  child: Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 32.0),
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Icon(
                          Icons.feed_outlined,
                          size: 64,
                          color: Theme.of(context).colorScheme.primary.withAlpha(120),
                        ),
                        const SizedBox(height: 16),
                        Text(
                          'Nenhuma avaliação no feed',
                          style: Theme.of(context).textTheme.titleMedium?.copyWith(
                                fontWeight: FontWeight.bold,
                              ),
                        ),
                        const SizedBox(height: 8),
                        Text(
                          'Quando as pessoas e estabelecimentos que você acompanha publicarem avaliações, elas aparecerão aqui.',
                          textAlign: TextAlign.center,
                          style: TextStyle(
                            color: Theme.of(context).colorScheme.onSurface.withAlpha(160),
                            fontSize: 14,
                          ),
                        ),
                        const SizedBox(height: 24),
                        OutlinedButton.icon(
                          onPressed: () => widget.feedNotifier.refresh(),
                          icon: const Icon(Icons.refresh, size: 18),
                          label: const Text('Atualizar'),
                        ),
                      ],
                    ),
                  ),
                ),
              ],
            ),
          );
        }

        if (state is FeedSuccess) {
          final reviews = state.reviews;

          return RefreshIndicator(
            onRefresh: () => widget.feedNotifier.refresh(),
            child: ListView.builder(
              controller: _scrollController,
              physics: const AlwaysScrollableScrollPhysics(),
              padding: const EdgeInsets.symmetric(vertical: 8.0),
              itemCount: reviews.length + (state.hasMore ? 1 : 0),
              itemBuilder: (context, index) {
                // Item de review
                if (index < reviews.length) {
                  final review = reviews[index];
                  return ReviewCard(
                    review: review,
                    onTap: () => widget.onReviewTap?.call(review),
                  );
                }

                // Rodapé de paginação (loading ou erro de mais itens)
                return Padding(
                  padding: const EdgeInsets.symmetric(vertical: 16.0),
                  child: Center(
                    child: state.isLoadingMore
                        ? const SizedBox(
                            width: 24,
                            height: 24,
                            child: CircularProgressIndicator(strokeWidth: 2.5),
                          )
                        : (state.loadMoreError != null
                            ? Column(
                                children: [
                                  Text(
                                    state.loadMoreError!,
                                    style: TextStyle(
                                      color: Theme.of(context).colorScheme.error,
                                      fontSize: 12,
                                    ),
                                  ),
                                  TextButton.icon(
                                    onPressed: () => widget.feedNotifier.retry(),
                                    icon: const Icon(Icons.refresh, size: 16),
                                    label: const Text('Tentar novamente'),
                                  ),
                                ],
                              )
                            : const SizedBox.shrink()),
                  ),
                );
              },
            ),
          );
        }

        return const SizedBox.shrink();
      },
    );
  }
}
