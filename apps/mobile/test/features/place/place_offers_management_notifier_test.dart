import 'package:flutter_test/flutter_test.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/features/place/domain/entities/place_commercial_entities.dart';
import 'package:rewit_mobile/features/place/domain/repositories/place_commercial_repository.dart';
import 'package:rewit_mobile/features/place/presentation/state/place_offers_management_notifier.dart';
import 'package:rewit_mobile/features/search/domain/entities/search_entities.dart';
import 'package:rewit_mobile/features/search/domain/repositories/search_repository.dart';

class _FakeCommercialRepository implements PlaceCommercialRepository {
  PlaceOffersPage? offersPageToReturn;
  PlaceProductOffer? offerToReturnOnUpsert;
  Exception? exceptionToThrow;

  String? lastUpsertPlaceId;
  String? lastUpsertProductId;
  String? lastUpsertBusinessAccountId;
  String? lastUpsertAvailability;
  double? lastUpsertPrice;
  String? lastUpsertCurrency;

  String? lastDeletePlaceId;
  String? lastDeleteProductId;
  String? lastDeleteBusinessAccountId;

  @override
  Future<PlaceOffersPage> getOffers(String placeId, {int page = 0, int size = 50}) async {
    if (exceptionToThrow != null) throw exceptionToThrow!;
    return offersPageToReturn ??
        const PlaceOffersPage(
          content: [],
          pageNumber: 0,
          pageSize: 20,
          totalElements: 0,
          totalPages: 0,
          isLast: true,
        );
  }

  @override
  Future<PlaceProductOffer> upsertOffer({
    required String placeId,
    required String productId,
    required String businessAccountId,
    required String availability,
    double? price,
    String currency = 'BRL',
  }) async {
    lastUpsertPlaceId = placeId;
    lastUpsertProductId = productId;
    lastUpsertBusinessAccountId = businessAccountId;
    lastUpsertAvailability = availability;
    lastUpsertPrice = price;
    lastUpsertCurrency = currency;

    if (exceptionToThrow != null) throw exceptionToThrow!;

    return offerToReturnOnUpsert ??
        PlaceProductOffer(
          product: OfferedProductSummary(
            id: productId,
            name: 'Produto $productId',
            category: 'GERAL',
          ),
          availability: availability,
          price: price,
          currency: currency,
        );
  }

  @override
  Future<void> deleteOffer({
    required String placeId,
    required String productId,
    required String businessAccountId,
  }) async {
    lastDeletePlaceId = placeId;
    lastDeleteProductId = productId;
    lastDeleteBusinessAccountId = businessAccountId;

    if (exceptionToThrow != null) throw exceptionToThrow!;
  }

  @override
  Future<List<PlaceAmenityItem>> getAmenityOptions() async => throw UnimplementedError();

  @override
  Future<PlaceAmenities> getAmenities(String placeId) async => throw UnimplementedError();

  @override
  Future<PlaceAmenities> updateAmenities({
    required String placeId,
    required String businessAccountId,
    required List<String> amenityCodes,
  }) async => throw UnimplementedError();

  @override
  Future<PlaceOpeningHours> getOpeningHours(String placeId) async => throw UnimplementedError();

  @override
  Future<PlaceOpeningHours> updateOpeningHours({
    required String placeId,
    required String businessAccountId,
    required List<DaySchedule> days,
  }) async => throw UnimplementedError();
}

class _FakeSearchRepository implements SearchRepository {
  SearchPage? pageToReturn;
  Exception? exceptionToThrow;
  Duration? artificialDelay;

  String? lastQuery;
  int? lastPage;
  int? lastSize;

  @override
  Future<SearchPage> search({
    required String query,
    int page = 0,
    int size = 20,
  }) async {
    lastQuery = query;
    lastPage = page;
    lastSize = size;

    if (artificialDelay != null) {
      await Future.delayed(artificialDelay!);
    }

    if (exceptionToThrow != null) throw exceptionToThrow!;

    return pageToReturn ??
        const SearchPage(
          items: [],
          pageNumber: 0,
          pageSize: 20,
          totalElements: 0,
          totalPages: 0,
          isLast: true,
        );
  }
}

