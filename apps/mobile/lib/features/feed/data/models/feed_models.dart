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
  final String? targetName;

  const FeedTargetDto({
    required this.id,
    this.reviewId,
    required this.targetId,
    required this.rating,
    this.specificComment,
    this.createdAt,
    this.targetType,
    this.targetName,
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
      targetName: (json['targetName'] ?? json['name']) as String?,
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
      if (targetName != null) 'targetName': targetName,
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
      targetName: targetName,
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
  final bool? isMine;

  /// Só na resposta de `POST /api/v1/reviews`.
  final CheckInResult? checkIn;

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
    this.isMine,
    this.checkIn,
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
      isMine: json['isMine'] as bool?,
      checkIn: CheckInResult.fromJson(json['checkIn']),
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
      if (isMine != null) 'isMine': isMine,
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
      isMine: isMine,
      checkIn: checkIn,
    );
  }
}

/// DTO de resposta paginada aceitando os envelopes de Feed V1 (/api/v1/feed) e Feed V2 (/api/v2/feed).
class FeedPageDto {
  final List<FeedReviewDto> items;
  final int page;
  final int size;
  final int windowSize;
  final int totalPages;
  final int? totalElements;
  final bool? isLast;

  const FeedPageDto({
    required this.items,
    required this.page,
    required this.size,
    required this.windowSize,
    required this.totalPages,
    this.totalElements,
    this.isLast,
  });

  factory FeedPageDto.fromJson(Map<String, dynamic> json) {
    // 1. Validação estrutural do envelope: exige explicitamente a presença de 'items' (V2) ou 'content' (V1).
    // Não transforma silenciosamente respostas estruturalmente inválidas (ex.: {}, payloads de erro) em páginas vazias.
    if (!json.containsKey('items') && !json.containsKey('content')) {
      throw const FormatException(
        'Envelope de feed inválido: esperado "items" (V2) ou "content" (V1)',
      );
    }

    final dynamic rawList = json['items'] ?? json['content'];
    if (rawList is! List) {
      throw const FormatException(
        'Envelope de feed inválido: a lista de itens ("items" ou "content") deve ser uma lista',
      );
    }

    final itemsList = <FeedReviewDto>[];
    for (final element in rawList) {
      if (element is! Map<String, dynamic>) {
        throw const FormatException(
          'Elemento de avaliação inválido dentro da lista do feed',
        );
      }
      itemsList.add(FeedReviewDto.fromJson(element));
    }

    // 2. Extração segura dos metadados de paginação com conversão robusta para int
    final bool isV1 = json.containsKey('content') &&
        (!json.containsKey('items') || json.containsKey('pageNumber'));

    final int page = _parseInt(
      isV1 ? (json['pageNumber'] ?? json['page']) : (json['page'] ?? json['pageNumber']),
      defaultValue: 0,
    );

    final int size = _parseInt(
      isV1 ? (json['pageSize'] ?? json['size']) : (json['size'] ?? json['pageSize']),
      defaultValue: 10,
    );

    final int? totalElements = _parseNullableInt(json['totalElements']);
    final int totalPages = _parseInt(json['totalPages'], defaultValue: 0);

    final int windowSize = _parseInt(
      json['windowSize'],
      defaultValue: isV1 ? (totalElements ?? 0) : 0,
    );

    final bool? isLast = _parseNullableBool(json['isLast']) ??
        (isV1 || json.containsKey('isLast')
            ? (totalPages == 0 || page >= totalPages - 1)
            : null);

    return FeedPageDto(
      items: itemsList,
      page: page,
      size: size,
      windowSize: windowSize,
      totalPages: totalPages,
      totalElements: totalElements,
      isLast: isLast,
    );
  }

  static int _parseInt(dynamic value, {int defaultValue = 0}) {
    if (value == null) return defaultValue;
    if (value is int) return value;
    if (value is num) return value.toInt();
    if (value is String) {
      return int.tryParse(value) ?? defaultValue;
    }
    return defaultValue;
  }

  static int? _parseNullableInt(dynamic value) {
    if (value == null) return null;
    if (value is int) return value;
    if (value is num) return value.toInt();
    if (value is String) {
      return int.tryParse(value);
    }
    return null;
  }

  static bool? _parseNullableBool(dynamic value) {
    if (value == null) return null;
    if (value is bool) return value;
    if (value is String) {
      final normalized = value.trim().toLowerCase();
      if (normalized == 'true') return true;
      if (normalized == 'false') return false;
    }
    return null;
  }

  FeedPage toEntity() {
    return FeedPage(
      items: items.map((i) => i.toEntity()).toList(),
      page: page,
      size: size,
      windowSize: windowSize,
      totalPages: totalPages,
      totalElements: totalElements,
      isLast: isLast,
    );
  }
}
