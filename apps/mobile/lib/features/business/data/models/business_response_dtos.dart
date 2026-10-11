import '../../domain/entities/business_response_entities.dart';

/// DTO para deserializar a resposta pública de uma resposta oficial (BusinessResponseResponse).
class BusinessResponseDto {
  final String id;
  final String reviewId;
  final String? discussionId;
  final String businessAccountId;
  final String corporateName;
  final String placeId;
  final String placeName;
  final String content;
  final DateTime createdAt;

  const BusinessResponseDto({
    required this.id,
    required this.reviewId,
    this.discussionId,
    required this.businessAccountId,
    required this.corporateName,
    required this.placeId,
    required this.placeName,
    required this.content,
    required this.createdAt,
  });

  factory BusinessResponseDto.fromJson(Map<String, dynamic> json) {
    return BusinessResponseDto(
      id: json['id'] as String? ?? '',
      reviewId: json['reviewId'] as String? ?? '',
      discussionId: json['discussionId'] as String?,
      businessAccountId: json['businessAccountId'] as String? ?? '',
      corporateName: json['corporateName'] as String? ?? '',
      placeId: json['placeId'] as String? ?? '',
      placeName: json['placeName'] as String? ?? '',
      content: json['content'] as String? ?? '',
      createdAt: json['createdAt'] != null
          ? DateTime.tryParse(json['createdAt'].toString()) ?? DateTime.now()
          : DateTime.now(),
    );
  }

  BusinessResponse toEntity() {
    return BusinessResponse(
      id: id,
      reviewId: reviewId,
      discussionId: discussionId,
      businessAccountId: businessAccountId,
      corporateName: corporateName,
      placeId: placeId,
      placeName: placeName,
      content: content,
      createdAt: createdAt,
    );
  }
}

/// DTO para deserializar envelope paginado de respostas oficiais (PagedResponse).
class BusinessResponsePageDto {
  final List<BusinessResponseDto> content;
  final int pageNumber;
  final int pageSize;
  final int totalElements;
  final int totalPages;
  final bool isLast;

  const BusinessResponsePageDto({
    required this.content,
    required this.pageNumber,
    required this.pageSize,
    required this.totalElements,
    required this.totalPages,
    required this.isLast,
  });

  factory BusinessResponsePageDto.fromJson(Map<String, dynamic> json) {
    final rawContent = json['content'];
    final List<BusinessResponseDto> items;
    if (rawContent is List) {
      items = rawContent
          .whereType<Map<String, dynamic>>()
          .map((item) => BusinessResponseDto.fromJson(item))
          .toList();
    } else {
      items = const [];
    }

    return BusinessResponsePageDto(
      content: items,
      pageNumber: (json['pageNumber'] as num?)?.toInt() ?? 0,
      pageSize: (json['pageSize'] as num?)?.toInt() ?? 20,
      totalElements: (json['totalElements'] as num?)?.toInt() ?? items.length,
      totalPages: (json['totalPages'] as num?)?.toInt() ?? 1,
      isLast: json['isLast'] as bool? ?? true,
    );
  }

  BusinessResponsePage toEntity() {
    return BusinessResponsePage(
      content: content.map((dto) => dto.toEntity()).toList(),
      pageNumber: pageNumber,
      pageSize: pageSize,
      totalElements: totalElements,
      totalPages: totalPages,
      isLast: isLast,
    );
  }
}

/// DTO para deserializar um local elegível vinculado.
class EligiblePlaceDto {
  final String placeId;
  final String placeName;

  const EligiblePlaceDto({
    required this.placeId,
    required this.placeName,
  });

  factory EligiblePlaceDto.fromJson(Map<String, dynamic> json) {
    return EligiblePlaceDto(
      placeId: json['placeId'] as String? ?? '',
      placeName: json['placeName'] as String? ?? '',
    );
  }

  EligiblePlace toEntity() {
    return EligiblePlace(
      placeId: placeId,
      placeName: placeName,
    );
  }
}

/// DTO para deserializar uma conta comercial elegível.
class EligibleBusinessAccountDto {
  final String businessAccountId;
  final String corporateName;
  final List<EligiblePlaceDto> places;
  final String? activeResponseId;
  final bool canPublish;

  const EligibleBusinessAccountDto({
    required this.businessAccountId,
    required this.corporateName,
    required this.places,
    this.activeResponseId,
    required this.canPublish,
  });

  factory EligibleBusinessAccountDto.fromJson(Map<String, dynamic> json) {
    final rawPlaces = json['places'];
    final List<EligiblePlaceDto> placesList;
    if (rawPlaces is List) {
      placesList = rawPlaces
          .whereType<Map<String, dynamic>>()
          .map((p) => EligiblePlaceDto.fromJson(p))
          .toList();
    } else {
      placesList = const [];
    }

    return EligibleBusinessAccountDto(
      businessAccountId: json['businessAccountId'] as String? ?? '',
      corporateName: json['corporateName'] as String? ?? '',
      places: placesList,
      activeResponseId: json['activeResponseId'] as String?,
      canPublish: json['canPublish'] as bool? ?? false,
    );
  }

  EligibleBusinessAccount toEntity() {
    return EligibleBusinessAccount(
      businessAccountId: businessAccountId,
      corporateName: corporateName,
      places: places.map((p) => p.toEntity()).toList(),
      activeResponseId: activeResponseId,
      canPublish: canPublish,
    );
  }
}

/// DTO para deserializar o resultado de elegibilidade (BusinessResponseEligibilityResponse).
class BusinessResponseEligibilityDto {
  final String reviewId;
  final bool respondable;
  final List<EligibleBusinessAccountDto> accounts;

  const BusinessResponseEligibilityDto({
    required this.reviewId,
    required this.respondable,
    required this.accounts,
  });

  factory BusinessResponseEligibilityDto.fromJson(Map<String, dynamic> json) {
    final rawAccounts = json['accounts'];
    final List<EligibleBusinessAccountDto> accountsList;
    if (rawAccounts is List) {
      accountsList = rawAccounts
          .whereType<Map<String, dynamic>>()
          .map((a) => EligibleBusinessAccountDto.fromJson(a))
          .toList();
    } else {
      accountsList = const [];
    }

    return BusinessResponseEligibilityDto(
      reviewId: json['reviewId'] as String? ?? '',
      respondable: json['respondable'] as bool? ?? false,
      accounts: accountsList,
    );
  }

  BusinessResponseEligibility toEntity() {
    return BusinessResponseEligibility(
      reviewId: reviewId,
      respondable: respondable,
      accounts: accounts.map((a) => a.toEntity()).toList(),
    );
  }
}
