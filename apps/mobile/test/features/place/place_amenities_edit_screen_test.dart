import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/features/place/domain/entities/place_commercial_entities.dart';
import 'package:rewit_mobile/features/place/domain/repositories/place_commercial_repository.dart';
import 'package:rewit_mobile/features/place/presentation/screens/place_amenities_edit_screen.dart';
import 'package:rewit_mobile/features/place/presentation/state/place_amenities_edit_notifier.dart';

class _FakeCommercialRepository implements PlaceCommercialRepository {
  List<PlaceAmenityItem>? catalogToReturn;
  PlaceAmenities? amenitiesToReturn;
  Exception? exceptionToThrow;
  List<String>? lastUpdatedCodes;

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
    return amenitiesToReturn ??
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

}

void main() {
  Widget buildTestWidget({
    required PlaceAmenitiesEditArgs args,
    required PlaceAmenitiesEditNotifier notifier,
  }) {
    return MaterialApp(
      home: PlaceAmenitiesEditScreen(
        args: args,
        notifier: notifier,
      ),
    );
  }

  group('PlaceAmenitiesEditScreen Widget Tests', () {
    late _FakeCommercialRepository repository;
    late PlaceAmenitiesEditNotifier notifier;

    setUp(() {
      repository = _FakeCommercialRepository();
      notifier = PlaceAmenitiesEditNotifier(repository: repository);
    });

    testWidgets('exibe tela de acesso restrito se isApproved for false', (tester) async {
      await tester.pumpWidget(
        buildTestWidget(
          args: const PlaceAmenitiesEditArgs(
            placeId: 'p-1',
            businessAccountId: 'b-1',
            placeName: 'Café Teste',
            isApproved: false,
          ),
          notifier: notifier,
        ),
      );
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('amenities_unauthorized_view')), findsOneWidget);
      expect(find.text('Acesso Restrito'), findsOneWidget);
      expect(find.byKey(const Key('save_amenities_button')), findsNothing);
    });

    testWidgets('carrega catálogo e renderiza checkboxes com seleção inicial', (tester) async {
      await tester.pumpWidget(
        buildTestWidget(
          args: const PlaceAmenitiesEditArgs(
            placeId: 'p-1',
            businessAccountId: 'b-1',
            placeName: 'Café Teste',
            isApproved: true,
          ),
          notifier: notifier,
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('Comodidades'), findsWidgets);
      expect(find.text('Café Teste'), findsOneWidget);
      expect(find.text('Wi-Fi para clientes'), findsOneWidget);
      expect(find.text('Estacionamento próprio'), findsOneWidget);
      expect(find.text('Aceita animais'), findsOneWidget);

      // 1 selecionada inicialmente (WIFI)
      expect(find.text('1 selecionada(s)'), findsOneWidget);
    });

    testWidgets('alternar checkbox altera seleção', (tester) async {
      await tester.pumpWidget(
        buildTestWidget(
          args: const PlaceAmenitiesEditArgs(
            placeId: 'p-1',
            businessAccountId: 'b-1',
            placeName: 'Café Teste',
            isApproved: true,
          ),
          notifier: notifier,
        ),
      );
      await tester.pumpAndSettle();

      // Clica em Estacionamento próprio
      await tester.tap(find.byKey(const Key('amenity_checkbox_PARKING')));
      await tester.pumpAndSettle();

      expect(find.text('2 selecionada(s)'), findsOneWidget);
      expect(notifier.selectedCodes, containsAll(['WIFI', 'PARKING']));
    });

    testWidgets('botões de ação rápida Marcar todas e Limpar seleção funcionam', (tester) async {
      await tester.pumpWidget(
        buildTestWidget(
          args: const PlaceAmenitiesEditArgs(
            placeId: 'p-1',
            businessAccountId: 'b-1',
            placeName: 'Café Teste',
            isApproved: true,
          ),
          notifier: notifier,
        ),
      );
      await tester.pumpAndSettle();

      // Marcar todas
      await tester.tap(find.byKey(const Key('select_all_amenities_button')));
      await tester.pumpAndSettle();

      expect(find.text('3 selecionada(s)'), findsOneWidget);
      expect(notifier.selectedCodes.length, 3);

      // Limpar seleção
      await tester.tap(find.byKey(const Key('clear_all_amenities_button')));
      await tester.pumpAndSettle();

      expect(find.text('0 selecionada(s)'), findsOneWidget);
      expect(notifier.selectedCodes, isEmpty);
    });

    testWidgets('salva comodidades e exibe SnackBar de sucesso', (tester) async {
      await tester.pumpWidget(
        buildTestWidget(
          args: const PlaceAmenitiesEditArgs(
            placeId: 'p-1',
            businessAccountId: 'b-1',
            placeName: 'Café Teste',
            isApproved: true,
          ),
          notifier: notifier,
        ),
      );
      await tester.pumpAndSettle();

      // Clica em Salvar
      await tester.tap(find.byKey(const Key('save_amenities_button')));
      await tester.pumpAndSettle();

      expect(find.text('Comodidades atualizadas com sucesso.'), findsOneWidget);
      expect(repository.lastUpdatedCodes, ['WIFI']);
    });

    testWidgets('preserva seleção caso ocorra erro no backend', (tester) async {
      await tester.pumpWidget(
        buildTestWidget(
          args: const PlaceAmenitiesEditArgs(
            placeId: 'p-1',
            businessAccountId: 'b-1',
            placeName: 'Café Teste',
            isApproved: true,
          ),
          notifier: notifier,
        ),
      );
      await tester.pumpAndSettle();

      // Marca PET_FRIENDLY
      await tester.tap(find.byKey(const Key('amenity_checkbox_PET_FRIENDLY')));
      await tester.pumpAndSettle();

      repository.exceptionToThrow = const ApiException(
        ProblemDetail(
          type: 'about:blank',
          status: 500,
          title: 'Erro',
          detail: 'Falha interna ao persistir comodidades.',
        ),
      );

      // Clica em Salvar
      await tester.tap(find.byKey(const Key('save_amenities_button')));
      await tester.pumpAndSettle();

      expect(find.descendant(of: find.byType(SnackBar), matching: find.text('Falha interna ao persistir comodidades.')), findsOneWidget);
      // Seleção continua preservada na tela
      expect(find.text('2 selecionada(s)'), findsOneWidget);
      expect(notifier.selectedCodes, containsAll(['WIFI', 'PET_FRIENDLY']));
    });
  });
}
