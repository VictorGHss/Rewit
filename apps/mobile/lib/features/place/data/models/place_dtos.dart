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
  final String? city;
  final String? state;
  final String? country;
  final double? latitude;
  final double? longitude;
  final int validationRadiusMeters;
  final String origin;
  final bool isVerified;
  final String status;
  final String? photoUrl;
  final String? phone;
  final String? websiteUrl;
  final String? instagramUrl;
  final String? facebookUrl;

  const PlaceDto({
    required this.id,
    required this.name,
    required this.slug,
    required this.category,
    this.description,
    required this.addressText,
    this.streetNumber,
    this.neighborhood,
    this.city,
    this.state,
    this.country,
    this.latitude,
    this.longitude,
    required this.validationRadiusMeters,
    required this.origin,
    required this.isVerified,
    required this.status,
    this.photoUrl,
    this.phone,
    this.websiteUrl,
    this.instagramUrl,
    this.facebookUrl,
  });

  factory PlaceDto.fromJson(Map<String, dynamic> json) {
    final rawId = json['id'];
    final rawName = json['name'];
    if (rawId is! String || rawId.trim().isEmpty || rawName is! String || rawName.trim().isEmpty) {
      throw const FormatException('Resposta inválida do servidor: id ou nome do local ausente.');
    }
    final rawCity = (json['city'] as String?)?.trim();
    final rawState = (json['state'] as String?)?.trim();
    final rawPhotoUrl = (json['photoUrl'] as String?)?.trim();
    final rawPhone = (json['phone'] as String?)?.trim();
    final rawWebsiteUrl = (json['websiteUrl'] as String?)?.trim();
    final rawInstagramUrl = (json['instagramUrl'] as String?)?.trim();
    final rawFacebookUrl = (json['facebookUrl'] as String?)?.trim();
    return PlaceDto(
      id: rawId.trim(),
      name: rawName.trim(),
      slug: (json['slug'] as String?)?.trim() ?? '',
      category: (json['category'] as String?)?.trim() ?? 'GERAL',
      description: json['description'] as String?,
      addressText: (json['addressText'] as String?)?.trim() ?? '',
      streetNumber: json['streetNumber'] as String?,
      neighborhood: json['neighborhood'] as String?,
      city: (rawCity != null && rawCity.isNotEmpty) ? rawCity : null,
      state: (rawState != null && rawState.isNotEmpty) ? rawState : null,
      country: json['country'] as String?,
      latitude: (json['latitude'] as num?)?.toDouble(),
      longitude: (json['longitude'] as num?)?.toDouble(),
      validationRadiusMeters: (json['validationRadiusMeters'] as num?)?.toInt() ?? 50,
      origin: json['origin'] as String? ?? 'USER',
      isVerified: json['isVerified'] as bool? ?? false,
      status: json['status'] as String? ?? 'ACTIVE',
      photoUrl: (rawPhotoUrl != null && rawPhotoUrl.isNotEmpty) ? rawPhotoUrl : null,
      phone: (rawPhone != null && rawPhone.isNotEmpty) ? rawPhone : null,
      websiteUrl: (rawWebsiteUrl != null && rawWebsiteUrl.isNotEmpty) ? rawWebsiteUrl : null,
      instagramUrl: (rawInstagramUrl != null && rawInstagramUrl.isNotEmpty) ? rawInstagramUrl : null,
      facebookUrl: (rawFacebookUrl != null && rawFacebookUrl.isNotEmpty) ? rawFacebookUrl : null,
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
      if (city != null) 'city': city,
      if (state != null) 'state': state,
      if (country != null) 'country': country,
      if (latitude != null) 'latitude': latitude,
      if (longitude != null) 'longitude': longitude,
      'validationRadiusMeters': validationRadiusMeters,
      'origin': origin,
      'isVerified': isVerified,
      'status': status,
      if (photoUrl != null) 'photoUrl': photoUrl,
      if (phone != null) 'phone': phone,
      if (websiteUrl != null) 'websiteUrl': websiteUrl,
      if (instagramUrl != null) 'instagramUrl': instagramUrl,
      if (facebookUrl != null) 'facebookUrl': facebookUrl,
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
      photoUrl: photoUrl,
      phone: phone,
      websiteUrl: websiteUrl,
      instagramUrl: instagramUrl,
      facebookUrl: facebookUrl,
    );
  }
}

/// DTO de requisição para atualização do perfil comercial e contatos públicos (PUT /api/v1/places/{id}/business-profile).
class UpdatePlaceBusinessProfileRequestDto {
  final String businessAccountId;
  final String? description;
  final String? phone;
  final String? websiteUrl;
  final String? instagramUrl;
  final String? facebookUrl;

  const UpdatePlaceBusinessProfileRequestDto({
    required this.businessAccountId,
    this.description,
    this.phone,
    this.websiteUrl,
    this.instagramUrl,
    this.facebookUrl,
  });

  Map<String, dynamic> toJson() {
    return {
      'businessAccountId': businessAccountId,
      'description': (description != null && description!.trim().isNotEmpty) ? description!.trim() : null,
      'phone': (phone != null && phone!.trim().isNotEmpty) ? phone!.trim() : null,
      'websiteUrl': (websiteUrl != null && websiteUrl!.trim().isNotEmpty) ? websiteUrl!.trim() : null,
      'instagramUrl': (instagramUrl != null && instagramUrl!.trim().isNotEmpty) ? instagramUrl!.trim() : null,
      'facebookUrl': (facebookUrl != null && facebookUrl!.trim().isNotEmpty) ? facebookUrl!.trim() : null,
    };
  }
}

/// DTO de requisição para criação de um novo Local Físico (`POST /api/v1/places`).
class CreatePlaceRequestDto {
  final String name;
  final String addressText;
  final String? category;
  final String? description;
  final String? streetNumber;
  final String? neighborhood;
  final String? city;
  final String? state;
  final String? country;
  final double? latitude;
  final double? longitude;

  const CreatePlaceRequestDto({
    required this.name,
    required this.addressText,
    this.category,
    this.description,
    this.streetNumber,
    this.neighborhood,
    this.city,
    this.state,
    this.country,
    this.latitude,
    this.longitude,
  });

  factory CreatePlaceRequestDto.fromEntity(CreatePlaceInput input) {
    return CreatePlaceRequestDto(
      name: input.name.trim(),
      addressText: input.addressText.trim(),
      category: input.category?.trim(),
      description: input.description?.trim(),
      streetNumber: input.streetNumber?.trim(),
      neighborhood: input.neighborhood?.trim(),
      city: input.city?.trim(),
      state: input.state?.trim(),
      country: input.country?.trim(),
      latitude: input.latitude,
      longitude: input.longitude,
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'name': name,
      'addressText': addressText,
      if (category != null && category!.isNotEmpty) 'category': category,
      if (description != null && description!.isNotEmpty) 'description': description,
      if (streetNumber != null && streetNumber!.isNotEmpty) 'streetNumber': streetNumber,
      if (neighborhood != null && neighborhood!.isNotEmpty) 'neighborhood': neighborhood,
      if (city != null && city!.isNotEmpty) 'city': city,
      if (state != null && state!.isNotEmpty) 'state': state,
      if (country != null && country!.isNotEmpty) 'country': country,
      if (latitude != null) 'latitude': latitude,
      if (longitude != null) 'longitude': longitude,
    };
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
