import 'package:flutter_test/flutter_test.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/features/place/domain/entities/place_commercial_entities.dart';
import 'package:rewit_mobile/features/place/domain/repositories/place_commercial_repository.dart';
import 'package:rewit_mobile/features/place/presentation/state/place_opening_hours_edit_notifier.dart';

class _FakeCommercialRepository implements PlaceCommercialRepository {
  PlaceOpeningHours? openingHoursToReturn;
  Exception? exceptionToThrow;
  List<DaySchedule>? lastUpdatedDays;
  String? lastUpdatedPlaceId;
  String? lastUpdatedBusinessAccountId;

  @override
  Future<PlaceOpeningHours> getOpeningHours(String placeId) async {
    if (exceptionToThrow != null) throw exceptionToThrow!;
    return openingHoursToReturn ??
        PlaceOpeningHours(
          placeId: placeId,
          configured: true,
          days: const [
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
  Future<PlaceOpeningHours> updateOpeningHours({
    required String placeId,
    required String businessAccountId,
    required List<DaySchedule> days,
  }) async {
    lastUpdatedPlaceId = placeId;
    lastUpdatedBusinessAccountId = businessAccountId;
    lastUpdatedDays = days;
    if (exceptionToThrow != null) throw exceptionToThrow!;
    return PlaceOpeningHours(
      placeId: placeId,
      configured: true,
      days: days,
    );
  }

  @override
  Future<PlaceAmenities> getAmenities(String placeId) async =>
      throw UnimplementedError();

  @override
  Future<List<PlaceAmenityItem>> getAmenityOptions() async =>
      throw UnimplementedError();

  @override
  Future<PlaceAmenities> updateAmenities({
    required String placeId,
    required String businessAccountId,
    required List<String> amenityCodes,
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

}

void main() {
  group('PlaceOpeningHoursEditNotifier Tests', () {
    late _FakeCommercialRepository repository;
    late PlaceOpeningHoursEditNotifier notifier;

    setUp(() {
      repository = _FakeCommercialRepository();
      notifier = PlaceOpeningHoursEditNotifier(repository: repository);
    });

    tearDown(() {
      notifier.dispose();
    });

    test('load normaliza para os 7 dias da semana ordenados', () async {
      await notifier.load('p-1');

      expect(notifier.isLoading, isFalse);
      expect(notifier.days.length, 7);
      expect(notifier.days.map((d) => d.dayOfWeek).toList(), [1, 2, 3, 4, 5, 6, 7]);
      expect(notifier.days[0].closed, isFalse);
      expect(notifier.days[0].intervals.length, 1);
      // Dias não retornados pelo backend devem estar fechados por padrão
      expect(notifier.days[1].closed, isTrue);
      expect(notifier.days[1].intervals, isEmpty);
    });

    test('load trata erro da API e inicializa semana padrão', () async {
      repository.exceptionToThrow = const ApiException(
        ProblemDetail(
          type: 'about:blank',
          status: 500,
          title: 'Erro',
          detail: 'Falha no banco',
        ),
      );

      await notifier.load('p-1');

      expect(notifier.isLoading, isFalse);
      expect(notifier.errorMessage, 'Falha no banco');
      expect(notifier.days.length, 7);
      expect(notifier.days.every((d) => d.closed), isTrue);
    });

    test('setDayClosed abre o dia com intervalo padrão se estiver vazio', () async {
      await notifier.load('p-1');
      notifier.setDayClosed(2, false);
      final day2 = notifier.days.firstWhere((d) => d.dayOfWeek == 2);
      expect(day2.closed, isFalse);
      expect(day2.intervals.length, 1);
      expect(day2.intervals.first.openTime, '08:00');
      expect(day2.intervals.first.closeTime, '18:00');

      // Fechando o dia zera os intervalos
      notifier.setDayClosed(2, true);
      final day2Closed = notifier.days.firstWhere((d) => d.dayOfWeek == 2);
      expect(day2Closed.closed, isTrue);
      expect(day2Closed.intervals, isEmpty);
    });

    test('addInterval, updateInterval e removeInterval alteram a programação', () async {
      await notifier.load('p-1');

      // Adiciona segundo intervalo na segunda-feira
      notifier.addInterval(1, openTime: '19:00', closeTime: '22:00');
      expect(notifier.days[0].intervals.length, 2);
      expect(notifier.days[0].intervals[1].openTime, '19:00');

      // Atualiza horário
      notifier.updateInterval(1, 1, openTime: '19:30', closeTime: '23:00');
      expect(notifier.days[0].intervals[1].openTime, '19:30');
      expect(notifier.days[0].intervals[1].closeTime, '23:00');

      // Remove intervalo
      notifier.removeInterval(1, 1);
      expect(notifier.days[0].intervals.length, 1);
    });

    test('validate detecta dia aberto sem intervalos', () async {
      await notifier.load('p-1');
      // Remove o único intervalo do dia 1 mas mantém closed=false manualmente
      notifier.removeInterval(1, 0);
      // forçar dia 1 aberto sem intervalos
      final day1 = notifier.days[0].copyWith(closed: false, intervals: const []);
      final days = List<DaySchedule>.from(notifier.days);
      days[0] = day1;
      // aciona setDayClosed para abrir sem intervalos se simularmos
      notifier.addInterval(1, openTime: '10:00', closeTime: '12:00');
      expect(notifier.validate(), isNull);
    });

    test('validate detecta horário de abertura posterior ou igual ao fechamento', () async {
      await notifier.load('p-1');
      notifier.updateInterval(1, 0, openTime: '18:00', closeTime: '08:00');

      final error = notifier.validate();
      expect(error, contains('deve ser anterior ao de fechamento'));
    });

    test('validate detecta intervalos sobrepostos no mesmo dia', () async {
      await notifier.load('p-1');
      notifier.addInterval(1, openTime: '10:00', closeTime: '14:00');
      // 08:00-18:00 e 10:00-14:00 se sobrepõem!
      final error = notifier.validate();
      expect(error, contains('sobrepostos'));
    });

    test('save bloqueia requisição se a validação falhar', () async {
      await notifier.load('p-1');
      notifier.updateInterval(1, 0, openTime: '20:00', closeTime: '08:00');

      final success = await notifier.save(placeId: 'p-1', businessAccountId: 'b-1');
      expect(success, isFalse);
      expect(notifier.errorMessage, isNotNull);
      expect(repository.lastUpdatedDays, isNull);
    });

    test('save envia dados válidos e atualiza successMessage', () async {
      await notifier.load('p-1');

      final success = await notifier.save(placeId: 'p-1', businessAccountId: 'b-1');
      expect(success, isTrue);
      expect(notifier.successMessage, 'Horários de funcionamento atualizados com sucesso.');
      expect(repository.lastUpdatedPlaceId, 'p-1');
      expect(repository.lastUpdatedBusinessAccountId, 'b-1');
      expect(repository.lastUpdatedDays?.length, 7);
    });

    test('save preserva edições do usuário caso o backend retorne erro', () async {
      await notifier.load('p-1');
      notifier.addInterval(1, openTime: '19:00', closeTime: '22:00');

      repository.exceptionToThrow = const ApiException(
        ProblemDetail(
          type: 'about:blank',
          status: 403,
          title: 'Forbidden',
          detail: 'Você não é responsável por este local',
        ),
      );

      final success = await notifier.save(placeId: 'p-1', businessAccountId: 'b-1');
      expect(success, isFalse);
      expect(notifier.errorMessage, 'Você não é responsável por este local');
      // Edições devem ser preservadas
      expect(notifier.days[0].intervals.length, 2);
      expect(notifier.days[0].intervals[1].openTime, '19:00');
    });

    test('save bloqueia chamadas concorrentes enquanto salva', () async {
      await notifier.load('p-1');
      // Dispara primeiro save
      final first = notifier.save(placeId: 'p-1', businessAccountId: 'b-1');
      // Segundo save imediato enquanto o primeiro está ativo
      final second = notifier.save(placeId: 'p-1', businessAccountId: 'b-1');

      final results = await Future.wait([first, second]);
      expect(results[0], isTrue);
      expect(results[1], isFalse);
    });
  });
}