void main() {
  late _FakeCommercialRepository commercialRepo;
  late _FakeSearchRepository searchRepo;
  late PlaceOffersManagementNotifier notifier;

  setUp(() {
    commercialRepo = _FakeCommercialRepository();
    searchRepo = _FakeSearchRepository();
    notifier = PlaceOffersManagementNotifier(
      repository: commercialRepo,
      searchRepository: searchRepo,
    );
  });

  tearDown(() {
    notifier.dispose();
  });

  group('PlaceOffersManagementNotifier - loadOffers', () {
    test('carrega primeira página de ofertas com sucesso', () async {
      const sampleOffer = PlaceProductOffer(
        product: OfferedProductSummary(
          id: 'prod-1',
          name: 'Café Espresso',
          category: 'Bebidas',
        ),
        availability: 'AVAILABLE',
        price: 8.50,
      );

      commercialRepo.offersPageToReturn = const PlaceOffersPage(
        content: [sampleOffer],
        pageNumber: 0,
        pageSize: 20,
        totalElements: 1,
        totalPages: 1,
        isLast: true,
      );

      await notifier.loadOffers('place-1');

      expect(notifier.isLoading, isFalse);
      expect(notifier.offers.length, 1);
      expect(notifier.offers.first.product.name, 'Café Espresso');
      expect(notifier.totalElements, 1);
      expect(notifier.currentPage, 0);
      expect(notifier.isLastPage, isTrue);
      expect(notifier.errorMessage, isNull);
    });

    test('trata ApiException adequadamente', () async {
      commercialRepo.exceptionToThrow = const ApiException(
        ProblemDetail(type: 'about:blank', status: 403, title: 'Erro', detail: 'Conta comercial não autorizada.'),
      );

      await notifier.loadOffers('place-1');

      expect(notifier.isLoading, isFalse);
      expect(notifier.offers, isEmpty);
      expect(notifier.errorMessage, 'Conta comercial não autorizada.');
    });

    test('trata exceção genérica com mensagem padrão', () async {
      commercialRepo.exceptionToThrow = Exception('Network error');

      await notifier.loadOffers('place-1');

      expect(notifier.isLoading, isFalse);
      expect(notifier.offers, isEmpty);
      expect(notifier.errorMessage, contains('Falha ao carregar ofertas comerciais'));
    });
  });

  group('PlaceOffersManagementNotifier - loadMoreOffers', () {
    test('carrega próxima página de ofertas e deduplica por produto', () async {
      const initialOffer = PlaceProductOffer(
        product: OfferedProductSummary(id: 'prod-1', name: 'Pão de Queijo', category: 'Lanches'),
        availability: 'AVAILABLE',
        price: 5.0,
      );
      commercialRepo.offersPageToReturn = const PlaceOffersPage(
        content: [initialOffer],
        pageNumber: 0,
        pageSize: 1,
        totalElements: 2,
        totalPages: 2,
        isLast: false,
      );

      await notifier.loadOffers('place-1');
      expect(notifier.offers.length, 1);

      const nextOffer1 = PlaceProductOffer(
        product: OfferedProductSummary(id: 'prod-1', name: 'Pão de Queijo Especial', category: 'Lanches'),
        availability: 'AVAILABLE',
        price: 6.0,
      );
      const nextOffer2 = PlaceProductOffer(
        product: OfferedProductSummary(id: 'prod-2', name: 'Suco Natural', category: 'Bebidas'),
        availability: 'AVAILABLE',
        price: 9.0,
      );

      commercialRepo.offersPageToReturn = const PlaceOffersPage(
        content: [nextOffer1, nextOffer2],
        pageNumber: 1,
        pageSize: 2,
        totalElements: 2,
        totalPages: 2,
        isLast: true,
      );

      await notifier.loadMoreOffers('place-1');

      expect(notifier.offers.length, 2);
      expect(notifier.offers[0].product.name, 'Pão de Queijo Especial');
      expect(notifier.offers[0].price, 6.0);
      expect(notifier.offers[1].product.name, 'Suco Natural');
      expect(notifier.currentPage, 1);
      expect(notifier.isLastPage, isTrue);
    });

    test('ignora chamada se já for última página', () async {
      commercialRepo.offersPageToReturn = const PlaceOffersPage(
        content: [],
        pageNumber: 0,
        totalElements: 0,
        isLast: true,
      );

      await notifier.loadOffers('place-1');
      await notifier.loadMoreOffers('place-1');

      expect(notifier.isLoadingMore, isFalse);
    });
  });

  group('PlaceOffersManagementNotifier - upsertOffer', () {
    test('publica nova oferta e insere no topo da lista', () async {
      commercialRepo.offersPageToReturn = const PlaceOffersPage(
        content: [],
        pageNumber: 0,
        totalElements: 0,
        isLast: true,
      );
      await notifier.loadOffers('place-1');

      final success = await notifier.upsertOffer(
        placeId: 'place-1',
        productId: 'prod-new',
        businessAccountId: 'biz-1',
        availability: 'AVAILABLE',
        price: 15.50,
      );

      expect(success, isTrue);
      expect(notifier.offers.length, 1);
      expect(notifier.offers.first.product.id, 'prod-new');
      expect(notifier.offers.first.price, 15.50);
      expect(notifier.totalElements, 1);
      expect(notifier.successMessage, 'Oferta publicada com sucesso.');
      expect(commercialRepo.lastUpsertPrice, 15.50);
    });

    test('atualiza oferta existente na mesma posição', () async {
      const initialOffer = PlaceProductOffer(
        product: OfferedProductSummary(id: 'prod-1', name: 'Café', category: 'Bebidas'),
        availability: 'AVAILABLE',
        price: 5.0,
      );
      commercialRepo.offersPageToReturn = const PlaceOffersPage(
        content: [initialOffer],
        pageNumber: 0,
        totalElements: 1,
        isLast: true,
      );
      await notifier.loadOffers('place-1');

      final success = await notifier.upsertOffer(
        placeId: 'place-1',
        productId: 'prod-1',
        businessAccountId: 'biz-1',
        availability: 'OUT_OF_STOCK',
        price: 6.50,
      );

      expect(success, isTrue);
      expect(notifier.offers.length, 1);
      expect(notifier.offers.first.availability, 'OUT_OF_STOCK');
      expect(notifier.offers.first.price, 6.50);
      expect(notifier.successMessage, 'Oferta atualizada com sucesso.');
    });

    test('permite preço gratuito (0.0) e preço não informado (null)', () async {
      commercialRepo.offersPageToReturn = const PlaceOffersPage(content: [], isLast: true);
      await notifier.loadOffers('place-1');

      // Gratuito
      final successFree = await notifier.upsertOffer(
        placeId: 'place-1',
        productId: 'prod-free',
        businessAccountId: 'biz-1',
        availability: 'AVAILABLE',
        price: 0.0,
      );
      expect(successFree, isTrue);
      expect(commercialRepo.lastUpsertPrice, 0.0);

      // Preço não informado
      final successNull = await notifier.upsertOffer(
        placeId: 'place-1',
        productId: 'prod-undisclosed',
        businessAccountId: 'biz-1',
        availability: 'AVAILABLE',
        price: null,
      );
      expect(successNull, isTrue);
      expect(commercialRepo.lastUpsertPrice, isNull);
    });

    test('valida preço negativo e rejeita antes da chamada remota', () async {
      final success = await notifier.upsertOffer(
        placeId: 'place-1',
        productId: 'prod-neg',
        businessAccountId: 'biz-1',
        availability: 'AVAILABLE',
        price: -5.0,
      );

      expect(success, isFalse);
      expect(notifier.errorMessage, 'O preço não pode ser negativo.');
      expect(commercialRepo.lastUpsertProductId, isNull);
    });

    test('valida preço acima do limite e rejeita antes da chamada remota', () async {
      final success = await notifier.upsertOffer(
        placeId: 'place-1',
        productId: 'prod-high',
        businessAccountId: 'biz-1',
        availability: 'AVAILABLE',
        price: 10000000000.0,
      );

      expect(success, isFalse);
      expect(notifier.errorMessage, 'O preço excede o limite máximo permitido.');
      expect(commercialRepo.lastUpsertProductId, isNull);
    });

    test('trata ApiException no upsert', () async {
      commercialRepo.exceptionToThrow = const ApiException(
        ProblemDetail(type: 'about:blank', status: 422, title: 'Erro', detail: 'Produto inativo no catálogo.'),
      );

      final success = await notifier.upsertOffer(
        placeId: 'place-1',
        productId: 'prod-inactive',
        businessAccountId: 'biz-1',
        availability: 'AVAILABLE',
        price: 10.0,
      );

      expect(success, isFalse);
      expect(notifier.errorMessage, 'Produto inativo no catálogo.');
    });
  });

  group('PlaceOffersManagementNotifier - deleteOffer', () {
    test('remove oferta da lista e decrementa contagem', () async {
      const offer = PlaceProductOffer(
        product: OfferedProductSummary(id: 'prod-del', name: 'Item a remover', category: 'Geral'),
        availability: 'AVAILABLE',
        price: 12.0,
      );
      commercialRepo.offersPageToReturn = const PlaceOffersPage(
        content: [offer],
        totalElements: 1,
        isLast: true,
      );
      await notifier.loadOffers('place-1');
      expect(notifier.offers.length, 1);

      final success = await notifier.deleteOffer(
        placeId: 'place-1',
        productId: 'prod-del',
        businessAccountId: 'biz-1',
      );

      expect(success, isTrue);
      expect(notifier.offers, isEmpty);
      expect(notifier.totalElements, 0);
      expect(notifier.successMessage, 'Oferta retirada da vitrine.');
      expect(commercialRepo.lastDeleteProductId, 'prod-del');
      expect(commercialRepo.lastDeleteBusinessAccountId, 'biz-1');
    });

    test('trata ApiException no delete', () async {
      commercialRepo.exceptionToThrow = const ApiException(
        ProblemDetail(type: 'about:blank', status: 403, title: 'Erro', detail: 'Acesso negado para remover oferta.'),
      );

      final success = await notifier.deleteOffer(
        placeId: 'place-1',
        productId: 'prod-1',
        businessAccountId: 'biz-1',
      );

      expect(success, isFalse);
      expect(notifier.errorMessage, 'Acesso negado para remover oferta.');
    });
  });

  group('PlaceOffersManagementNotifier - searchProducts', () {
    test('filtra estritamente alvos do tipo PRODUCT e status ACTIVE', () async {
      searchRepo.pageToReturn = const SearchPage(
        items: [
          SearchResultItem(
            id: 'p-1',
            name: 'Café Grão 1kg',
            category: 'Grãos',
            targetType: TargetType.product,
            rawTargetType: 'PRODUCT',
            status: 'ACTIVE',
          ),
          SearchResultItem(
            id: 'place-ignored',
            name: 'Cafeteria do Centro',
            category: 'Cafeteria',
            targetType: TargetType.place,
            rawTargetType: 'PLACE',
            status: 'ACTIVE',
          ),
          SearchResultItem(
            id: 'p-inactive',
            name: 'Café Descontinuado',
            category: 'Grãos',
            targetType: TargetType.product,
            rawTargetType: 'PRODUCT',
            status: 'INACTIVE',
          ),
        ],
        pageNumber: 0,
        pageSize: 20,
        totalElements: 3,
        totalPages: 1,
        isLast: true,
      );

      await notifier.searchProducts('Café');

      expect(notifier.isSearching, isFalse);
      expect(notifier.searchResults.length, 1);
      expect(notifier.searchResults.first.id, 'p-1');
      expect(notifier.searchResults.first.name, 'Café Grão 1kg');
    });

    test('limpa resultados se busca tiver menos de 2 caracteres', () async {
      await notifier.searchProducts('a');

      expect(notifier.isSearching, isFalse);
      expect(notifier.searchResults, isEmpty);
      expect(notifier.searchErrorMessage, isNull);
    });

    test('informa erro se searchRepository não estiver disponível', () async {
      final notifierWithoutSearch = PlaceOffersManagementNotifier(
        repository: commercialRepo,
        searchRepository: null,
      );

      await notifierWithoutSearch.searchProducts('Café');

      expect(notifierWithoutSearch.searchErrorMessage, 'Mecanismo de busca de catálogo indisponível.');
      notifierWithoutSearch.dispose();
    });

    test('descarta respostas fora de ordem de buscas mais antigas', () async {
      searchRepo.artificialDelay = const Duration(milliseconds: 50);
      searchRepo.pageToReturn = const SearchPage(
        items: [
          SearchResultItem(
            id: 'p-old',
            name: 'Resposta Antiga',
            targetType: TargetType.product,
            rawTargetType: 'PRODUCT',
            status: 'ACTIVE',
          ),
        ],
        pageNumber: 0,
        pageSize: 20,
        totalElements: 1,
        totalPages: 1,
        isLast: true,
      );

      // Inicia busca 1
      final future1 = notifier.searchProducts('Primeira');

      // Altera retorno e inicia busca 2 imediatamente
      searchRepo.pageToReturn = const SearchPage(
        items: [
          SearchResultItem(
            id: 'p-new',
            name: 'Resposta Nova',
            targetType: TargetType.product,
            rawTargetType: 'PRODUCT',
            status: 'ACTIVE',
          ),
        ],
        pageNumber: 0,
        pageSize: 20,
        totalElements: 1,
        totalPages: 1,
        isLast: true,
      );
      searchRepo.artificialDelay = Duration.zero;
      final future2 = notifier.searchProducts('Segunda');

      await Future.wait([future1, future2]);

      expect(notifier.searchResults.length, 1);
      expect(notifier.searchResults.first.name, 'Resposta Nova');
    });
  });

  group('PlaceOffersManagementNotifier - utilitários', () {
    test('findExistingOffer localiza produto ou retorna null', () async {
      commercialRepo.offersPageToReturn = const PlaceOffersPage(
        content: [
          PlaceProductOffer(
            product: OfferedProductSummary(id: 'prod-target', name: 'Item Alvo', category: 'Geral'),
            availability: 'AVAILABLE',
            price: 20.0,
          ),
        ],
        isLast: true,
      );
      await notifier.loadOffers('place-1');

      final found = notifier.findExistingOffer('prod-target');
      final notFound = notifier.findExistingOffer('prod-inexistente');

      expect(found, isNotNull);
      expect(found!.product.name, 'Item Alvo');
      expect(notFound, isNull);
    });

    test('clearSearch e clearMessages limpam estados adequadamente', () {
      notifier.clearSearch();
      expect(notifier.searchQuery, isEmpty);
      expect(notifier.searchResults, isEmpty);

      notifier.clearMessages();
      expect(notifier.errorMessage, isNull);
      expect(notifier.successMessage, isNull);
    });
  });
}
