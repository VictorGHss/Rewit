import 'package:flutter/foundation.dart';

/// Intervalo diário de funcionamento de um estabelecimento [openTime, closeTime).
@immutable
class TimeInterval {
  final String openTime;
  final String closeTime;

  const TimeInterval({
    required this.openTime,
    required this.closeTime,
  });

  /// Retorna o intervalo formatado em português (ex: "08:00 às 18:00").
  String get formatted => '$openTime às $closeTime';

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is TimeInterval &&
          runtimeType == other.runtimeType &&
          openTime == other.openTime &&
          closeTime == other.closeTime;

  @override
  int get hashCode => openTime.hashCode ^ closeTime.hashCode;
}

/// Programação de horários para um dia da semana (1 = Segunda-feira ... 7 = Domingo).
@immutable
class DaySchedule {
  final int dayOfWeek;
  final String dayName;
  final bool closed;
  final List<TimeInterval> intervals;

  const DaySchedule({
    required this.dayOfWeek,
    required this.dayName,
    required this.closed,
    this.intervals = const [],
  });

  /// Nome amigável em português do dia da semana.
  String get localizedDayName {
    switch (dayOfWeek) {
      case 1:
        return 'Segunda-feira';
      case 2:
        return 'Terça-feira';
      case 3:
        return 'Quarta-feira';
      case 4:
        return 'Quinta-feira';
      case 5:
        return 'Sexta-feira';
      case 6:
        return 'Sábado';
      case 7:
        return 'Domingo';
      default:
        return dayName;
    }
  }

  /// Retorna a descrição consolidada dos intervalos do dia ou "Fechado".
  String get formattedSchedule {
    if (closed || intervals.isEmpty) {
      return 'Fechado';
    }
    return intervals.map((i) => i.formatted).join(', ');
  }

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is DaySchedule &&
          runtimeType == other.runtimeType &&
          dayOfWeek == other.dayOfWeek &&
          dayName == other.dayName &&
          closed == other.closed &&
          listEquals(intervals, other.intervals);

  @override
  int get hashCode =>
      dayOfWeek.hashCode ^
      dayName.hashCode ^
      closed.hashCode ^
      Object.hashAll(intervals);
}

/// Horários semanais de funcionamento de um local físico (F2.1).
@immutable
class PlaceOpeningHours {
  final String placeId;
  final bool configured;
  final List<DaySchedule> days;

  const PlaceOpeningHours({
    required this.placeId,
    required this.configured,
    this.days = const [],
  });

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is PlaceOpeningHours &&
          runtimeType == other.runtimeType &&
          placeId == other.placeId &&
          configured == other.configured &&
          listEquals(days, other.days);

  @override
  int get hashCode =>
      placeId.hashCode ^ configured.hashCode ^ Object.hashAll(days);
}

/// Comodidade individual de um estabelecimento (F2.2).
@immutable
class PlaceAmenityItem {
  final String code;
  final String label;

  const PlaceAmenityItem({
    required this.code,
    required this.label,
  });

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is PlaceAmenityItem &&
          runtimeType == other.runtimeType &&
          code == other.code &&
          label == other.label;

  @override
  int get hashCode => code.hashCode ^ label.hashCode;
}

/// Conjunto de comodidades declaradas de um local (F2.2).
@immutable
class PlaceAmenities {
  final String placeId;
  final bool configured;
  final List<PlaceAmenityItem> amenities;

  const PlaceAmenities({
    required this.placeId,
    required this.configured,
    this.amenities = const [],
  });

  /// Indica se o estabelecimento declarou formalmente não possuir comodidades.
  bool get hasDeclaredNone => configured && amenities.isEmpty;

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is PlaceAmenities &&
          runtimeType == other.runtimeType &&
          placeId == other.placeId &&
          configured == other.configured &&
          listEquals(amenities, other.amenities);

