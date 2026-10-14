import 'package:flutter/foundation.dart';
import '../../domain/entities/place_commercial_entities.dart';

/// Estado genérico para uma seção isolada de dados comerciais.
sealed class CommercialSectionState<T> {
  const CommercialSectionState();
}

class SectionInitial<T> extends CommercialSectionState<T> {
  const SectionInitial();
}

class SectionLoading<T> extends CommercialSectionState<T> {
  const SectionLoading();
}

class SectionLoaded<T> extends CommercialSectionState<T> {
  final T data;
  const SectionLoaded(this.data);

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is SectionLoaded<T> &&
          runtimeType == other.runtimeType &&
          data == other.data;

  @override
  int get hashCode => data.hashCode;
}

class SectionError<T> extends CommercialSectionState<T> {
  final String message;
  const SectionError(this.message);

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is SectionError<T> &&
          runtimeType == other.runtimeType &&
          message == other.message;

  @override
  int get hashCode => message.hashCode;
}

/// Estado com suporte a paginação para as ofertas comerciais oficiais.
@immutable
class PlaceOffersData {
  final List<PlaceProductOffer> offers;
  final int currentPage;
  final int pageSize;
  final int totalElements;
  final int totalPages;
  final bool isLastPage;
  final bool isLoadingMore;
  final String? loadMoreError;

  const PlaceOffersData({
    this.offers = const [],
    this.currentPage = 0,
    this.pageSize = 50,
    this.totalElements = 0,
    this.totalPages = 0,
    this.isLastPage = true,
    this.isLoadingMore = false,
    this.loadMoreError,
  });

  PlaceOffersData copyWith({
    List<PlaceProductOffer>? offers,
    int? currentPage,
    int? pageSize,
    int? totalElements,
    int? totalPages,
    bool? isLastPage,
    bool? isLoadingMore,
    String? loadMoreError,
    bool clearLoadMoreError = false,
  }) {
    return PlaceOffersData(
      offers: offers ?? this.offers,
      currentPage: currentPage ?? this.currentPage,
      pageSize: pageSize ?? this.pageSize,
      totalElements: totalElements ?? this.totalElements,
      totalPages: totalPages ?? this.totalPages,
      isLastPage: isLastPage ?? this.isLastPage,
      isLoadingMore: isLoadingMore ?? this.isLoadingMore,
      loadMoreError:
          clearLoadMoreError ? null : (loadMoreError ?? this.loadMoreError),
    );
  }

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is PlaceOffersData &&
          runtimeType == other.runtimeType &&
          listEquals(offers, other.offers) &&
          currentPage == other.currentPage &&
          pageSize == other.pageSize &&
          totalElements == other.totalElements &&
          totalPages == other.totalPages &&
          isLastPage == other.isLastPage &&
          isLoadingMore == other.isLoadingMore &&
          loadMoreError == other.loadMoreError;

  @override
  int get hashCode =>
      Object.hashAll(offers) ^
      currentPage.hashCode ^
      pageSize.hashCode ^
      totalElements.hashCode ^
      totalPages.hashCode ^
      isLastPage.hashCode ^
      isLoadingMore.hashCode ^
      loadMoreError.hashCode;
}

/// Estado consolidado das informações comerciais do estabelecimento.
@immutable
class PlaceCommercialState {
  final CommercialSectionState<PlaceOpeningHours> openingHoursState;
  final CommercialSectionState<PlaceAmenities> amenitiesState;
  final CommercialSectionState<PlaceOffersData> offersState;

  const PlaceCommercialState({
    this.openingHoursState = const SectionInitial(),
    this.amenitiesState = const SectionInitial(),
    this.offersState = const SectionInitial(),
  });

  PlaceCommercialState copyWith({
    CommercialSectionState<PlaceOpeningHours>? openingHoursState,
    CommercialSectionState<PlaceAmenities>? amenitiesState,
    CommercialSectionState<PlaceOffersData>? offersState,
  }) {
    return PlaceCommercialState(
      openingHoursState: openingHoursState ?? this.openingHoursState,
      amenitiesState: amenitiesState ?? this.amenitiesState,
      offersState: offersState ?? this.offersState,
    );
  }

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is PlaceCommercialState &&
          runtimeType == other.runtimeType &&
          openingHoursState == other.openingHoursState &&
          amenitiesState == other.amenitiesState &&
          offersState == other.offersState;

  @override
  int get hashCode =>
      openingHoursState.hashCode ^
      amenitiesState.hashCode ^
      offersState.hashCode;
}

