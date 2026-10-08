import 'package:rewit_mobile/features/feed/domain/entities/feed_entities.dart';

/// Hierarquia de estados para o fluxo de Minhas Avaliações (C5.9).
sealed class MyReviewsState {
  const MyReviewsState();
}

/// Estado inicial antes de qualquer carregamento.
class MyReviewsInitial extends MyReviewsState {
  const MyReviewsInitial();
}

/// Estado de carregamento da primeira página.
class MyReviewsLoading extends MyReviewsState {
  const MyReviewsLoading();
}

/// Estado quando o usuário autenticado não possui nenhuma avaliação.
class MyReviewsEmpty extends MyReviewsState {
  const MyReviewsEmpty();
}

/// Estado de sucesso com dados paginados carregados.
class MyReviewsLoaded extends MyReviewsState {
  final List<FeedReview> reviews;
  final int currentPage;
  final int totalPages;
  final int totalElements;
  final bool isLastPage;
  final bool isLoadingMore;
  final String? loadMoreError;

  const MyReviewsLoaded({
    required this.reviews,
    required this.currentPage,
    required this.totalPages,
    required this.totalElements,
    required this.isLastPage,
    this.isLoadingMore = false,
    this.loadMoreError,
  });

  /// Indica se ainda existem páginas adicionais disponíveis para busca.
  bool get hasMore => !isLastPage && (totalPages == 0 || currentPage + 1 < totalPages);

  MyReviewsLoaded copyWith({
    List<FeedReview>? reviews,
    int? currentPage,
    int? totalPages,
    int? totalElements,
    bool? isLastPage,
    bool? isLoadingMore,
    String? loadMoreError,
    bool clearLoadMoreError = false,
  }) {
    return MyReviewsLoaded(
      reviews: reviews ?? this.reviews,
      currentPage: currentPage ?? this.currentPage,
      totalPages: totalPages ?? this.totalPages,
      totalElements: totalElements ?? this.totalElements,
      isLastPage: isLastPage ?? this.isLastPage,
      isLoadingMore: isLoadingMore ?? this.isLoadingMore,
      loadMoreError: clearLoadMoreError ? null : (loadMoreError ?? this.loadMoreError),
    );
  }
}

/// Estado de falha ao carregar a primeira página de avaliações.
class MyReviewsError extends MyReviewsState {
  final String message;
  final String? code;

  const MyReviewsError({required this.message, this.code});
}
