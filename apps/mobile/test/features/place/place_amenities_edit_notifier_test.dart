import 'package:flutter_test/flutter_test.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/features/place/domain/entities/place_commercial_entities.dart';
import 'package:rewit_mobile/features/place/domain/repositories/place_commercial_repository.dart';
import 'package:rewit_mobile/features/place/presentation/state/place_amenities_edit_notifier.dart';

import 'package:rewit_mobile/features/place/domain/entities/place_detail.dart';
class _FakeCommercialRepository implements PlaceCommercialRepository {
  List<PlaceAmenityItem>? catalogToReturn;
  PlaceAmenities? placeAmenitiesToReturn;
  Exception? exceptionToThrow;

  List<String>? lastUpdatedCodes;
  String? lastUpdatedPlaceId;
  String? lastUpdatedBusinessAccountId;

  @override
  Future<List<PlaceAmenityItem>> getAmenityOptions() async {
    if (exceptionToThrow != null) throw exceptionToThrow!;
    return catalogToReturn ??
        const [
          PlaceAmenityItem(code: 'WIFI', label: 'Wi-Fi para clientes'),
          PlaceAmenityItem(code: 'PARKING', label: 'Estacionamento próprio'),
          PlaceAmenityItem(code: 'PET_FRIENDLY', label: 'Aceita animais'),
        ];
  }

  @override
  Future<PlaceAmenities> getAmenities(String placeId) async {
    if (exceptionToThrow != null) throw exceptionToThrow!;
    return placeAmenitiesToReturn ??
        PlaceAmenities(
          placeId: placeId,
          configured: true,
          amenities: const [
            PlaceAmenityItem(code: 'WIFI', label: 'Wi-Fi para clientes'),
          ],
        );
  }

  @override
  Future<PlaceAmenities> updateAmenities({
    required String placeId,
    required String businessAccountId,
    required List<String> amenityCodes,
  }) async {
    lastUpdatedPlaceId = placeId;
    lastUpdatedBusinessAccountId = businessAccountId;
    lastUpdatedCodes = amenityCodes;
    if (exceptionToThrow != null) throw exceptionToThrow!;
    return PlaceAmenities(
      placeId: placeId,
      configured: true,
      amenities: amenityCodes.map((c) => PlaceAmenityItem(code: c, label: c)).toList(),
    );
  }

  @override
  Future<PlaceOpeningHours> getOpeningHours(String placeId) async =>
      throw UnimplementedError();

  @override
  Future<PlaceOpeningHours> updateOpeningHours({
    required String placeId,
    required String businessAccountId,
    required List<DaySchedule> days,
  }) async =>
      throw UnimplementedError();

  @override
  Future<PlaceOffersPage> getOffers(String placeId, {int page = 0, int size = 50}) async =>
      throw UnimplementedError();

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
  group('PlaceAmenitiesEditNotifier Tests', () {
    late _FakeCommercialRepository repository;
    late PlaceAmenitiesEditNotifier notifier;

    setUp(() {
      repository = _FakeCommercialRepository();
      notifier = PlaceAmenitiesEditNotifier(repository: repository);
    });

    tearDown(() {
      notifier.dispose();
    });

    test('load carrega opções do catálogo e comodidades vinculadas', () async {
      await notifier.load('p-1');

      expect(notifier.isLoading, isFalse);
      expect(notifier.catalogOptions.length, 3);
      expect(notifier.selectedCodes, {'WIFI'});
      expect(notifier.configured, isTrue);
    });

    test('toggleAmenity adiciona e remove códigos de comodidade', () async {
      await notifier.load('p-1');

      notifier.toggleAmenity('PARKING');
      expect(notifier.selectedCodes, {'WIFI', 'PARKING'});

      notifier.toggleAmenity('WIFI');
      expect(notifier.selectedCodes, {'PARKING'});
    });

    test('selectAll e clearAll modificam a seleção de forma agregada', () async {
      await notifier.load('p-1');

      notifier.selectAll();
      expect(notifier.selectedCodes, {'WIFI', 'PARKING', 'PET_FRIENDLY'});

      notifier.clearAll();
      expect(notifier.selectedCodes, isEmpty);
      expect(notifier.hasDeclaredNone, isTrue); // configured=true e seleção vazia
    });

    test('save envia seleção atual e atualiza successMessage', () async {
      await notifier.load('p-1');
      notifier.toggleAmenity('PET_FRIENDLY');

      final success = await notifier.save(placeId: 'p-1', businessAccountId: 'b-1');
      expect(success, isTrue);
      expect(notifier.successMessage, 'Comodidades atualizadas com sucesso.');
      expect(repository.lastUpdatedPlaceId, 'p-1');
      expect(repository.lastUpdatedBusinessAccountId, 'b-1');
      expect(repository.lastUpdatedCodes, containsAll(['WIFI', 'PET_FRIENDLY']));
    });

    test('save com lista vazia declara formalmente ausência de comodidades', () async {
      await notifier.load('p-1');
      notifier.clearAll();

      final success = await notifier.save(placeId: 'p-1', businessAccountId: 'b-1');
      expect(success, isTrue);
      expect(repository.lastUpdatedCodes, isEmpty);
      expect(notifier.hasDeclaredNone, isTrue);
    });

    test('save preserva seleção caso backend retorne erro', () async {
      await notifier.load('p-1');
      notifier.toggleAmenity('PARKING');

      repository.exceptionToThrow = const ApiException(
        ProblemDetail(
          type: 'about:blank',
          status: 403,
          title: 'Forbidden',
          detail: 'Acesso negado para gerenciar o local.',
        ),
      );

      final success = await notifier.save(placeId: 'p-1', businessAccountId: 'b-1');
      expect(success, isFalse);
      expect(notifier.errorMessage, 'Acesso negado para gerenciar o local.');
      // Seleção do usuário não deve ser revertida
      expect(notifier.selectedCodes, {'WIFI', 'PARKING'});
    });

    test('save bloqueia requisições concorrentes', () async {
      await notifier.load('p-1');

      final first = notifier.save(placeId: 'p-1', businessAccountId: 'b-1');
      final second = notifier.save(placeId: 'p-1', businessAccountId: 'b-1');

      final results = await Future.wait([first, second]);
      expect(results[0], isTrue);
      expect(results[1], isFalse);
    });
  });
}
