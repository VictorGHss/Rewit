import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/features/place/domain/entities/place_commercial_entities.dart';
import 'package:rewit_mobile/features/place/domain/repositories/place_commercial_repository.dart';
import 'package:rewit_mobile/features/place/presentation/screens/place_opening_hours_edit_screen.dart';
import 'package:rewit_mobile/features/place/presentation/state/place_opening_hours_edit_notifier.dart';

import 'package:rewit_mobile/features/place/domain/entities/place_detail.dart';
class _FakeCommercialRepository implements PlaceCommercialRepository {
  PlaceOpeningHours? openingHoursToReturn;
  Exception? exceptionToThrow;
  List<DaySchedule>? lastUpdatedDays;

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
            DaySchedule(
              dayOfWeek: 2,
              dayName: 'TUESDAY',
              closed: true,
              intervals: [],
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
  Widget buildTestWidget({
    required PlaceOpeningHoursEditArgs args,
    required PlaceOpeningHoursEditNotifier notifier,
  }) {
    return MaterialApp(
      home: PlaceOpeningHoursEditScreen(
        args: args,
        notifier: notifier,
      ),
    );
  }

  group('PlaceOpeningHoursEditScreen Widget Tests', () {
    late _FakeCommercialRepository repository;
    late PlaceOpeningHoursEditNotifier notifier;

    setUp(() {
      repository = _FakeCommercialRepository();
      notifier = PlaceOpeningHoursEditNotifier(repository: repository);
    });

    testWidgets('exibe tela de acesso restrito se isApproved for false', (tester) async {
      await tester.pumpWidget(
        buildTestWidget(
          args: const PlaceOpeningHoursEditArgs(
            placeId: 'p-1',
            businessAccountId: 'b-1',
            placeName: 'Café Teste',
            isApproved: false,
          ),
          notifier: notifier,
        ),
      );
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('opening_hours_unauthorized_view')), findsOneWidget);
      expect(find.text('Acesso Restrito'), findsOneWidget);
      expect(find.byKey(const Key('save_opening_hours_button')), findsNothing);
    });

    testWidgets('carrega e renderiza os dias e botões de horário quando aprovado', (tester) async {
      await tester.pumpWidget(
        buildTestWidget(
          args: const PlaceOpeningHoursEditArgs(
            placeId: 'p-1',
            businessAccountId: 'b-1',
            placeName: 'Café Teste',
            isApproved: true,
          ),
          notifier: notifier,
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('Horários de Funcionamento'), findsWidgets);
      expect(find.text('Café Teste'), findsOneWidget);
      expect(find.byKey(const Key('day_card_1')), findsOneWidget);
      expect(find.text('Segunda-feira'), findsOneWidget);
      expect(find.text('Terça-feira'), findsOneWidget);

      // Segunda-feira está aberta com 08:00 às 18:00
      expect(find.byKey(const Key('open_time_button_1_0')), findsOneWidget);
      expect(find.text('08:00'), findsOneWidget);
      expect(find.text('18:00'), findsOneWidget);
    });

    testWidgets('alternar switch do dia abre ou fecha a programação', (tester) async {
      await tester.pumpWidget(
        buildTestWidget(
          args: const PlaceOpeningHoursEditArgs(
            placeId: 'p-1',
            businessAccountId: 'b-1',
            placeName: 'Café Teste',
            isApproved: true,
          ),
          notifier: notifier,
        ),
      );
      await tester.pumpAndSettle();

      // Terça-feira (dia 2) estava fechada
      expect(find.byKey(const Key('open_time_button_2_0')), findsNothing);

      // Clica no switch da Terça-feira para abrir
      await tester.tap(find.byKey(const Key('day_switch_2')));
      await tester.pumpAndSettle();

      // Agora Terça-feira possui intervalo padrão
      expect(find.byKey(const Key('open_time_button_2_0')), findsOneWidget);
    });

    testWidgets('exibe erro de validação ao salvar horário inválido', (tester) async {
      await tester.pumpWidget(
        buildTestWidget(
          args: const PlaceOpeningHoursEditArgs(
            placeId: 'p-1',
            businessAccountId: 'b-1',
            placeName: 'Café Teste',
            isApproved: true,
          ),
          notifier: notifier,
        ),
      );
      await tester.pumpAndSettle();

      // Altera o horário da segunda-feira para início 20:00 e fim 18:00
      notifier.updateInterval(1, 0, openTime: '20:00', closeTime: '18:00');
      await tester.pumpAndSettle();

      // Clica em Salvar
      await tester.tap(find.byKey(const Key('save_opening_hours_button')));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('opening_hours_error_banner')), findsOneWidget);
      expect(find.descendant(of: find.byKey(const Key('opening_hours_error_banner')), matching: find.textContaining('deve ser anterior ao de fechamento')), findsOneWidget);
      expect(repository.lastUpdatedDays, isNull);
    });

    testWidgets('salva com sucesso e exibe SnackBar de confirmação', (tester) async {
      await tester.pumpWidget(
        buildTestWidget(
          args: const PlaceOpeningHoursEditArgs(
            placeId: 'p-1',
            businessAccountId: 'b-1',
            placeName: 'Café Teste',
            isApproved: true,
          ),
          notifier: notifier,
        ),
      );
      await tester.pumpAndSettle();

      // Clica no botão Salvar
      await tester.tap(find.byKey(const Key('save_opening_hours_button')));
      await tester.pumpAndSettle();

      expect(find.text('Horários de funcionamento atualizados com sucesso.'), findsOneWidget);
      expect(repository.lastUpdatedDays, isNotNull);
    });

    testWidgets('preserva edições e exibe feedback se o salvamento falhar', (tester) async {
      await tester.pumpWidget(
        buildTestWidget(
          args: const PlaceOpeningHoursEditArgs(
            placeId: 'p-1',
            businessAccountId: 'b-1',
            placeName: 'Café Teste',
            isApproved: true,
          ),
          notifier: notifier,
        ),
      );
      await tester.pumpAndSettle();

      // Abre a terça-feira
      await tester.tap(find.byKey(const Key('day_switch_2')));
      await tester.pumpAndSettle();

      // Configura erro no backend
      repository.exceptionToThrow = const ApiException(
        ProblemDetail(
          type: 'about:blank',
          status: 403,
          title: 'Forbidden',
          detail: 'Sem autorização para este local.',
        ),
      );

      // Clica em Salvar
      await tester.tap(find.byKey(const Key('save_opening_hours_button')));
      await tester.pumpAndSettle();

      expect(find.descendant(of: find.byType(SnackBar), matching: find.text('Sem autorização para este local.')), findsOneWidget);
      // Terça-feira continua aberta
      expect(find.byKey(const Key('open_time_button_2_0')), findsOneWidget);
    });
  });
}
