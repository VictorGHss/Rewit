import '../../domain/entities/feed_entities.dart';

/// DTO de serialização do autor da avaliação.
class FeedAuthorDto {
  final String? id;
  final String? handle;
  final String displayName;
  final String? avatarUrl;
  final bool isAnonymous;

  const FeedAuthorDto({
    this.id,
    this.handle,
    required this.displayName,
    this.avatarUrl,
    this.isAnonymous = false,
  });

  factory FeedAuthorDto.fromJson(Map<String, dynamic> json) {
    return FeedAuthorDto(
      id: json['id'] as String?,
      handle: json['handle'] as String?,
      displayName: json['displayName'] as String? ?? 'Anônimo',
      avatarUrl: json['avatarUrl'] as String?,
      isAnonymous: json['isAnonymous'] as bool? ?? false,
    );
  }

  Map<String, dynamic> toJson() {
    return {
      if (id != null) 'id': id,
      if (handle != null) 'handle': handle,
      'displayName': displayName,
      if (avatarUrl != null) 'avatarUrl': avatarUrl,
      'isAnonymous': isAnonymous,
    };
  }

  FeedAuthor toEntity() {
    return FeedAuthor(
      id: id,
      handle: handle,
      displayName: displayName,
      avatarUrl: avatarUrl,
      isAnonymous: isAnonymous,
    );
  }
}

/// DTO de serialização de alvo avaliado.
class FeedTargetDto {
  final String id;
  final String? reviewId;
  final String targetId;
  final double rating;
  final String? specificComment;
  final DateTime? createdAt;
  final String? targetType;

  const FeedTargetDto({
    required this.id,
    this.reviewId,
    required this.targetId,
    required this.rating,
    this.specificComment,
    this.createdAt,
    this.targetType,
  });

  factory FeedTargetDto.fromJson(Map<String, dynamic> json) {
    DateTime? parsedDate;
    final rawDate = json['createdAt'];
    if (rawDate is String) {
      try {
        parsedDate = DateTime.parse(rawDate);
      } catch (_) {}
    }

    return FeedTargetDto(
      id: json['id'] as String,
      reviewId: json['reviewId'] as String?,
      targetId: json['targetId'] as String,
      rating: (json['rating'] as num?)?.toDouble() ?? 0.0,
      specificComment: json['specificComment'] as String?,
      createdAt: parsedDate,
      targetType: (json['targetType'] ?? json['type']) as String?,
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'id': id,
      if (reviewId != null) 'reviewId': reviewId,
      'targetId': targetId,
      'rating': rating,
      if (specificComment != null) 'specificComment': specificComment,
      if (createdAt != null) 'createdAt': createdAt!.toIso8601String(),
      if (targetType != null) 'targetType': targetType,
    };
  }

  FeedTarget toEntity() {
    return FeedTarget(
      id: id,
      reviewId: reviewId,
      targetId: targetId,
      rating: rating,
      specificComment: specificComment,
      createdAt: createdAt,
      targetType: targetType,
    );
  }
}

/// DTO de serialização de item de avaliação no Feed V2.
class FeedReviewDto {
  final String id;
  final FeedAuthorDto author;
  final String? contextPlaceId;
  final String? experienceText;
  final bool isAnonymous;
  final bool isVerifiedOnSite;
  final String visibility;
  final String status;
  final DateTime createdAt;
  final DateTime? updatedAt;
  final List<FeedTargetDto> targets;
  final int helpfulCount;
  final bool isHelpfulByMe;

