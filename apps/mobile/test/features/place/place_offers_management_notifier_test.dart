import 'package:flutter_test/flutter_test.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/features/place/domain/entities/place_commercial_entities.dart';
import 'package:rewit_mobile/features/place/domain/repositories/place_commercial_repository.dart';
import 'package:rewit_mobile/features/place/presentation/state/place_offers_management_notifier.dart';
import 'package:rewit_mobile/features/search/domain/entities/search_entities.dart';
import 'package:rewit_mobile/features/search/domain/repositories/search_repository.dart';

class _FakeCommercialRepository implements PlaceCommercialRepository {
  List<PlaceProductOffer> databaseOffers = [];
  Map<int, PlaceOffersPage> pagedResponses = {};
  Duration? getOffersDelay;
  PlaceOffersPage? offersPageToReturn;
  Exception? exceptionToThrow;
  Exception? exceptionOnGetOffers;

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
  Future<PlaceOffersPage> getOffers(String placeId, {int page = 0, int size = 20}) async {
    if (getOffersDelay != null) {
      await Future.delayed(getOffersDelay!);
    }
    if (exceptionOnGetOffers != null) throw exceptionOnGetOffers!;
    if (exceptionToThrow != null) throw exceptionToThrow!;

    if (pagedResponses.containsKey(page)) {
      return pagedResponses[page]!;
    }

    if (offersPageToReturn != null) {
      return offersPageToReturn!;
    }

    if (databaseOffers.isNotEmpty) {
      final start = page * size;
      final end = (start + size < databaseOffers.length) ? start + size : databaseOffers.length;
      final content = (start < databaseOffers.length) ? databaseOffers.sublist(start, end) : <PlaceProductOffer>[];
      final total = databaseOffers.length;
      final totalP = (total / size).ceil();
      return PlaceOffersPage(
        content: content,
        pageNumber: page,
        pageSize: size,
        totalElements: total,
        totalPages: totalP,
        isLast: end >= databaseOffers.length,
      );
    }

    return const PlaceOffersPage(
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

    final offer = PlaceProductOffer(
      product: OfferedProductSummary(
        id: productId,
        name: 'Produto $productId',
        category: 'GERAL',
      ),
      availability: availability,
      price: price,
      currency: currency,
    );

    final idx = databaseOffers.indexWhere((o) => o.product.id == productId);
    if (idx != -1) {
      databaseOffers[idx] = offer;
    } else {
      databaseOffers.add(offer);
    }
    databaseOffers.sort((a, b) => a.product.name.compareTo(b.product.name));

    return offer;
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

    databaseOffers.removeWhere((o) => o.product.id == productId);
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

    test('com refresh: true permite nova busca e invalida requisição anterior em voo', () async {
      commercialRepo.getOffersDelay = const Duration(milliseconds: 50);
      commercialRepo.offersPageToReturn = const PlaceOffersPage(
        content: [
          PlaceProductOffer(
            product: OfferedProductSummary(id: 'slow', name: 'Resposta Lenta', category: 'G'),
            availability: 'AVAILABLE',
          ),
        ],
        isLast: true,
      );

      // Inicia carregamento lento
      final future1 = notifier.loadOffers('place-1');

      // Força refresh imediato
      commercialRepo.offersPageToReturn = const PlaceOffersPage(
        content: [
          PlaceProductOffer(
            product: OfferedProductSummary(id: 'fast', name: 'Resposta Atualizada', category: 'G'),
            availability: 'AVAILABLE',
          ),
        ],
        isLast: true,
      );
      commercialRepo.getOffersDelay = Duration.zero;
      final future2 = notifier.loadOffers('place-1', refresh: true);

      await Future.wait([future1, future2]);

      expect(notifier.offers.length, 1);
      expect(notifier.offers.first.product.id, 'fast');
      expect(notifier.offers.first.product.name, 'Resposta Atualizada');
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

    test('descarta resposta de loadMoreOffers se um refresh for acionado antes da conclusão', () async {
      commercialRepo.offersPageToReturn = const PlaceOffersPage(
        content: [
          PlaceProductOffer(
            product: OfferedProductSummary(id: 'p-1', name: 'Prod 1', category: 'G'),
            availability: 'AVAILABLE',
          ),
        ],
        pageNumber: 0,
        isLast: false,
      );
      await notifier.loadOffers('place-1');

      // Prepara carga de próxima página lenta
      commercialRepo.getOffersDelay = const Duration(milliseconds: 60);
      commercialRepo.offersPageToReturn = const PlaceOffersPage(
        content: [
          PlaceProductOffer(
            product: OfferedProductSummary(id: 'p-2', name: 'Prod 2 Desatualizado', category: 'G'),
            availability: 'AVAILABLE',
          ),
        ],
        pageNumber: 1,
        isLast: true,
      );

      final loadMoreFuture = notifier.loadMoreOffers('place-1');

      // Refresh dispara no meio
      commercialRepo.getOffersDelay = Duration.zero;
      commercialRepo.offersPageToReturn = const PlaceOffersPage(
        content: [
          PlaceProductOffer(
            product: OfferedProductSummary(id: 'p-fresh', name: 'Prod Atualizado', category: 'G'),
            availability: 'AVAILABLE',
          ),
        ],
        pageNumber: 0,
        isLast: true,
      );

      await notifier.loadOffers('place-1', refresh: true);
      await loadMoreFuture;

      expect(notifier.offers.length, 1);
      expect(notifier.offers.first.product.id, 'p-fresh');
    });
  });

  group('PlaceOffersManagementNotifier - upsertOffer e validação de preços', () {
    test('publica nova oferta e sincroniza a partir da primeira página com ordenação do servidor', () async {
      commercialRepo.databaseOffers = [
        const PlaceProductOffer(
          product: OfferedProductSummary(id: 'prod-b', name: 'Bolo de Cenoura', category: 'Doces'),
          availability: 'AVAILABLE',
          price: 10.0,
        ),
      ];

      await notifier.loadOffers('place-1');
      expect(notifier.offers.length, 1);

      // Publica produto 'Açaí' que deve vir antes na ordenação do servidor
      final success = await notifier.upsertOffer(
        placeId: 'place-1',
        productId: 'prod-a',
        businessAccountId: 'biz-1',
        availability: 'AVAILABLE',
        price: 15.50,
      );

      expect(success, isTrue);
      expect(notifier.offers.length, 2);
      expect(notifier.totalElements, 2);
      expect(notifier.currentPage, 0);
      expect(notifier.successMessage, 'Oferta publicada com sucesso.');
      expect(commercialRepo.lastUpsertPrice, 15.50);
    });

    test('atualiza oferta existente e sincroniza da primeira página', () async {
      commercialRepo.databaseOffers = [
        const PlaceProductOffer(
          product: OfferedProductSummary(id: 'prod-1', name: 'Café', category: 'Bebidas'),
          availability: 'AVAILABLE',
          price: 5.0,
        ),
      ];
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
      commercialRepo.databaseOffers = [];
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

    test('valida preço com mais de duas casas decimais e rejeita antes da chamada remota', () async {
      final success = await notifier.upsertOffer(
        placeId: 'place-1',
        productId: 'prod-decimals',
        businessAccountId: 'biz-1',
        availability: 'AVAILABLE',
        price: 12.999,
      );

      expect(success, isFalse);
      expect(notifier.errorMessage, 'O preço não pode ter mais de duas casas decimais.');
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
    test('remove oferta da lista e sincroniza com a API real reiniciando paginação', () async {
      commercialRepo.databaseOffers = [
        const PlaceProductOffer(
          product: OfferedProductSummary(id: 'prod-del', name: 'Item a remover', category: 'Geral'),
          availability: 'AVAILABLE',
          price: 12.0,
        ),
      ];
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
      expect(notifier.currentPage, 0);
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

  group('PlaceOffersManagementNotifier - Cenários de Paginação e Concorrência', () {
    test('excluir oferta da primeira página e carregar página seguinte sem perder registros', () async {
      // 3 ofertas cadastradas: P1, P2 na página 0 (tamanho 2), P3 na página 1
      commercialRepo.databaseOffers = [
        const PlaceProductOffer(
          product: OfferedProductSummary(id: 'p-1', name: 'Produto 1', category: 'G'),
          availability: 'AVAILABLE',
        ),
        const PlaceProductOffer(
          product: OfferedProductSummary(id: 'p-2', name: 'Produto 2', category: 'G'),
          availability: 'AVAILABLE',
        ),
        const PlaceProductOffer(
          product: OfferedProductSummary(id: 'p-3', name: 'Produto 3', category: 'G'),
          availability: 'AVAILABLE',
        ),
      ];

      // Carrega página 0 com size=2
      commercialRepo.pagedResponses[0] = PlaceOffersPage(
        content: [commercialRepo.databaseOffers[0], commercialRepo.databaseOffers[1]],
        pageNumber: 0,
        pageSize: 2,
        totalElements: 3,
        totalPages: 2,
        isLast: false,
      );
      commercialRepo.pagedResponses[1] = PlaceOffersPage(
        content: [commercialRepo.databaseOffers[2]],
        pageNumber: 1,
        pageSize: 2,
        totalElements: 3,
        totalPages: 2,
        isLast: true,
      );

      await notifier.loadOffers('place-1');
      expect(notifier.offers.length, 2);

      // Remove p-1: após delete, getOffers recarrega página 0 refletindo o banco real
      commercialRepo.pagedResponses.clear();
      commercialRepo.offersPageToReturn = null;
      // Banco agora tem P2 e P3
      commercialRepo.pagedResponses[0] = PlaceOffersPage(
        content: [commercialRepo.databaseOffers[1], commercialRepo.databaseOffers[2]],
        pageNumber: 0,
        pageSize: 2,
        totalElements: 2,
        totalPages: 1,
        isLast: true,
      );

      final delSuccess = await notifier.deleteOffer(
        placeId: 'place-1',
        productId: 'p-1',
        businessAccountId: 'biz-1',
      );
      expect(delSuccess, isTrue);

      // Agora a página 0 contém P2 e P3, nenhum registro foi omitido ou corrompido
      expect(notifier.offers.length, 2);
      expect(notifier.offers.map((o) => o.product.id), containsAll(['p-2', 'p-3']));
      expect(notifier.currentPage, 0);
      expect(notifier.isLastPage, isTrue);
    });

    test('publicar nova oferta e depois carregar páginas adicionais sem perder ou duplicar registros', () async {
      // Começa com P2 na página 0, P3 na página 1
      commercialRepo.databaseOffers = [
        const PlaceProductOffer(
          product: OfferedProductSummary(id: 'p-2', name: 'Bolo', category: 'G'),
          availability: 'AVAILABLE',
        ),
        const PlaceProductOffer(
          product: OfferedProductSummary(id: 'p-3', name: 'Café', category: 'G'),
          availability: 'AVAILABLE',
        ),
      ];

      commercialRepo.pagedResponses[0] = PlaceOffersPage(
        content: [commercialRepo.databaseOffers[0]],
        pageNumber: 0,
        pageSize: 1,
        totalElements: 2,
        totalPages: 2,
        isLast: false,
      );

      await notifier.loadOffers('place-1');
      expect(notifier.offers.length, 1);
      expect(notifier.offers.first.product.id, 'p-2');

      // Publica 'Açaí' (p-1). O backend insere e reordena: P1, P2, P3
      // A sincronização pós-upsert recarrega a página 0
      commercialRepo.pagedResponses[0] = const PlaceOffersPage(
        content: [
          PlaceProductOffer(
            product: OfferedProductSummary(id: 'p-1', name: 'Açaí', category: 'G'),
            availability: 'AVAILABLE',
            price: 12.0,
          ),
        ],
        pageNumber: 0,
        pageSize: 1,
        totalElements: 3,
        totalPages: 3,
        isLast: false,
      );

      final upSuccess = await notifier.upsertOffer(
        placeId: 'place-1',
        productId: 'p-1',
        businessAccountId: 'biz-1',
        availability: 'AVAILABLE',
        price: 12.0,
      );
      expect(upSuccess, isTrue);
      expect(notifier.offers.length, 1);
      expect(notifier.offers.first.product.name, 'Açaí');
      expect(notifier.currentPage, 0);

      // Agora carrega a página seguinte (página 1: Bolo)
      commercialRepo.pagedResponses[1] = const PlaceOffersPage(
        content: [
          PlaceProductOffer(
            product: OfferedProductSummary(id: 'p-2', name: 'Bolo', category: 'G'),
            availability: 'AVAILABLE',
          ),
        ],
        pageNumber: 1,
        pageSize: 1,
        totalElements: 3,
        totalPages: 3,
        isLast: false,
      );

      await notifier.loadMoreOffers('place-1');
      expect(notifier.offers.length, 2);
      expect(notifier.offers[0].product.name, 'Açaí');
      expect(notifier.offers[1].product.name, 'Bolo');
      expect(notifier.currentPage, 1);

      // Carrega página 2 (Café)
      commercialRepo.pagedResponses[2] = const PlaceOffersPage(
        content: [
          PlaceProductOffer(
            product: OfferedProductSummary(id: 'p-3', name: 'Café', category: 'G'),
            availability: 'AVAILABLE',
          ),
        ],
        pageNumber: 2,
        pageSize: 1,
        totalElements: 3,
        totalPages: 3,
        isLast: true,
      );

      await notifier.loadMoreOffers('place-1');
      expect(notifier.offers.length, 3);
      expect(notifier.offers.map((o) => o.product.id), ['p-1', 'p-2', 'p-3']);
      expect(notifier.isLastPage, isTrue);
    });
  });

  group('PlaceOffersManagementNotifier - searchProducts e invalidação', () {
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

    test('descarta respostas fora de ordem de buscas mais antigas ao editar termo rapidamente', () async {
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

      // Inicia busca 2 antes da 1 terminar
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

    test('limpa resultados imediatamente ao iniciar nova busca para não exibir itens obsoletos', () async {
      // Primeira busca concluída
      searchRepo.pageToReturn = const SearchPage(
        items: [
          SearchResultItem(
            id: 'p-1',
            name: 'Produto Antigo',
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
      await notifier.searchProducts('Antigo');
      expect(notifier.searchResults.length, 1);

      // Inicia segunda busca lenta
      searchRepo.artificialDelay = const Duration(milliseconds: 60);
      final future = notifier.searchProducts('Novo');

      // Imediatamente após iniciar, searchResults deve estar vazio
      expect(notifier.searchResults, isEmpty);
      expect(notifier.isSearching, isTrue);

      await future;
    });

    test('clearSearch invalida requisição em trânsito e impede que resposta atrasada restaure resultados', () async {
      searchRepo.artificialDelay = const Duration(milliseconds: 50);
      searchRepo.pageToReturn = const SearchPage(
        items: [
          SearchResultItem(
            id: 'p-slow',
            name: 'Resposta em trânsito',
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

      final searchFuture = notifier.searchProducts('Termo');
      expect(notifier.isSearching, isTrue);

      // Usuário limpa a busca ou fecha o modal enquanto a busca está em andamento
      notifier.clearSearch();
      expect(notifier.isSearching, isFalse);
      expect(notifier.searchResults, isEmpty);

      // Aguarda a resposta atrasada do repositório
      await searchFuture;

      // Confirma que a resposta atrasada foi sumariamente descartada
      expect(notifier.searchResults, isEmpty);
      expect(notifier.isSearching, isFalse);
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
