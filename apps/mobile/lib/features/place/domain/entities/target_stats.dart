/// Estatísticas agregadas calculadas para um alvo avaliável (RateableTarget).
class TargetStats {
  final String targetId;
  final double averageRating;
  final int reviewsCount;
  final DateTime? lastCalculatedAt;

  const TargetStats({
    required this.targetId,
    required this.averageRating,
    required this.reviewsCount,
    this.lastCalculatedAt,
  });

  bool get hasReviews => reviewsCount > 0;
  String get formattedRating => averageRating.toStringAsFixed(1);
}
