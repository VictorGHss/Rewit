import '../../domain/entities/place_commercial_entities.dart';

/// DTO para intervalo de horário [openTime, closeTime).
class TimeIntervalDto {
  final String openTime;
  final String closeTime;

  const TimeIntervalDto({
    required this.openTime,
    required this.closeTime,
  });

  factory TimeIntervalDto.fromJson(Map<String, dynamic> json) {
    return TimeIntervalDto(
      openTime: json['openTime'] as String? ?? '',
      closeTime: json['closeTime'] as String? ?? '',
    );
  }

  TimeInterval toEntity() {
    return TimeInterval(
      openTime: openTime,
      closeTime: closeTime,
    );
  }
}

/// DTO para programação de um dia da semana.
class DayScheduleDto {
  final int dayOfWeek;
  final String dayName;
  final bool closed;
  final List<TimeIntervalDto> intervals;

  const DayScheduleDto({
    required this.dayOfWeek,
    required this.dayName,
    required this.closed,
    this.intervals = const [],
  });

  factory DayScheduleDto.fromJson(Map<String, dynamic> json) {
    final rawIntervals = json['intervals'] as List<dynamic>?;
    final intervalsList = rawIntervals != null
        ? rawIntervals
            .whereType<Map<String, dynamic>>()
            .map((item) => TimeIntervalDto.fromJson(item))
            .toList()
        : <TimeIntervalDto>[];

    return DayScheduleDto(
      dayOfWeek: (json['dayOfWeek'] as num?)?.toInt() ?? 1,
      dayName: json['dayName'] as String? ?? '',
      closed: json['closed'] as bool? ?? false,
      intervals: intervalsList,
    );
  }

  DaySchedule toEntity() {
    return DaySchedule(
      dayOfWeek: dayOfWeek,
      dayName: dayName,
      closed: closed,
      intervals: intervals.map((i) => i.toEntity()).toList(),
    );
  }
}

/// DTO para resposta de horários de funcionamento (F2.1).
class PlaceOpeningHoursDto {
  final String placeId;
  final bool configured;
  final List<DayScheduleDto> days;

  const PlaceOpeningHoursDto({
    required this.placeId,
    required this.configured,
    this.days = const [],
  });

  factory PlaceOpeningHoursDto.fromJson(Map<String, dynamic> json) {
    final rawDays = json['days'] as List<dynamic>?;
    final daysList = rawDays != null
        ? rawDays
            .whereType<Map<String, dynamic>>()
            .map((item) => DayScheduleDto.fromJson(item))
            .toList()
        : <DayScheduleDto>[];

    return PlaceOpeningHoursDto(
      placeId: json['placeId'] as String? ?? '',
      configured: json['configured'] as bool? ?? false,
      days: daysList,
    );
  }

  PlaceOpeningHours toEntity() {
    return PlaceOpeningHours(
      placeId: placeId,
      configured: configured,
      days: days.map((d) => d.toEntity()).toList(),
    );
  }
}

/// DTO para comodidade individual.
class AmenityItemDto {
  final String code;
  final String label;

  const AmenityItemDto({
    required this.code,
    required this.label,
  });

  factory AmenityItemDto.fromJson(Map<String, dynamic> json) {
    return AmenityItemDto(
      code: json['code'] as String? ?? '',
      label: json['label'] as String? ?? '',
    );
  }

  PlaceAmenityItem toEntity() {
    return PlaceAmenityItem(
      code: code,
      label: label,
    );
  }
}

/// DTO para resposta de comodidades (F2.2).
class PlaceAmenitiesDto {
  final String placeId;
  final bool configured;
  final List<AmenityItemDto> amenities;

  const PlaceAmenitiesDto({
    required this.placeId,
    required this.configured,
    this.amenities = const [],
  });

  factory PlaceAmenitiesDto.fromJson(Map<String, dynamic> json) {
    final rawAmenities = json['amenities'] as List<dynamic>?;
    final amenitiesList = rawAmenities != null
        ? rawAmenities
            .whereType<Map<String, dynamic>>()
            .map((item) => AmenityItemDto.fromJson(item))
            .toList()
        : <AmenityItemDto>[];

    return PlaceAmenitiesDto(
      placeId: json['placeId'] as String? ?? '',
      configured: json['configured'] as bool? ?? false,
      amenities: amenitiesList,
    );
  }

