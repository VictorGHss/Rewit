import 'package:flutter/foundation.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import '../../domain/repositories/place_commercial_repository.dart';
import 'place_commercial_state.dart';

/// Gerenciador de estado reativo para dados comerciais de estabelecimentos (F2.1, F2.2, F2.3).
///
/// Mantém as três seções (horários, comodidades, ofertas) isoladas para que uma falha em uma
/// delas não impeça a exibição das demais.
class PlaceCommercialNotifier extends ChangeNotifier {
  final PlaceCommercialRepository repository;
  final String placeId;

  PlaceCommercialState _state = const PlaceCommercialState();
  PlaceCommercialState get state => _state;

  PlaceCommercialNotifier({
    required this.repository,
    required this.placeId,
  });

  /// Dispara o carregamento concorrente e isolado das três seções comerciais.
  Future<void> loadAll() async {
    await Future.wait([
      loadOpeningHours(),
      loadAmenities(),
      loadOffers(),
    ]);
  }

  /// Carrega os horários de funcionamento do estabelecimento (F2.1).
  Future<void> loadOpeningHours() async {
    _state = _state.copyWith(
      openingHoursState: const SectionLoading(),
    );
    notifyListeners();

    try {
      final hours = await repository.getOpeningHours(placeId);
      _state = _state.copyWith(
        openingHoursState: SectionLoaded(hours),
      );
      notifyListeners();
    } on ApiException catch (e) {
      _state = _state.copyWith(
        openingHoursState: SectionError(e.detail),
      );
      notifyListeners();
    } on NetworkException catch (e) {
      _state = _state.copyWith(
        openingHoursState: SectionError(e.message),
      );
      notifyListeners();
    } catch (_) {
      _state = _state.copyWith(
        openingHoursState: const SectionError('Não foi possível carregar os horários de funcionamento.'),
      );
      notifyListeners();
    }
  }

  /// Carrega as comodidades do estabelecimento (F2.2).
  Future<void> loadAmenities() async {
    _state = _state.copyWith(
      amenitiesState: const SectionLoading(),
    );
    notifyListeners();

    try {
      final amenities = await repository.getAmenities(placeId);
      _state = _state.copyWith(
        amenitiesState: SectionLoaded(amenities),
      );
      notifyListeners();
    } on ApiException catch (e) {
      _state = _state.copyWith(
        amenitiesState: SectionError(e.detail),
      );
      notifyListeners();
    } on NetworkException catch (e) {
      _state = _state.copyWith(
        amenitiesState: SectionError(e.message),
      );
      notifyListeners();
    } catch (_) {
      _state = _state.copyWith(
        amenitiesState: const SectionError('Não foi possível carregar as comodidades.'),
      );
      notifyListeners();
    }
  }

  /// Carrega a primeira página de ofertas comerciais oficiais (F2.3).
  Future<void> loadOffers() async {
    _state = _state.copyWith(
      offersState: const SectionLoading(),
    );
    notifyListeners();

    try {
      final pageResult = await repository.getOffers(placeId, page: 0, size: 50);
      final offersData = PlaceOffersData(
        offers: pageResult.content,
        currentPage: pageResult.pageNumber,
        pageSize: pageResult.pageSize,
        totalElements: pageResult.totalElements,
        totalPages: pageResult.totalPages,
        isLastPage: pageResult.isLast,
      );
      _state = _state.copyWith(
        offersState: SectionLoaded(offersData),
      );
      notifyListeners();
    } on ApiException catch (e) {
      _state = _state.copyWith(
        offersState: SectionError(e.detail),
      );
      notifyListeners();
    } on NetworkException catch (e) {
      _state = _state.copyWith(
        offersState: SectionError(e.message),
      );
      notifyListeners();
    } catch (_) {
      _state = _state.copyWith(
        offersState: const SectionError('Não foi possível carregar as ofertas comerciais.'),
      );
      notifyListeners();
    }
  }

  /// Carrega a próxima página de ofertas comerciais sem duplicar produtos já exibidos.
  Future<void> loadMoreOffers() async {
    final currentOffersState = _state.offersState;
    if (currentOffersState is! SectionLoaded<PlaceOffersData>) return;

    final currentData = currentOffersState.data;
    if (currentData.isLastPage || currentData.isLoadingMore) return;

    _state = _state.copyWith(
      offersState: SectionLoaded(
        currentData.copyWith(
          isLoadingMore: true,
          clearLoadMoreError: true,
        ),
      ),
    );
    notifyListeners();

    final nextPage = currentData.currentPage + 1;
    try {
      final pageResult = await repository.getOffers(placeId, page: nextPage, size: 50);

      // Evita duplicações baseado no identificador do produto
      final existingProductIds = currentData.offers.map((o) => o.product.id).toSet();
      final newOffers = pageResult.content
          .where((o) => !existingProductIds.contains(o.product.id))
          .toList();

      final updatedData = currentData.copyWith(
        offers: [...currentData.offers, ...newOffers],
        currentPage: nextPage,
        isLastPage: pageResult.isLast,
        totalElements: pageResult.totalElements,
        totalPages: pageResult.totalPages,
        isLoadingMore: false,
        clearLoadMoreError: true,
      );

      _state = _state.copyWith(
        offersState: SectionLoaded(updatedData),
      );
      notifyListeners();
    } on ApiException catch (e) {
      _state = _state.copyWith(
        offersState: SectionLoaded(
          currentData.copyWith(
            isLoadingMore: false,
            loadMoreError: e.detail,
          ),
        ),
      );
      notifyListeners();
    } on NetworkException catch (e) {
      _state = _state.copyWith(
        offersState: SectionLoaded(
          currentData.copyWith(
            isLoadingMore: false,
            loadMoreError: e.message,
          ),
        ),
      );
      notifyListeners();
    } catch (_) {
      _state = _state.copyWith(
        offersState: SectionLoaded(
          currentData.copyWith(
            isLoadingMore: false,
            loadMoreError: 'Não foi possível carregar mais ofertas.',
          ),
        ),
      );
      notifyListeners();
    }
  }

  /// Recarrega do zero as três seções comerciais.
  Future<void> refresh() async {
    await loadAll();
  }
}

