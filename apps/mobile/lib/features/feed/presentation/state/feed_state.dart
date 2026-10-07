import '../../domain/entities/feed_entities.dart';

/// Hierarquia tipada de estados da timeline do Feed V2.
abstract class FeedState {
  const FeedState();
}

/// Estado inicial antes de qualquer requisição.
class FeedInitial extends FeedState {
  const FeedInitial();
}

/// Carregamento inicial da primeira página.
class FeedLoading extends FeedState {
  const FeedLoading();
}

/// Feed carregado com sucesso com itens disponíveis.
class FeedSuccess extends FeedState {
  final List<FeedReview> reviews;
  final int currentPage;
  final int totalPages;
  final bool hasMore;
  final bool isLoadingMore;
  final String? loadMoreError;

  const FeedSuccess({
    required this.reviews,
    required this.currentPage,
    required this.totalPages,
    required this.hasMore,
    this.isLoadingMore = false,
    this.loadMoreError,
  });

  FeedSuccess copyWith({
    List<FeedReview>? reviews,
    int? currentPage,
    int? totalPages,
    bool? hasMore,
    bool? isLoadingMore,
    String? loadMoreError,
    bool clearLoadMoreError = false,
  }) {
    return FeedSuccess(
      reviews: reviews ?? this.reviews,
      currentPage: currentPage ?? this.currentPage,
      totalPages: totalPages ?? this.totalPages,
      hasMore: hasMore ?? this.hasMore,
      isLoadingMore: isLoadingMore ?? this.isLoadingMore,
      loadMoreError: clearLoadMoreError ? null : (loadMoreError ?? this.loadMoreError),
    );
  }
}

/// Nenhuma avaliação disponível no feed (usuário ainda não segue ou autores sem reviews ativas).
class FeedEmpty extends FeedState {
  const FeedEmpty();
}

/// Erro durante o carregamento inicial.
class FeedError extends FeedState {
  final String message;
  final String? code;

  const FeedError({required this.message, this.code});
}
