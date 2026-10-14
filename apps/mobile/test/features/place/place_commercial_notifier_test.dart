import 'package:flutter_test/flutter_test.dart';
import 'package:rewit_mobile/features/place/domain/entities/place_commercial_entities.dart';
import 'package:rewit_mobile/features/place/domain/repositories/place_commercial_repository.dart';
import 'package:rewit_mobile/features/place/presentation/state/place_commercial_notifier.dart';
import 'package:rewit_mobile/features/place/presentation/state/place_commercial_state.dart';

class _FakeCommercialRepository implements PlaceCommercialRepository {
  PlaceOpeningHours? openingHoursToReturn;
  PlaceAmenities? amenitiesToReturn;
  PlaceOffersPage? offersPageToReturn;

  Exception? openingHoursException;
  Exception? amenitiesException;
  Exception? offersException;

  int requestedOffersPage = -1;

  @override
  Future<PlaceOpeningHours> getOpeningHours(String placeId) async {
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
}

void main() {
  group('PlaceCommercialNotifier Tests', () {
    late _FakeCommercialRepository repository;
    late PlaceCommercialNotifier notifier;

    setUp(() {
      repository = _FakeCommercialRepository();
      notifier = PlaceCommercialNotifier(repository: repository, placeId: 'place-test');
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
  });
}
