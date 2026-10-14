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

  bool _isDisposed = false;
  bool get isDisposed => _isDisposed;

  int _offersLoadGeneration = 0;

  PlaceCommercialNotifier({
    required this.repository,
    required this.placeId,
  });

  @override
  void dispose() {
    _isDisposed = true;
    super.dispose();
  }

  void _safeNotifyListeners() {
    if (!_isDisposed) {
      notifyListeners();
    }
  }

  /// Dispara o carregamento concorrente e isolado das três seções comerciais.
  Future<void> loadAll() async {
    if (_isDisposed) return;
    await Future.wait([
      loadOpeningHours(),
      loadAmenities(),
      loadOffers(),
    ]);
  }

  /// Carrega os horários de funcionamento do estabelecimento (F2.1).
  Future<void> loadOpeningHours() async {
    if (_isDisposed) return;
    _state = _state.copyWith(
      openingHoursState: const SectionLoading(),
    );
    _safeNotifyListeners();

    try {
      final hours = await repository.getOpeningHours(placeId);
      if (_isDisposed) return;
      _state = _state.copyWith(
        openingHoursState: SectionLoaded(hours),
      );
      _safeNotifyListeners();
    } on ApiException catch (e) {
      if (_isDisposed) return;
      _state = _state.copyWith(
        openingHoursState: SectionError(e.detail),
      );
      _safeNotifyListeners();
    } on NetworkException catch (e) {
      if (_isDisposed) return;
      _state = _state.copyWith(
        openingHoursState: SectionError(e.message),
      );
      _safeNotifyListeners();
    } catch (_) {
      if (_isDisposed) return;
      _state = _state.copyWith(
        openingHoursState: const SectionError('Não foi possível carregar os horários de funcionamento.'),
      );
      _safeNotifyListeners();
    }
  }

  /// Carrega as comodidades do estabelecimento (F2.2).
  Future<void> loadAmenities() async {
    if (_isDisposed) return;
    _state = _state.copyWith(
      amenitiesState: const SectionLoading(),
    );
    _safeNotifyListeners();

    try {
      final amenities = await repository.getAmenities(placeId);
      if (_isDisposed) return;
      _state = _state.copyWith(
        amenitiesState: SectionLoaded(amenities),
      );
      _safeNotifyListeners();
    } on ApiException catch (e) {
      if (_isDisposed) return;
      _state = _state.copyWith(
        amenitiesState: SectionError(e.detail),
      );
      _safeNotifyListeners();
    } on NetworkException catch (e) {
      if (_isDisposed) return;
      _state = _state.copyWith(
        amenitiesState: SectionError(e.message),
      );
      _safeNotifyListeners();
    } catch (_) {
      if (_isDisposed) return;
      _state = _state.copyWith(
        amenitiesState: const SectionError('Não foi possível carregar as comodidades.'),
      );
      _safeNotifyListeners();
    }
  }

  /// Carrega a primeira página de ofertas comerciais oficiais (F2.3).
  Future<void> loadOffers() async {
    if (_isDisposed) return;
    final generation = ++_offersLoadGeneration;
    _state = _state.copyWith(
      offersState: const SectionLoading(),
    );
    _safeNotifyListeners();

    try {
      final pageResult = await repository.getOffers(placeId, page: 0, size: 50);
      if (_isDisposed || generation != _offersLoadGeneration) return;

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
      _safeNotifyListeners();
    } on ApiException catch (e) {
      if (_isDisposed || generation != _offersLoadGeneration) return;
      _state = _state.copyWith(
        offersState: SectionError(e.detail),
      );
      _safeNotifyListeners();
    } on NetworkException catch (e) {
      if (_isDisposed || generation != _offersLoadGeneration) return;
      _state = _state.copyWith(
        offersState: SectionError(e.message),
      );
      _safeNotifyListeners();
    } catch (_) {
      if (_isDisposed || generation != _offersLoadGeneration) return;
      _state = _state.copyWith(
        offersState: const SectionError('Não foi possível carregar as ofertas comerciais.'),
      );
      _safeNotifyListeners();
    }
  }

  /// Carrega a próxima página de ofertas comerciais sem duplicar produtos já exibidos.
  Future<void> loadMoreOffers() async {
    if (_isDisposed) return;
    final currentOffersState = _state.offersState;
    if (currentOffersState is! SectionLoaded<PlaceOffersData>) return;

    final currentData = currentOffersState.data;
    if (currentData.isLastPage || currentData.isLoadingMore) return;

    final generation = _offersLoadGeneration;

    _state = _state.copyWith(
      offersState: SectionLoaded(
        currentData.copyWith(
          isLoadingMore: true,
          clearLoadMoreError: true,
        ),
      ),
    );
    _safeNotifyListeners();

    final nextPage = currentData.currentPage + 1;
    try {
      final pageResult = await repository.getOffers(placeId, page: nextPage, size: 50);
      if (_isDisposed || generation != _offersLoadGeneration) return;

      final freshState = _state.offersState;
      if (freshState is! SectionLoaded<PlaceOffersData>) return;
      final freshData = freshState.data;

      // Evita duplicações baseado no identificador do produto
      final existingProductIds = freshData.offers.map((o) => o.product.id).toSet();
      final newOffers = pageResult.content
          .where((o) => !existingProductIds.contains(o.product.id))
          .toList();

      final updatedData = freshData.copyWith(
        offers: [...freshData.offers, ...newOffers],
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
      _safeNotifyListeners();
    } on ApiException catch (e) {
      if (_isDisposed || generation != _offersLoadGeneration) return;
      final freshState = _state.offersState;
      if (freshState is! SectionLoaded<PlaceOffersData>) return;
      _state = _state.copyWith(
        offersState: SectionLoaded(
          freshState.data.copyWith(
            isLoadingMore: false,
            loadMoreError: e.detail,
          ),
        ),
      );
      _safeNotifyListeners();
    } on NetworkException catch (e) {
      if (_isDisposed || generation != _offersLoadGeneration) return;
      final freshState = _state.offersState;
      if (freshState is! SectionLoaded<PlaceOffersData>) return;
      _state = _state.copyWith(
        offersState: SectionLoaded(
          freshState.data.copyWith(
            isLoadingMore: false,
            loadMoreError: e.message,
          ),
        ),
      );
      _safeNotifyListeners();
    } catch (_) {
      if (_isDisposed || generation != _offersLoadGeneration) return;
      final freshState = _state.offersState;
      if (freshState is! SectionLoaded<PlaceOffersData>) return;
      _state = _state.copyWith(
        offersState: SectionLoaded(
          freshState.data.copyWith(
            isLoadingMore: false,
            loadMoreError: 'Não foi possível carregar mais ofertas.',
          ),
        ),
      );
      _safeNotifyListeners();
    }
  }

  /// Recarrega do zero as três seções comerciais.
  Future<void> refresh() async {
    if (_isDisposed) return;
    await loadAll();
  }
}
