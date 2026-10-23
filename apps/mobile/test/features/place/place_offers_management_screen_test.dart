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

    await tester.pumpWidget(_buildTestApp(args: validArgs, notifier: notifier));
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

  group('Validação estrita de preços no formulário de oferta', () {
    Future<void> openFormAndSelectProduct(WidgetTester tester) async {
      commercialRepo.pageToReturn = const PlaceOffersPage(content: [], isLast: true);
      searchRepo.pageToReturn = const SearchPage(
        items: [
          SearchResultItem(
            id: 'p-test',
            name: 'Item de Teste',
            category: 'Geral',
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

      await tester.tap(find.byKey(const Key('add_offer_button')));
      await tester.pumpAndSettle();

      await tester.enterText(find.byKey(const Key('product_search_input')), 'Item');
      await tester.pump(const Duration(milliseconds: 350));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('product_search_item_p-test')));
      await tester.pumpAndSettle();
    }

    testWidgets('aceita preço com vírgula e ponto decimal com duas casas', (tester) async {
      await openFormAndSelectProduct(tester);

      // Com vírgula
      await tester.enterText(find.byKey(const Key('offer_price_input')), '14,50');
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('save_offer_button')));
      await tester.pumpAndSettle();

      expect(commercialRepo.lastUpsertPrice, 14.50);
      expect(find.byKey(const Key('offers_form_sheet')), findsNothing);

      // Abre novamente e testa com ponto decimal
      await tester.tap(find.byKey(const Key('add_offer_button')));
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(const Key('product_search_input')), 'Item');
      await tester.pump(const Duration(milliseconds: 350));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('product_search_item_p-test')));
      await tester.pumpAndSettle();

      await tester.enterText(find.byKey(const Key('offer_price_input')), '19.85');
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('save_offer_button')));
      await tester.pumpAndSettle();

      expect(commercialRepo.lastUpsertPrice, 19.85);
      expect(find.byKey(const Key('offers_form_sheet')), findsNothing);
    });

    testWidgets('aceita preço com zero e uma casa decimal', (tester) async {
      await openFormAndSelectProduct(tester);

      // Zero casas
      await tester.enterText(find.byKey(const Key('offer_price_input')), '25');
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('save_offer_button')));
      await tester.pumpAndSettle();

      expect(commercialRepo.lastUpsertPrice, 25.0);

      // Uma casa decimal
      await tester.tap(find.byKey(const Key('add_offer_button')));
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(const Key('product_search_input')), 'Item');
      await tester.pump(const Duration(milliseconds: 350));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('product_search_item_p-test')));
      await tester.pumpAndSettle();

      await tester.enterText(find.byKey(const Key('offer_price_input')), '30,5');
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('save_offer_button')));
      await tester.pumpAndSettle();

      expect(commercialRepo.lastUpsertPrice, 30.5);
    });

    testWidgets('rejeita preço com 3 casas decimais sem arredondar e preserva o texto digitado', (tester) async {
      await openFormAndSelectProduct(tester);

      commercialRepo.lastUpsertPrice = null;
      await tester.enterText(find.byKey(const Key('offer_price_input')), '12,999');
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('save_offer_button')));
      await tester.pumpAndSettle();

      // Modal NÃO fecha e repositório NÃO é chamado
      expect(find.byKey(const Key('offers_form_sheet')), findsOneWidget);
      expect(find.text('O preço não pode ter mais de duas casas decimais.'), findsOneWidget);
      expect(commercialRepo.lastUpsertPrice, isNull);

      // Texto digitado permanece no campo para correção do usuário
      expect(find.text('12,999'), findsOneWidget);
    });

    testWidgets('rejeita valor negativo e caracteres inválidos', (tester) async {
      await openFormAndSelectProduct(tester);

      commercialRepo.lastUpsertPrice = null;
      await tester.enterText(find.byKey(const Key('offer_price_input')), '-5.00');
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('save_offer_button')));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('offers_form_sheet')), findsOneWidget);
      expect(find.text('Informe um valor numérico válido.'), findsOneWidget);
      expect(commercialRepo.lastUpsertPrice, isNull);

      // Caracteres não numéricos
      await tester.enterText(find.byKey(const Key('offer_price_input')), 'abc');
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('save_offer_button')));
      await tester.pumpAndSettle();

      expect(find.text('Informe um valor numérico válido.'), findsOneWidget);
      expect(commercialRepo.lastUpsertPrice, isNull);
    });

    testWidgets('rejeita campo vazio no modo de valor customizado', (tester) async {
      await openFormAndSelectProduct(tester);

      commercialRepo.lastUpsertPrice = null;
      await tester.enterText(find.byKey(const Key('offer_price_input')), '   ');
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('save_offer_button')));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('offers_form_sheet')), findsOneWidget);
      expect(find.text('Informe o valor do produto.'), findsOneWidget);
      expect(commercialRepo.lastUpsertPrice, isNull);
    });

    testWidgets('aceita limite máximo 9999999999.99 e rejeita valor excedente', (tester) async {
      await openFormAndSelectProduct(tester);

      // Excedente
      await tester.enterText(find.byKey(const Key('offer_price_input')), '10000000000.00');
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('save_offer_button')));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('offers_form_sheet')), findsOneWidget);
      expect(find.text('O preço excede o limite permitido.'), findsOneWidget);
      expect(commercialRepo.lastUpsertPrice, isNull);

      // Limite máximo exato
      await tester.enterText(find.byKey(const Key('offer_price_input')), '9999999999.99');
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('save_offer_button')));
      await tester.pumpAndSettle();

      expect(commercialRepo.lastUpsertPrice, 9999999999.99);
      expect(find.byKey(const Key('offers_form_sheet')), findsNothing);
    });

    testWidgets('suporta modo gratuito e modo preço não informado', (tester) async {
      await openFormAndSelectProduct(tester);

      // Gratuito
      await tester.tap(find.byKey(const Key('price_mode_free')));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('offer_price_input')), findsNothing);

      await tester.tap(find.byKey(const Key('save_offer_button')));
      await tester.pumpAndSettle();

      expect(commercialRepo.lastUpsertPrice, 0.0);

      // Não informado
      await tester.tap(find.byKey(const Key('add_offer_button')));
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(const Key('product_search_input')), 'Item');
      await tester.pump(const Duration(milliseconds: 350));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('product_search_item_p-test')));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('price_mode_undisclosed')));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('offer_price_input')), findsNothing);

      await tester.tap(find.byKey(const Key('save_offer_button')));
      await tester.pumpAndSettle();

      expect(commercialRepo.lastUpsertPrice, isNull);
    });
  });

  group('Invalidação e isolamento da busca no modal', () {
    testWidgets('fechar e reabrir formulário limpa busca e resultados anteriores', (tester) async {
      commercialRepo.pageToReturn = const PlaceOffersPage(content: [], isLast: true);
      searchRepo.pageToReturn = const SearchPage(
        items: [
          SearchResultItem(
            id: 'p-1',
            name: 'Café Grão Especial',
            category: 'Grãos',
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

      // Abre formulário e pesquisa
      await tester.tap(find.byKey(const Key('add_offer_button')));
      await tester.pumpAndSettle();

      await tester.enterText(find.byKey(const Key('product_search_input')), 'Café');
      await tester.pump(const Duration(milliseconds: 350));
      await tester.pumpAndSettle();

      expect(find.text('Café Grão Especial'), findsOneWidget);

      // Fecha modal pelo X
      await tester.tap(find.byIcon(Icons.close));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('offers_form_sheet')), findsNothing);

      // Reabre modal
      await tester.tap(find.byKey(const Key('add_offer_button')));
      await tester.pumpAndSettle();

      // Campo de busca e resultados devem estar limpos
      expect(find.text('Café Grão Especial'), findsNothing);
      final searchInput = tester.widget<TextField>(find.byKey(const Key('product_search_input')));
      expect(searchInput.controller?.text, isEmpty);
    });

    testWidgets('limpar termo com menos de 2 caracteres esvazia resultados de busca', (tester) async {
      commercialRepo.pageToReturn = const PlaceOffersPage(content: [], isLast: true);
      searchRepo.pageToReturn = const SearchPage(
        items: [
          SearchResultItem(
            id: 'p-1',
            name: 'Pão Italiano',
            category: 'Padaria',
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

      await tester.tap(find.byKey(const Key('add_offer_button')));
      await tester.pumpAndSettle();

      await tester.enterText(find.byKey(const Key('product_search_input')), 'Pão');
      await tester.pump(const Duration(milliseconds: 350));
      await tester.pumpAndSettle();

      expect(find.text('Pão Italiano'), findsOneWidget);

      // Apaga o texto deixando 1 caractere
      await tester.enterText(find.byKey(const Key('product_search_input')), 'P');
      await tester.pumpAndSettle();

      expect(find.text('Pão Italiano'), findsNothing);
    });
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

    await tester.tap(find.byKey(const Key('edit_offer_button_prod-edit-1')));
    await tester.pumpAndSettle();

    expect(find.byKey(const Key('offers_form_sheet')), findsOneWidget);
    expect(find.text('Editar oferta'), findsOneWidget);
    expect(find.text('Croissant Francês'), findsWidgets);

    // Altera para Esgotado
    await tester.tap(find.text('Esgotado'));
    await tester.pumpAndSettle();

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

    await tester.tap(find.byKey(const Key('delete_offer_button_prod-del-1')));
    await tester.pumpAndSettle();

    expect(find.text('Retirar da vitrine?'), findsOneWidget);

    await tester.tap(find.byKey(const Key('cancel_delete_offer_button')));
    await tester.pumpAndSettle();

    expect(commercialRepo.lastDeleteProductId, isNull);
    expect(find.text('Suco de Laranja'), findsOneWidget);

    // Confirma exclusão
    await tester.tap(find.byKey(const Key('delete_offer_button_prod-del-1')));
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const Key('confirm_delete_offer_button')));
    await tester.pumpAndSettle();

    expect(commercialRepo.lastDeleteProductId, 'prod-del-1');
    expect(commercialRepo.lastDeletePlaceId, 'place-123');
    expect(find.text('Oferta retirada da vitrine.'), findsOneWidget);
  });
}
