import 'dart:async';
import 'package:flutter_test/flutter_test.dart';
import 'package:rewit_mobile/features/place/domain/entities/place_commercial_entities.dart';
import 'package:rewit_mobile/features/place/domain/repositories/place_commercial_repository.dart';
import 'package:rewit_mobile/features/place/presentation/state/place_commercial_notifier.dart';
import 'package:rewit_mobile/features/place/presentation/state/place_commercial_state.dart';

import 'package:rewit_mobile/features/place/domain/entities/place_detail.dart';
class _FakeCommercialRepository implements PlaceCommercialRepository {
  PlaceOpeningHours? openingHoursToReturn;
  PlaceAmenities? amenitiesToReturn;
  PlaceOffersPage? offersPageToReturn;

  Exception? openingHoursException;
  Exception? amenitiesException;
  Exception? offersException;

  Future<PlaceOpeningHours> Function(String placeId)? onGetOpeningHours;
  Future<PlaceAmenities> Function(String placeId)? onGetAmenities;
  Future<PlaceOffersPage> Function(String placeId, int page, int size)? onGetOffers;

  int requestedOffersPage = -1;

  @override
  Future<PlaceOpeningHours> getOpeningHours(String placeId) async {
    if (onGetOpeningHours != null) return onGetOpeningHours!(placeId);
    if (openingHoursException != null) throw openingHoursException!;
    return openingHoursToReturn ??
        const PlaceOpeningHours(
          placeId: 'p-1',
          configured: true,
          days: [
            DaySchedule(
              dayOfWeek: 1,
              dayName: 'MONDAY',
              closed: false,
              intervals: [TimeInterval(openTime: '08:00', closeTime: '18:00')],
            ),
          ],
        );
  }

  @override
  Future<PlaceAmenities> getAmenities(String placeId) async {
    if (onGetAmenities != null) return onGetAmenities!(placeId);
    if (amenitiesException != null) throw amenitiesException!;
    return amenitiesToReturn ??
        const PlaceAmenities(
          placeId: 'p-1',
          configured: true,
          amenities: [PlaceAmenityItem(code: 'WIFI', label: 'Wi-Fi')],
        );
  }

  @override
  Future<PlaceOffersPage> getOffers(String placeId, {int page = 0, int size = 50}) async {
    requestedOffersPage = page;
    if (onGetOffers != null) return onGetOffers!(placeId, page, size);
    if (offersException != null) throw offersException!;
    return offersPageToReturn ??
        PlaceOffersPage(
          content: const [
            PlaceProductOffer(
              product: OfferedProductSummary(
                id: 'prod-1',
                name: 'Café',
                category: 'BEBIDA',
              ),
              availability: 'AVAILABLE',
              price: 5.0,
            ),
          ],
          pageNumber: page,
          pageSize: size,
          totalElements: 1,
          totalPages: 1,
          isLast: true,
        );
  }

  @override
  Future<PlaceOpeningHours> updateOpeningHours({
    required String placeId,
    required String businessAccountId,
    required List<DaySchedule> days,
  }) async {
    return PlaceOpeningHours(placeId: placeId, configured: true, days: days);
  }

  @override
  Future<List<PlaceAmenityItem>> getAmenityOptions() async {
    return const [
      PlaceAmenityItem(code: 'WIFI', label: 'Wi-Fi para clientes'),
      PlaceAmenityItem(code: 'PARKING', label: 'Estacionamento gratuito'),
    ];
  }

