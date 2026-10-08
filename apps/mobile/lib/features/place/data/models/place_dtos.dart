import '../../../feed/data/models/feed_models.dart';
import '../../domain/entities/place_detail.dart';
import '../../domain/entities/target_reviews_page.dart';
import '../../domain/entities/target_stats.dart';

/// DTO de serialização para o detalhe completo de um Local Físico (Place).
class PlaceDto {
  final String id;
  final String name;
  final String slug;
  final String category;
  final String? description;
  final String addressText;
  final String? streetNumber;
  final String? neighborhood;
  final String city;
  final String state;
  final String? country;
  final double latitude;
  final double longitude;
  final int validationRadiusMeters;
  final String origin;
  final bool isVerified;
  final String status;

  const PlaceDto({
    required this.id,
    required this.name,
    required this.slug,
    required this.category,
    this.description,
    required this.addressText,
    this.streetNumber,
    this.neighborhood,
    required this.city,
    required this.state,
    this.country,
    required this.latitude,
    required this.longitude,
    required this.validationRadiusMeters,
    required this.origin,
    required this.isVerified,
    required this.status,
  });

  factory PlaceDto.fromJson(Map<String, dynamic> json) {
    return PlaceDto(
      id: json['id'] as String,
      name: json['name'] as String,
      slug: json['slug'] as String,
      category: json['category'] as String,
      description: json['description'] as String?,
      addressText: json['addressText'] as String,
      streetNumber: json['streetNumber'] as String?,
      neighborhood: json['neighborhood'] as String?,
      city: json['city'] as String,
      state: json['state'] as String,
      country: json['country'] as String?,
      latitude: (json['latitude'] as num).toDouble(),
      longitude: (json['longitude'] as num).toDouble(),
      validationRadiusMeters: (json['validationRadiusMeters'] as num).toInt(),
      origin: json['origin'] as String? ?? 'USER',
      isVerified: json['isVerified'] as bool? ?? false,
      status: json['status'] as String? ?? 'ACTIVE',
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'id': id,
      'name': name,
      'slug': slug,
      'category': category,
      if (description != null) 'description': description,
      'addressText': addressText,
      if (streetNumber != null) 'streetNumber': streetNumber,
      if (neighborhood != null) 'neighborhood': neighborhood,
      'city': city,
      'state': state,
      if (country != null) 'country': country,
      'latitude': latitude,
      'longitude': longitude,
      'validationRadiusMeters': validationRadiusMeters,
      'origin': origin,
      'isVerified': isVerified,
      'status': status,
    };
  }

  PlaceDetail toEntity() {
    return PlaceDetail(
      id: id,
      name: name,
      slug: slug,
      category: category,
      description: description,
      addressText: addressText,
      streetNumber: streetNumber,
      neighborhood: neighborhood,
      city: city,
      state: state,
      country: country,
      latitude: latitude,
      longitude: longitude,
      validationRadiusMeters: validationRadiusMeters,
      origin: origin,
      isVerified: isVerified,
      status: status,
    );
  }
}

/// DTO de serialização para estatísticas agregadas de um alvo avaliável.
class TargetStatsDto {
  final String targetId;
  final double averageRating;
  final int reviewsCount;
  final DateTime? lastCalculatedAt;

  const TargetStatsDto({
    required this.targetId,
    required this.averageRating,
    required this.reviewsCount,
    this.lastCalculatedAt,
  });

  factory TargetStatsDto.fromJson(Map<String, dynamic> json) {
    DateTime? parsedDate;
    final rawDate = json['lastCalculatedAt'];
    if (rawDate is String) {
      try {
        parsedDate = DateTime.parse(rawDate);
      } catch (_) {}
    }

    return TargetStatsDto(
      targetId: json['targetId'] as String,
      averageRating: (json['averageRating'] as num?)?.toDouble() ?? 0.0,
      reviewsCount: (json['reviewsCount'] as num?)?.toInt() ?? 0,
      lastCalculatedAt: parsedDate,
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'targetId': targetId,
      'averageRating': averageRating,
      'reviewsCount': reviewsCount,
      if (lastCalculatedAt != null) 'lastCalculatedAt': lastCalculatedAt!.toIso8601String(),
    };
  }

  TargetStats toEntity() {
    return TargetStats(
      targetId: targetId,
      averageRating: averageRating,
      reviewsCount: reviewsCount,
      lastCalculatedAt: lastCalculatedAt,
    );
  }
}

/// DTO de serialização para resposta paginada de avaliações de um alvo.
class TargetReviewsPageDto {
  final List<FeedReviewDto> content;
  final int pageNumber;
  final int pageSize;
  final int totalElements;
  final int totalPages;
  final bool isLast;

  const TargetReviewsPageDto({
    required this.content,
    required this.pageNumber,
    required this.pageSize,
    required this.totalElements,
    required this.totalPages,
    required this.isLast,
  });

  factory TargetReviewsPageDto.fromJson(Map<String, dynamic> json) {
    final rawList = json['content'] as List<dynamic>? ?? [];
    final content = rawList
        .whereType<Map<String, dynamic>>()
        .map((e) => FeedReviewDto.fromJson(e))
        .toList();

    return TargetReviewsPageDto(
      content: content,
      pageNumber: (json['pageNumber'] as num?)?.toInt() ?? 0,
      pageSize: (json['pageSize'] as num?)?.toInt() ?? 10,
      totalElements: (json['totalElements'] as num?)?.toInt() ?? 0,
      totalPages: (json['totalPages'] as num?)?.toInt() ?? 0,
      isLast: json['isLast'] as bool? ?? true,
    );
  }

  TargetReviewsPage toEntity() {
    return TargetReviewsPage(
      reviews: content.map((e) => e.toEntity()).toList(),
      pageNumber: pageNumber,
      pageSize: pageSize,
      totalElements: totalElements,
      totalPages: totalPages,
      isLast: isLast,
    );
  }
}
