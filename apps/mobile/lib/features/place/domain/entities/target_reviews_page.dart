import '../../../feed/domain/entities/feed_entities.dart';

/// Envelope paginado de avaliações de um alvo avaliável (RateableTarget).
class TargetReviewsPage {
  final List<FeedReview> reviews;
  final int pageNumber;
  final int pageSize;
  final int totalElements;
  final int totalPages;
  final bool isLast;

  const TargetReviewsPage({
    required this.reviews,
    required this.pageNumber,
    required this.pageSize,
    required this.totalElements,
    required this.totalPages,
    required this.isLast,
  });

  bool get isEmpty => reviews.isEmpty;
  bool get isNotEmpty => reviews.isNotEmpty;
}