  @override
  Future<PlaceAmenities> updateAmenities({
    required String placeId,
    required String businessAccountId,
    required List<String> amenityCodes,
  }) async {
    return PlaceAmenities(
      placeId: placeId,
      configured: true,
      amenities: amenityCodes.map((c) => PlaceAmenityItem(code: c, label: c)).toList(),
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
    return PlaceProductOffer(
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
  }) async {}


  @override
  Future<PlaceDetail> getBusinessProfile(String placeId) async =>
      throw UnimplementedError();

  @override
  Future<PlaceDetail> updateBusinessProfile({
    required String placeId,
    required String businessAccountId,
    String? description,
    String? phone,
    String? websiteUrl,
    String? instagramUrl,
    String? facebookUrl,
  }) async =>
      throw UnimplementedError();
}

void main() {
  group('PlaceCommercialNotifier Tests', () {
    late _FakeCommercialRepository repository;
    late PlaceCommercialNotifier notifier;

    setUp(() {
      repository = _FakeCommercialRepository();
      notifier = PlaceCommercialNotifier(repository: repository, placeId: 'place-test');
    });

    tearDown(() {
      if (!notifier.isDisposed) {
        notifier.dispose();
      }
    });

    test('loadAll carrega as três seções com sucesso', () async {
      await notifier.loadAll();

      expect(notifier.state.openingHoursState, isA<SectionLoaded<PlaceOpeningHours>>());
      expect(notifier.state.amenitiesState, isA<SectionLoaded<PlaceAmenities>>());
      expect(notifier.state.offersState, isA<SectionLoaded<PlaceOffersData>>());

      final hours = (notifier.state.openingHoursState as SectionLoaded<PlaceOpeningHours>).data;
      expect(hours.configured, isTrue);

      final amenities = (notifier.state.amenitiesState as SectionLoaded<PlaceAmenities>).data;
      expect(amenities.amenities.length, 1);

      final offersData = (notifier.state.offersState as SectionLoaded<PlaceOffersData>).data;
      expect(offersData.offers.length, 1);
    });

    test('falha isolada em horários não impede comodidades e ofertas de carregarem', () async {
      repository.openingHoursException = Exception('Erro 500 ao buscar horários');

      await notifier.loadAll();

      expect(notifier.state.openingHoursState, isA<SectionError<PlaceOpeningHours>>());
      expect(notifier.state.amenitiesState, isA<SectionLoaded<PlaceAmenities>>());
      expect(notifier.state.offersState, isA<SectionLoaded<PlaceOffersData>>());
    });

    test('falha isolada em comodidades não afeta horários nem ofertas', () async {
      repository.amenitiesException = Exception('Falha de rede em comodidades');

      await notifier.loadAll();

      expect(notifier.state.openingHoursState, isA<SectionLoaded<PlaceOpeningHours>>());
      expect(notifier.state.amenitiesState, isA<SectionError<PlaceAmenities>>());
      expect(notifier.state.offersState, isA<SectionLoaded<PlaceOffersData>>());
    });

    test('paginação de ofertas carrega mais itens e deduplica por produto', () async {
      repository.offersPageToReturn = const PlaceOffersPage(
        content: [
          PlaceProductOffer(
            product: OfferedProductSummary(
              id: 'prod-1',
              name: 'Café',
              category: 'BEBIDA',
            ),
            availability: 'AVAILABLE',
            price: 5.0,
          ),
        ],
        pageNumber: 0,
        pageSize: 1,
        totalElements: 2,
        totalPages: 2,
        isLast: false,
      );

      await notifier.loadOffers();
      var data = (notifier.state.offersState as SectionLoaded<PlaceOffersData>).data;
      expect(data.offers.length, 1);
      expect(data.isLastPage, isFalse);

      // Próxima página com um item repetido e um item novo
      repository.offersPageToReturn = const PlaceOffersPage(
        content: [
          PlaceProductOffer(
            product: OfferedProductSummary(
              id: 'prod-1', // Repetido
              name: 'Café',
              category: 'BEBIDA',
            ),
            availability: 'AVAILABLE',
            price: 5.0,
          ),
          PlaceProductOffer(
            product: OfferedProductSummary(
              id: 'prod-2', // Novo
              name: 'Bolo',
              category: 'PADARIA',
            ),
            availability: 'AVAILABLE',
            price: 12.0,
          ),
        ],
        pageNumber: 1,
        pageSize: 2,
        totalElements: 2,
        totalPages: 2,
        isLast: true,
      );

      await notifier.loadMoreOffers();
      data = (notifier.state.offersState as SectionLoaded<PlaceOffersData>).data;

      expect(data.offers.length, 2, reason: 'prod-1 repetido deve ser descartado pela deduplicação');
      expect(data.offers[1].product.id, 'prod-2');
      expect(data.isLastPage, isTrue);
      expect(repository.requestedOffersPage, 1);
    });

    test('loadMoreOffers trata erro sem perder ofertas já carregadas', () async {
      repository.offersPageToReturn = const PlaceOffersPage(
        content: [
          PlaceProductOffer(
            product: OfferedProductSummary(
              id: 'prod-1',
              name: 'Café',
              category: 'BEBIDA',
            ),
            availability: 'AVAILABLE',
            price: 5.0,
          ),
        ],
        pageNumber: 0,
        pageSize: 1,
        totalElements: 2,
        totalPages: 2,
        isLast: false,
      );

      await notifier.loadOffers();

      repository.offersException = Exception('Timeout na paginação');
      await notifier.loadMoreOffers();

      final data = (notifier.state.offersState as SectionLoaded<PlaceOffersData>).data;
      expect(data.offers.length, 1, reason: 'ofertas da página 0 continuam preservadas');
      expect(data.loadMoreError, isNotNull);
      expect(data.isLoadingMore, isFalse);
    });

    test('refresh dispara novo carregamento de todas as seções', () async {
      await notifier.refresh();
      expect(notifier.state.openingHoursState, isA<SectionLoaded<PlaceOpeningHours>>());
      expect(notifier.state.amenitiesState, isA<SectionLoaded<PlaceAmenities>>());
      expect(notifier.state.offersState, isA<SectionLoaded<PlaceOffersData>>());
    });

    test('C1: resposta atrasada de loadMoreOffers é descartada após recarga completa e não sobrescreve estado', () async {
      const offerA = PlaceProductOffer(
        product: OfferedProductSummary(
          id: 'prod-A',
          name: 'Produto A',
          category: 'BEBIDA',
        ),
        availability: 'AVAILABLE',
        price: 10.0,
      );

      const offerB = PlaceProductOffer(
        product: OfferedProductSummary(
          id: 'prod-B',
          name: 'Produto B',
          category: 'COMIDA',
        ),
        availability: 'AVAILABLE',
        price: 25.0,
      );

      // 1. A lista inicial contém a oferta A
      repository.offersPageToReturn = const PlaceOffersPage(
        content: [offerA],
        pageNumber: 0,
        pageSize: 1,
        totalElements: 2,
        totalPages: 2,
        isLast: false,
      );
      await notifier.loadOffers();

      final initialData = (notifier.state.offersState as SectionLoaded<PlaceOffersData>).data;
      expect(initialData.offers.length, 1);
      expect(initialData.offers.first.product.id, 'prod-A');

      // 2. Inicie o carregamento da próxima página e mantenha a resposta pendente via Completer
      final paginationCompleter = Completer<PlaceOffersPage>();
      repository.onGetOffers = (placeId, page, size) {
        if (page == 1) {
          return paginationCompleter.future;
        }
        return Future.value(const PlaceOffersPage(
          content: [],
          pageNumber: 0,
          pageSize: 50,
          totalElements: 0,
          totalPages: 0,
          isLast: true,
        ));
      };

      final loadMoreFuture = notifier.loadMoreOffers();
      expect(
        (notifier.state.offersState as SectionLoaded<PlaceOffersData>).data.isLoadingMore,
        isTrue,
      );

      // 3. Inicie uma atualização completa e faça essa atualização terminar com uma lista vazia
      await notifier.loadOffers();

      final reloadedData = (notifier.state.offersState as SectionLoaded<PlaceOffersData>).data;
      expect(reloadedData.offers, isEmpty, reason: 'atualização completa resultou em catálogo vazio');
      expect(reloadedData.currentPage, 0);
      expect(reloadedData.isLastPage, isTrue);

      // 4. Libere a resposta atrasada da paginação, que contém A e B
      paginationCompleter.complete(const PlaceOffersPage(
        content: [offerA, offerB],
        pageNumber: 1,
        pageSize: 1,
        totalElements: 2,
        totalPages: 2,
        isLast: true,
      ));

      await loadMoreFuture;

      // 5. Confirme que a interface continua sem ofertas antigas, que a paginação corresponde ao resultado
      // mais recente e que não há flags de carregamento indevidamente presas
      final finalData = (notifier.state.offersState as SectionLoaded<PlaceOffersData>).data;
      expect(finalData.offers, isEmpty, reason: 'resposta obsoleta de paginação não pode poluir catálogo recarregado');
      expect(finalData.currentPage, 0);
      expect(finalData.isLastPage, isTrue);
      expect(finalData.isLoadingMore, isFalse, reason: 'flag isLoadingMore não deve ser alterada por resposta obsoleta');
      expect(finalData.loadMoreError, isNull);
    });

    test('B1: descarte do notifier durante chamada assíncrona não dispara exceção nem notifica após dispose', () async {
      final hoursCompleter = Completer<PlaceOpeningHours>();
      final amenitiesCompleter = Completer<PlaceAmenities>();
      final offersCompleter = Completer<PlaceOffersPage>();

      repository.onGetOpeningHours = (_) => hoursCompleter.future;
      repository.onGetAmenities = (_) => amenitiesCompleter.future;
      repository.onGetOffers = (_, __, ___) => offersCompleter.future;

      var listenerCallCount = 0;
      var notificationsAfterDispose = 0;

      notifier.addListener(() {
        listenerCallCount++;
        if (notifier.isDisposed) {
          notificationsAfterDispose++;
        }
      });

      // Dispara as chamadas assíncronas concorrentes
      final loadAllFuture = notifier.loadAll();
      expect(listenerCallCount, greaterThan(0), reason: 'deve notificar estados Loading iniciais');

      // Descarta o notifier enquanto as três chamadas ainda estão pendentes
      notifier.dispose();
      expect(notifier.isDisposed, isTrue);

      // Libera as respostas pendentes (inclusive erros para testar os blocos catch)
      hoursCompleter.completeError(Exception('Falha de rede tardia em horários'));
      amenitiesCompleter.complete(const PlaceAmenities(
        placeId: 'p-1',
        configured: true,
        amenities: [],
      ));
      offersCompleter.completeError(Exception('Falha de rede tardia em ofertas'));

      // Aguarda a conclusão sem lançar exceção
      await expectLater(loadAllFuture, completes);

      // Confirma que nenhuma notificação foi emitida após o descarte
      expect(notificationsAfterDispose, 0, reason: 'nenhum notifyListeners deve ser disparado após dispose()');
    });
  });
}
