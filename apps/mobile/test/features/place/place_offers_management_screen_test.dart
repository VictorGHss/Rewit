import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/features/place/domain/entities/place_commercial_entities.dart';
import 'package:rewit_mobile/features/place/domain/repositories/place_commercial_repository.dart';
import 'package:rewit_mobile/features/place/presentation/screens/place_offers_management_screen.dart';
import 'package:rewit_mobile/features/place/presentation/state/place_offers_management_notifier.dart';
import 'package:rewit_mobile/features/search/domain/entities/search_entities.dart';
import 'package:rewit_mobile/features/search/domain/repositories/search_repository.dart';

class _FakeCommercialRepository implements PlaceCommercialRepository {
  PlaceOffersPage? pageToReturn;
  PlaceProductOffer? offerToReturnOnUpsert;
  Exception? exceptionToThrow;

  String? lastUpsertPlaceId;
  String? lastUpsertProductId;
  String? lastUpsertAvailability;
  double? lastUpsertPrice;

  String? lastDeletePlaceId;
  String? lastDeleteProductId;

  @override
  Future<PlaceOffersPage> getOffers(String placeId, {int page = 0, int size = 50}) async {
    if (exceptionToThrow != null) throw exceptionToThrow!;
    return pageToReturn ??
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
    lastUpsertAvailability = availability;
    lastUpsertPrice = price;

    if (exceptionToThrow != null) throw exceptionToThrow!;

    return offerToReturnOnUpsert ??
        PlaceProductOffer(
          product: OfferedProductSummary(
            id: productId,
            name: 'Produto Salvo $productId',
            category: 'Geral',
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

  @override
  Future<SearchPage> search({
    required String query,
    int page = 0,
    int size = 20,
  }) async {
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

Widget _buildTestApp({
  required PlaceOffersManagementArgs args,
  required PlaceOffersManagementNotifier notifier,
}) {
  return MaterialApp(
    home: PlaceOffersManagementScreen(
      args: args,
      notifier: notifier,
    ),
  );
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

  const validArgs = PlaceOffersManagementArgs(
    placeId: 'place-123',
    businessAccountId: 'biz-456',
    placeName: 'Café da Praça',
    isApproved: true,
  );

  testWidgets('exibe tela de acesso restrito se estabelecimento não estiver aprovado', (tester) async {
    const unapprovedArgs = PlaceOffersManagementArgs(
      placeId: 'place-123',
      businessAccountId: 'biz-456',
      placeName: 'Café da Praça',
      isApproved: false,
    );

    await tester.pumpWidget(_buildTestApp(args: unapprovedArgs, notifier: notifier));

    expect(find.byKey(const Key('offers_unauthorized_view')), findsOneWidget);
    expect(find.text('Acesso Restrito'), findsOneWidget);
    expect(find.byKey(const Key('add_offer_button')), findsNothing);
  });

  testWidgets('exibe indicador de carregamento durante busca inicial', (tester) async {
    commercialRepo.pageToReturn = const PlaceOffersPage(
      content: [],
      isLast: true,
    );

    // Carregamento assíncrono em andamento
    await tester.pumpWidget(_buildTestApp(args: validArgs, notifier: notifier));
    // Sem settle, enquanto Future do loadOffers ainda está resolvendo
    expect(find.byKey(const Key('offers_loading_indicator')), findsOneWidget);

    await tester.pumpAndSettle();
    expect(find.byKey(const Key('offers_loading_indicator')), findsNothing);
  });

  testWidgets('exibe erro com retry quando loadOffers falha', (tester) async {
    commercialRepo.exceptionToThrow = const ApiException(
      ProblemDetail(type: 'about:blank', status: 500, title: 'Erro', detail: 'Erro interno no servidor.'),
    );

    await tester.pumpWidget(_buildTestApp(args: validArgs, notifier: notifier));
    await tester.pumpAndSettle();

    expect(find.byKey(const Key('offers_error_view')), findsOneWidget);
    expect(find.text('Erro interno no servidor.'), findsOneWidget);

    // Recuperação após retry
    commercialRepo.exceptionToThrow = null;
    commercialRepo.pageToReturn = const PlaceOffersPage(content: [], isLast: true);

    await tester.tap(find.text('Tentar novamente'));
    await tester.pumpAndSettle();

    expect(find.byKey(const Key('offers_error_view')), findsNothing);
    expect(find.byKey(const Key('offers_empty_view')), findsOneWidget);
  });

  testWidgets('exibe empty state com botão de adicionar quando não há ofertas', (tester) async {
    commercialRepo.pageToReturn = const PlaceOffersPage(
      content: [],
      pageNumber: 0,
      totalElements: 0,
      isLast: true,
    );

    await tester.pumpWidget(_buildTestApp(args: validArgs, notifier: notifier));
    await tester.pumpAndSettle();

    expect(find.byKey(const Key('offers_empty_view')), findsOneWidget);
    expect(find.text('Nenhuma oferta na vitrine'), findsOneWidget);
    expect(find.byKey(const Key('add_first_offer_button')), findsOneWidget);
  });

  testWidgets('renderiza lista de ofertas comerciais com cards, preços e disponibilidade', (tester) async {
    commercialRepo.pageToReturn = const PlaceOffersPage(
      content: [
        PlaceProductOffer(
          product: OfferedProductSummary(
            id: 'prod-1',
            name: 'Café Filtrado',
            category: 'Bebidas Quentes',
          ),
          availability: 'AVAILABLE',
          price: 6.50,
        ),
        PlaceProductOffer(
          product: OfferedProductSummary(
            id: 'prod-2',
            name: 'Água da Casa',
            category: 'Bebidas',
          ),
          availability: 'OUT_OF_STOCK',
          price: 0.0,
        ),
      ],
      pageNumber: 0,
      totalElements: 2,
      isLast: true,
    );

    await tester.pumpWidget(_buildTestApp(args: validArgs, notifier: notifier));
    await tester.pumpAndSettle();

    expect(find.byKey(const Key('offer_card_prod-1')), findsOneWidget);
    expect(find.text('Café Filtrado'), findsOneWidget);
    expect(find.text('R\$ 6,50'), findsOneWidget);
    expect(find.text('Disponível'), findsOneWidget);

    expect(find.byKey(const Key('offer_card_prod-2')), findsOneWidget);
    expect(find.text('Água da Casa'), findsOneWidget);
    expect(find.text('Gratuito'), findsOneWidget);
    expect(find.text('Sem estoque'), findsOneWidget);
  });

  testWidgets('fluxo completo de adição de oferta: busca produto, define preço e salva', (tester) async {
    commercialRepo.pageToReturn = const PlaceOffersPage(
      content: [],
      pageNumber: 0,
      totalElements: 0,
      isLast: true,
    );

    searchRepo.pageToReturn = const SearchPage(
      items: [
        SearchResultItem(
          id: 'prod-search-1',
          name: 'Cappuccino Italiano',
          category: 'Cafés Especiais',
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

    await tester.pumpWidget(_buildTestApp(args: validArgs, notifier: notifier));
    await tester.pumpAndSettle();

    // Clica no botão de adicionar nova oferta
    await tester.tap(find.byKey(const Key('add_offer_button')));
    await tester.pumpAndSettle();

    expect(find.byKey(const Key('offers_form_sheet')), findsOneWidget);
    expect(find.byKey(const Key('product_search_input')), findsOneWidget);

    // Digita no campo de busca de produto
    await tester.enterText(find.byKey(const Key('product_search_input')), 'Cappuccino');
    // Aguarda debounce de 300ms
    await tester.pump(const Duration(milliseconds: 350));
    await tester.pumpAndSettle();

    // Seleciona o item encontrado
    expect(find.byKey(const Key('product_search_item_prod-search-1')), findsOneWidget);
    await tester.tap(find.byKey(const Key('product_search_item_prod-search-1')));
    await tester.pumpAndSettle();

    // Verifica que o produto foi selecionado
    expect(find.byKey(const Key('selected_product_card')), findsOneWidget);
    expect(find.text('Cappuccino Italiano'), findsWidgets);

    // Preenche preço praticado
    await tester.enterText(find.byKey(const Key('offer_price_input')), '14,90');
    await tester.pumpAndSettle();

    // Clica em salvar
    await tester.tap(find.byKey(const Key('save_offer_button')));
    await tester.pumpAndSettle();

    // Modal deve ter sido fechado e a oferta enviada ao repositório
    expect(find.byKey(const Key('offers_form_sheet')), findsNothing);
    expect(commercialRepo.lastUpsertProductId, 'prod-search-1');
    expect(commercialRepo.lastUpsertPrice, 14.90);
    expect(commercialRepo.lastUpsertAvailability, 'AVAILABLE');
    expect(find.text('Oferta publicada com sucesso.'), findsOneWidget);
  });

  testWidgets('fluxo de edição: abre modal pré-preenchido, altera disponibilidade e salva', (tester) async {
    commercialRepo.pageToReturn = const PlaceOffersPage(
      content: [
        PlaceProductOffer(
          product: OfferedProductSummary(
            id: 'prod-edit-1',
            name: 'Croissant Francês',
            category: 'Padaria',
          ),
          availability: 'AVAILABLE',
          price: 18.0,
        ),
      ],
      pageNumber: 0,
      totalElements: 1,
      isLast: true,
    );

    await tester.pumpWidget(_buildTestApp(args: validArgs, notifier: notifier));
    await tester.pumpAndSettle();

    // Clica no botão de editar
    await tester.tap(find.byKey(const Key('edit_offer_button_prod-edit-1')));
    await tester.pumpAndSettle();

    expect(find.byKey(const Key('offers_form_sheet')), findsOneWidget);
    expect(find.text('Editar oferta'), findsOneWidget);
    expect(find.text('Croissant Francês'), findsWidgets);

    // Altera para Esgotado
    await tester.tap(find.text('Esgotado'));
    await tester.pumpAndSettle();

    // Salva atualização
    await tester.tap(find.byKey(const Key('save_offer_button')));
    await tester.pumpAndSettle();

    expect(find.byKey(const Key('offers_form_sheet')), findsNothing);
    expect(commercialRepo.lastUpsertProductId, 'prod-edit-1');
    expect(commercialRepo.lastUpsertAvailability, 'OUT_OF_STOCK');
    expect(find.text('Oferta atualizada com sucesso.'), findsOneWidget);
  });

  testWidgets('fluxo de exclusão: cancela não remove; confirma remove da vitrine', (tester) async {
    commercialRepo.pageToReturn = const PlaceOffersPage(
      content: [
        PlaceProductOffer(
          product: OfferedProductSummary(
            id: 'prod-del-1',
            name: 'Suco de Laranja',
            category: 'Bebidas',
          ),
          availability: 'AVAILABLE',
          price: 10.0,
        ),
      ],
      pageNumber: 0,
      totalElements: 1,
      isLast: true,
    );

    await tester.pumpWidget(_buildTestApp(args: validArgs, notifier: notifier));
    await tester.pumpAndSettle();

    // Clica em retirar da vitrine
    await tester.tap(find.byKey(const Key('delete_offer_button_prod-del-1')));
    await tester.pumpAndSettle();

    expect(find.text('Retirar da vitrine?'), findsOneWidget);

    // Cancela primeiro
    await tester.tap(find.byKey(const Key('cancel_delete_offer_button')));
    await tester.pumpAndSettle();

    expect(commercialRepo.lastDeleteProductId, isNull);
    expect(find.text('Suco de Laranja'), findsOneWidget);

    // Abre novamente e confirma
    await tester.tap(find.byKey(const Key('delete_offer_button_prod-del-1')));
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const Key('confirm_delete_offer_button')));
    await tester.pumpAndSettle();

    expect(commercialRepo.lastDeleteProductId, 'prod-del-1');
    expect(commercialRepo.lastDeletePlaceId, 'place-123');
    expect(find.text('Oferta retirada da vitrine.'), findsOneWidget);
  });
}

