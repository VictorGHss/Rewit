/// Entidade de autor de avaliação no Feed V2.
class FeedAuthor {
  final String? id;
  final String? handle;
  final String displayName;
  final String? avatarUrl;
  final bool isAnonymous;

  const FeedAuthor({
    this.id,
    this.handle,
    required this.displayName,
    this.avatarUrl,
    this.isAnonymous = false,
  });

  /// Retorna o nome ou identificador seguro para exibição.
  String get displayHandle => isAnonymous ? 'Anônimo' : (handle != null ? '@$handle' : displayName);
}

/// Entidade de alvo avaliado (estabelecimento, produto, serviço).
class FeedTarget {
  final String id;
  final String? reviewId;
  final String targetId;
  final double rating;
  final String? specificComment;
  final DateTime? createdAt;

  const FeedTarget({
    required this.id,
    this.reviewId,
    required this.targetId,
    required this.rating,
    this.specificComment,
    this.createdAt,
  });
}

/// Entidade representacional de uma avaliação ranqueada e diversificada no Feed V2.
class FeedReview {
  final String id;
  final FeedAuthor author;
  final String? contextPlaceId;
  final String? experienceText;
  final bool isAnonymous;
  final bool isVerifiedOnSite;
  final String visibility;
  final String status;
  final DateTime createdAt;
  final DateTime? updatedAt;
  final List<FeedTarget> targets;
  final int helpfulCount;
  final bool isHelpfulByMe;

  const FeedReview({
    required this.id,
    required this.author,
    this.contextPlaceId,
    this.experienceText,
    this.isAnonymous = false,
    this.isVerifiedOnSite = false,
    required this.visibility,
    required this.status,
    required this.createdAt,
    this.updatedAt,
    this.targets = const [],
    this.helpfulCount = 0,
    this.isHelpfulByMe = false,
  });

  /// Média das notas dos alvos vinculados a esta avaliação.
  double? get averageRating {
    if (targets.isEmpty) return null;
    final sum = targets.fold<double>(0.0, (acc, t) => acc + t.rating);
    return sum / targets.length;
  }
}

/// Página de feed ranqueada devolvida pelo backend.
class FeedPage {
  final List<FeedReview> items;
  final int page;
  final int size;
  final int windowSize;
  final int totalPages;

  const FeedPage({
    required this.items,
    required this.page,
    required this.size,
    required this.windowSize,
    required this.totalPages,
  });

  bool get hasMore => page + 1 < totalPages;
  bool get isEmpty => items.isEmpty;
}