  @override
  int get hashCode =>
      placeId.hashCode ^ configured.hashCode ^ Object.hashAll(amenities);
}

/// Resumo do produto global incluído em uma oferta comercial oficial (F2.3).
@immutable
class OfferedProductSummary {
  final String id;
  final String name;
  final String? brand;
  final String? model;
  final String category;
  final String? imageUrl;

  const OfferedProductSummary({
    required this.id,
    required this.name,
    this.brand,
    this.model,
    required this.category,
    this.imageUrl,
  });

  /// Nome com marca/modelo agregados para apresentação resumida.
  String get displayName {
    final extra = [
      if (brand != null && brand!.trim().isNotEmpty) brand!.trim(),
      if (model != null && model!.trim().isNotEmpty) model!.trim(),
    ].join(' ');
    if (extra.isEmpty) return name;
    return '$name ($extra)';
  }

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is OfferedProductSummary &&
          runtimeType == other.runtimeType &&
          id == other.id &&
          name == other.name &&
          brand == other.brand &&
          model == other.model &&
          category == other.category &&
          imageUrl == other.imageUrl;

  @override
  int get hashCode =>
      id.hashCode ^
      name.hashCode ^
      brand.hashCode ^
      model.hashCode ^
      category.hashCode ^
      imageUrl.hashCode;
}

/// Oferta comercial oficial de produto em um estabelecimento (F2.3).
@immutable
class PlaceProductOffer {
  final OfferedProductSummary product;
  final String availability;
  final double? price;
  final String currency;

  const PlaceProductOffer({
    required this.product,
    required this.availability,
    this.price,
    this.currency = 'BRL',
  });

  bool get isAvailable => availability == 'AVAILABLE';
  bool get isOutOfStock => availability == 'OUT_OF_STOCK';

  String get localizedAvailability =>
      isAvailable ? 'Disponível' : 'Sem estoque';

  /// Formatação do preço em reais (BRL):
  /// - null: "Preço não informado"
  /// - 0: "Gratuito"
  /// - > 0: "R$ 12,90"
  String get formattedPrice {
    if (price == null) return 'Preço não informado';
    if (price == 0 || price == 0.0) return 'Gratuito';

    final fixed = price!.toStringAsFixed(2);
    final parts = fixed.split('.');
    final integerPart = parts[0];
    final decimalPart = parts[1];

    final regex = RegExp(r'(\d{1,3})(?=(\d{3})+(?!\d))');
    final formattedInteger =
        integerPart.replaceAllMapped(regex, (match) => '${match[1]}.');

    return 'R\$ $formattedInteger,$decimalPart';
  }

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is PlaceProductOffer &&
          runtimeType == other.runtimeType &&
          product == other.product &&
          availability == other.availability &&
          price == other.price &&
          currency == other.currency;

  @override
  int get hashCode =>
      product.hashCode ^
      availability.hashCode ^
      price.hashCode ^
      currency.hashCode;
}

/// Página de ofertas comerciais de um local.
@immutable
class PlaceOffersPage {
  final List<PlaceProductOffer> content;
  final int pageNumber;
  final int pageSize;
  final int totalElements;
  final int totalPages;
  final bool isLast;

  const PlaceOffersPage({
    this.content = const [],
    this.pageNumber = 0,
    this.pageSize = 50,
    this.totalElements = 0,
    this.totalPages = 0,
    this.isLast = true,
  });

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is PlaceOffersPage &&
          runtimeType == other.runtimeType &&
          listEquals(content, other.content) &&
          pageNumber == other.pageNumber &&
          pageSize == other.pageSize &&
          totalElements == other.totalElements &&
          totalPages == other.totalPages &&
          isLast == other.isLast;

  @override
  int get hashCode =>
      Object.hashAll(content) ^
      pageNumber.hashCode ^
      pageSize.hashCode ^
      totalElements.hashCode ^
      totalPages.hashCode ^
      isLast.hashCode;
}