  const FeedReviewDto({
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

  factory FeedReviewDto.fromJson(Map<String, dynamic> json) {
    final authorJson = json['author'];
    final authorDto = authorJson is Map<String, dynamic>
        ? FeedAuthorDto.fromJson(authorJson)
        : const FeedAuthorDto(displayName: 'Anônimo', isAnonymous: true);

    DateTime? parsedCreatedAt;
    final rawCreatedAt = json['createdAt'];
    if (rawCreatedAt is String) {
      try {
        parsedCreatedAt = DateTime.parse(rawCreatedAt);
      } catch (_) {}
    }
    parsedCreatedAt ??= DateTime.now();

    DateTime? parsedUpdatedAt;
    final rawUpdatedAt = json['updatedAt'];
    if (rawUpdatedAt is String) {
      try {
        parsedUpdatedAt = DateTime.parse(rawUpdatedAt);
      } catch (_) {}
    }

    final rawTargets = json['targets'] as List<dynamic>?;
    final parsedTargets = rawTargets != null
        ? rawTargets
            .whereType<Map<String, dynamic>>()
            .map((t) => FeedTargetDto.fromJson(t))
            .toList()
        : <FeedTargetDto>[];

    return FeedReviewDto(
      id: json['id'] as String,
      author: authorDto,
      contextPlaceId: json['contextPlaceId'] as String?,
      experienceText: json['experienceText'] as String?,
      isAnonymous: json['isAnonymous'] as bool? ?? false,
      isVerifiedOnSite: json['isVerifiedOnSite'] as bool? ?? false,
      visibility: json['visibility'] as String? ?? 'PUBLIC',
      status: json['status'] as String? ?? 'ACTIVE',
      createdAt: parsedCreatedAt,
      updatedAt: parsedUpdatedAt,
      targets: parsedTargets,
      helpfulCount: (json['helpfulCount'] as num?)?.toInt() ?? 0,
      isHelpfulByMe: json['isHelpfulByMe'] as bool? ?? false,
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'id': id,
      'author': author.toJson(),
      if (contextPlaceId != null) 'contextPlaceId': contextPlaceId,
      if (experienceText != null) 'experienceText': experienceText,
      'isAnonymous': isAnonymous,
      'isVerifiedOnSite': isVerifiedOnSite,
      'visibility': visibility,
      'status': status,
      'createdAt': createdAt.toIso8601String(),
      if (updatedAt != null) 'updatedAt': updatedAt!.toIso8601String(),
      'targets': targets.map((t) => t.toJson()).toList(),
      'helpfulCount': helpfulCount,
      'isHelpfulByMe': isHelpfulByMe,
    };
  }

  FeedReview toEntity() {
    return FeedReview(
      id: id,
      author: author.toEntity(),
      contextPlaceId: contextPlaceId,
      experienceText: experienceText,
      isAnonymous: isAnonymous,
      isVerifiedOnSite: isVerifiedOnSite,
      visibility: visibility,
      status: status,
      createdAt: createdAt,
      updatedAt: updatedAt,
      targets: targets.map((t) => t.toEntity()).toList(),
      helpfulCount: helpfulCount,
      isHelpfulByMe: isHelpfulByMe,
    );
  }
}

/// DTO de resposta paginada do Feed V2 (/api/v2/feed).
class FeedPageDto {
  final List<FeedReviewDto> items;
  final int page;
  final int size;
  final int windowSize;
  final int totalPages;

  const FeedPageDto({
    required this.items,
    required this.page,
    required this.size,
    required this.windowSize,
    required this.totalPages,
  });

  factory FeedPageDto.fromJson(Map<String, dynamic> json) {
    final rawItems = json['items'] as List<dynamic>?;
    final itemsList = rawItems != null
        ? rawItems
            .whereType<Map<String, dynamic>>()
            .map((item) => FeedReviewDto.fromJson(item))
            .toList()
        : <FeedReviewDto>[];

    return FeedPageDto(
      items: itemsList,
      page: (json['page'] as num?)?.toInt() ?? 0,
      size: (json['size'] as num?)?.toInt() ?? 10,
      windowSize: (json['windowSize'] as num?)?.toInt() ?? 0,
      totalPages: (json['totalPages'] as num?)?.toInt() ?? 0,
    );
  }

  FeedPage toEntity() {
    return FeedPage(
      items: items.map((i) => i.toEntity()).toList(),
      page: page,
      size: size,
      windowSize: windowSize,
      totalPages: totalPages,
    );
  }
}