  PlaceAmenities toEntity() {
    return PlaceAmenities(
      placeId: placeId,
      configured: configured,
      amenities: amenities.map((a) => a.toEntity()).toList(),
    );
  }
}

/// DTO para resumo do produto ofertado.
class OfferedProductSummaryDto {
  final String id;
  final String name;
  final String? brand;
  final String? model;
  final String category;
  final String? imageUrl;

  const OfferedProductSummaryDto({
    required this.id,
    required this.name,
    this.brand,
    this.model,
    required this.category,
    this.imageUrl,
  });

  factory OfferedProductSummaryDto.fromJson(Map<String, dynamic> json) {
    return OfferedProductSummaryDto(
      id: json['id'] as String? ?? '',
      name: json['name'] as String? ?? '',
      brand: json['brand'] as String?,
      model: json['model'] as String?,
      category: json['category'] as String? ?? '',
      imageUrl: json['imageUrl'] as String?,
    );
  }

  OfferedProductSummary toEntity() {
    return OfferedProductSummary(
      id: id,
      name: name,
      brand: brand,
      model: model,
      category: category,
      imageUrl: imageUrl,
    );
  }
}

/// DTO para oferta comercial individual (F2.3).
class PlaceProductOfferDto {
  final OfferedProductSummaryDto product;
  final String availability;
  final double? price;
  final String currency;

  const PlaceProductOfferDto({
    required this.product,
    required this.availability,
    this.price,
    this.currency = 'BRL',
  });

  factory PlaceProductOfferDto.fromJson(Map<String, dynamic> json) {
    final rawProduct = json['product'] as Map<String, dynamic>? ?? {};
    final rawPrice = json['price'];

    return PlaceProductOfferDto(
      product: OfferedProductSummaryDto.fromJson(rawProduct),
      availability: json['availability'] as String? ?? 'AVAILABLE',
      price: rawPrice is num ? rawPrice.toDouble() : null,
      currency: json['currency'] as String? ?? 'BRL',
    );
  }

  PlaceProductOffer toEntity() {
    return PlaceProductOffer(
      product: product.toEntity(),
      availability: availability,
      price: price,
      currency: currency,
    );
  }
}

/// DTO para página de ofertas comerciais (F2.3).
class PlaceOffersPageDto {
  final List<PlaceProductOfferDto> content;
  final int pageNumber;
  final int pageSize;
  final int totalElements;
  final int totalPages;
  final bool isLast;

  const PlaceOffersPageDto({
    this.content = const [],
    this.pageNumber = 0,
    this.pageSize = 50,
    this.totalElements = 0,
    this.totalPages = 0,
    this.isLast = true,
  });

  factory PlaceOffersPageDto.fromJson(Map<String, dynamic> json) {
    final rawContent = json['content'] as List<dynamic>?;
    final contentList = rawContent != null
        ? rawContent
            .whereType<Map<String, dynamic>>()
            .map((item) => PlaceProductOfferDto.fromJson(item))
            .toList()
        : <PlaceProductOfferDto>[];

    return PlaceOffersPageDto(
      content: contentList,
      pageNumber: (json['pageNumber'] as num?)?.toInt() ?? 0,
      pageSize: (json['pageSize'] as num?)?.toInt() ?? 50,
      totalElements: (json['totalElements'] as num?)?.toInt() ?? contentList.length,
      totalPages: (json['totalPages'] as num?)?.toInt() ?? 1,
      isLast: json['isLast'] as bool? ?? true,
    );
  }

  PlaceOffersPage toEntity() {
    return PlaceOffersPage(
      content: content.map((c) => c.toEntity()).toList(),
      pageNumber: pageNumber,
      pageSize: pageSize,
      totalElements: totalElements,
      totalPages: totalPages,
      isLast: isLast,
    );
  }
}

