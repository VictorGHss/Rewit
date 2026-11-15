import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rewit_mobile/app/theme/app_theme.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/features/feed/domain/entities/feed_entities.dart';
import 'package:rewit_mobile/features/place/domain/repositories/place_repository.dart';
import 'package:rewit_mobile/features/product/domain/entities/create_product_input.dart';
import 'package:rewit_mobile/features/product/domain/entities/product_detail.dart';
import 'package:rewit_mobile/features/product/domain/repositories/product_repository.dart';
import 'package:rewit_mobile/features/review_creation/domain/entities/review_creation_input.dart';
import 'package:rewit_mobile/features/review_creation/domain/repositories/review_creation_repository.dart';
import 'package:rewit_mobile/features/review_creation/presentation/screens/review_create_screen.dart';
import 'package:rewit_mobile/features/review_creation/presentation/widgets/target_search_selector_widget.dart';
import 'package:rewit_mobile/features/search/domain/entities/search_entities.dart';
import 'package:rewit_mobile/features/search/domain/repositories/search_repository.dart';

const _placeId = '55555555-5555-5555-5555-555555555555';
const _existingProductId = '22222222-2222-2222-2222-222222222222';
const _createdProductId = '33333333-3333-4333-8333-333333333333';

const _place = SearchResultItem(
  id: _placeId,
  name: 'Cafeteria Central',
  category: 'Cafés',
  targetType: TargetType.place,
  rawTargetType: 'PLACE',
  status: 'ACTIVE',
);

const _existingProduct = SearchResultItem(
  id: _existingProductId,
  name: 'Café Torrado Serra',
  category: 'Bebidas',
  targetType: TargetType.product,
  rawTargetType: 'PRODUCT',
  status: 'ACTIVE',
);

/// Busca por termo: cada consulta devolve a lista configurada (ausente = nenhum resultado).
class _SearchByQuery implements SearchRepository {
  final Map<String, List<SearchResultItem>> resultsByQuery;
  _SearchByQuery(this.resultsByQuery);

  @override
  Future<SearchPage> search({required String query, int page = 0, int size = 20}) async {
    final items = resultsByQuery[query.trim().toLowerCase()] ?? const <SearchResultItem>[];
    return SearchPage(
      items: items,
      pageNumber: page,
      pageSize: size,
      totalElements: items.length,
      totalPages: 1,
      isLast: true,
    );
  }
}

class _FakeProductRepository implements ProductRepository {
  final List<CreateProductInput> createCalls = [];
  Completer<ProductDetail>? pending;
  Exception? failure;

  @override
  Future<ProductDetail> createProduct(CreateProductInput input) async {
    createCalls.add(input);
    if (pending != null) return pending!.future;
    if (failure != null) throw failure!;
    return ProductDetail(
      id: _createdProductId,
      name: input.name.trim(),
      brand: input.brand?.trim() ?? '',
      category: input.category?.trim() ?? '',
      status: 'ACTIVE',
    );
  }

  // O fluxo de avaliação só cadastra produtos; qualquer outra chamada é um erro do teste
  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

/// Só habilita o botão de cadastro de local no seletor; os testes não abrem esse cadastro.
class _UnusedPlaceRepository implements PlaceRepository {
  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

class _CapturingReviewRepository implements ReviewCreationRepository {
  CreateReviewInput? captured;
  int calls = 0;

  @override
  Future<FeedReview> createReview(CreateReviewInput input) async {
    calls++;
    captured = input;
    return FeedReview(
      id: 'review-$calls',
      author: const FeedAuthor(displayName: 'Autor'),
      visibility: input.visibility,
      status: 'VISIBLE',
      createdAt: DateTime(2026, 10, 23),
      targets: input.targets
          .map((t) => FeedTarget(id: 't', targetId: t.rateableTargetId, rating: t.rating))
          .toList(),
    );
  }
}

ApiException _problem(int status, String detail, {Map<String, String>? fieldErrors}) {
  return ApiException(ProblemDetail(
    type: 'about:blank',
    title: 'Erro',
    status: status,
    detail: detail,
    fieldErrors: fieldErrors,
  ));
}

void main() {
  // --------------------------------------------------------------------------------------------------
  // Seletor de alvos
  // --------------------------------------------------------------------------------------------------
  group('TargetSearchSelectorWidget: produtos', () {
    late _FakeProductRepository products;
    late List<SearchResultItem> selected;

    setUp(() {
      products = _FakeProductRepository();
      selected = [];
    });

    Future<void> pumpSelector(WidgetTester tester, SearchRepository search, {PlaceRepository? places}) async {
      tester.view.physicalSize = const Size(800, 2000);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      await tester.pumpWidget(MaterialApp(
        theme: AppTheme.lightTheme,
        home: Scaffold(
          body: SingleChildScrollView(
            padding: const EdgeInsets.all(16),
            child: TargetSearchSelectorWidget(
              searchRepository: search,
              placeRepository: places,
              productRepository: products,
              targetIndex: 0,
              onTargetSelected: selected.add,
            ),
          ),
        ),
      ));
    }

    Future<void> search(WidgetTester tester, String query) async {
      await tester.enterText(find.widgetWithText(TextFormField, 'O que você quer avaliar? *'), query);
      await tester.pump(const Duration(milliseconds: 400));
      await tester.pumpAndSettle();
    }

    Future<void> chooseKind(WidgetTester tester, String label) async {
      await tester.tap(find.descendant(of: find.byKey(const Key('target_kind_filter')), matching: find.text(label)));
      await tester.pumpAndSettle();
    }

    testWidgets('filtro Locais/Produtos mostra só o tipo escolhido da mesma busca', (tester) async {
      await pumpSelector(tester, _SearchByQuery({'cafe': [_place, _existingProduct]}));
      await search(tester, 'cafe');
      expect(find.text('Cafeteria Central'), findsOneWidget);
      expect(find.text('Café Torrado Serra'), findsOneWidget);

      await chooseKind(tester, 'Produtos');
      expect(find.text('Cafeteria Central'), findsNothing);
      expect(find.text('Café Torrado Serra'), findsOneWidget);

      await chooseKind(tester, 'Locais');
      expect(find.text('Cafeteria Central'), findsOneWidget);
      expect(find.text('Café Torrado Serra'), findsNothing);
    });

    testWidgets('selecionar produto existente usa o item do catálogo sem cadastrar duplicata', (tester) async {
      await pumpSelector(tester, _SearchByQuery({'cafe': [_place, _existingProduct]}));
      await search(tester, 'cafe');
      await chooseKind(tester, 'Produtos');
      await tester.tap(find.text('Selecionar'));
      await tester.pumpAndSettle();

      expect(selected.single.id, _existingProductId);
      expect(selected.single.rawTargetType, 'PRODUCT');
      expect(products.createCalls, isEmpty);
    });

    testWidgets('produto inexistente: cadastrar no sheet e voltar selecionado como alvo PRODUCT', (tester) async {
      await pumpSelector(tester, _SearchByQuery({}));
      await search(tester, 'Granola da Casa');
      expect(find.text('Nenhum resultado para "Granola da Casa".'), findsOneWidget);

      await tester.tap(find.byKey(const Key('register_manual_product_button')));
      await tester.pumpAndSettle();
      // O nome vem da busca
      expect(
        find.descendant(of: find.byKey(const Key('manual_product_name_field')), matching: find.text('Granola da Casa')),
        findsOneWidget,
      );
      await tester.enterText(find.byKey(const Key('manual_product_brand_field')), '  Sítio Bom  ');
      await tester.enterText(find.byKey(const Key('manual_product_category_field')), 'Cereais');
      await tester.tap(find.byKey(const Key('manual_product_submit_button')));
      await tester.pumpAndSettle();

      expect(products.createCalls, hasLength(1));
      expect(products.createCalls.single.toJson(), {
        'name': 'Granola da Casa',
        'brand': 'Sítio Bom',
        'category': 'Cereais',
      });
      expect(selected.single.id, _createdProductId);
      expect(selected.single.targetType, TargetType.product);
      expect(selected.single.rawTargetType, 'PRODUCT');
      expect(selected.single.name, 'Granola da Casa');
      expect(find.byKey(const Key('manual_product_submit_button')), findsNothing, reason: 'o sheet fecha');
    });

    testWidgets('validação local: nome obrigatório e marca acima de 128 caracteres não chegam ao backend',
        (tester) async {
      await pumpSelector(tester, _SearchByQuery({}));
      await search(tester, 'xx');
      await tester.tap(find.byKey(const Key('register_manual_product_button')));
      await tester.pumpAndSettle();

      await tester.enterText(find.byKey(const Key('manual_product_name_field')), '   ');
      await tester.tap(find.byKey(const Key('manual_product_submit_button')));
      await tester.pumpAndSettle();
      expect(find.text('Informe o nome do produto.'), findsOneWidget);

      await tester.enterText(find.byKey(const Key('manual_product_name_field')), 'Produto');
      // maxLength do campo trunca a digitação; o limite também é validado no envio
      final brandField = tester.widget<TextField>(
          find.descendant(of: find.byKey(const Key('manual_product_brand_field')), matching: find.byType(TextField)));
      expect(brandField.maxLength, CreateProductInput.maxBrandLength);
      expect(products.createCalls, isEmpty);
      expect(selected, isEmpty);
    });

    testWidgets('erro de campo do backend aparece no campo, o sheet continua aberto e nada é selecionado',
        (tester) async {
      products.failure = _problem(400, 'Parâmetros da requisição inválidos',
          fieldErrors: {'name': 'O nome deve conter até 255 caracteres'});
      await pumpSelector(tester, _SearchByQuery({}));
      await search(tester, 'Produto X');
      await tester.tap(find.byKey(const Key('register_manual_product_button')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('manual_product_submit_button')));
      await tester.pumpAndSettle();

      expect(find.text('O nome deve conter até 255 caracteres'), findsOneWidget);
      expect(find.byKey(const Key('manual_product_error_text')), findsOneWidget);
      expect(find.byKey(const Key('manual_product_submit_button')), findsOneWidget);
      expect(selected, isEmpty);
    });

    testWidgets('conflito 409 e falha de rede mostram mensagens compreensíveis sem selecionar alvo', (tester) async {
      products.failure = _problem(409, 'Conflito de integridade de dados');
      await pumpSelector(tester, _SearchByQuery({}));
      await search(tester, 'Produto Y');
      await tester.tap(find.byKey(const Key('register_manual_product_button')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('manual_product_submit_button')));
      await tester.pumpAndSettle();
      expect(
        find.text('Não foi possível cadastrar o produto com esses dados. Revise os campos e tente novamente.'),
        findsOneWidget,
      );

      products.failure = const NetworkException('Sem conexão com o servidor.');
      await tester.tap(find.byKey(const Key('manual_product_submit_button')));
      await tester.pumpAndSettle();
      expect(find.text('Sem conexão com o servidor.'), findsOneWidget);
      expect(selected, isEmpty);
      expect(products.createCalls, hasLength(2));
    });

    testWidgets('envio em andamento bloqueia um segundo toque: o produto é cadastrado uma vez', (tester) async {
      products.pending = Completer<ProductDetail>();
      await pumpSelector(tester, _SearchByQuery({}));
      await search(tester, 'Produto Z');
      await tester.tap(find.byKey(const Key('register_manual_product_button')));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('manual_product_submit_button')));
      await tester.pump();
      await tester.tap(find.byKey(const Key('manual_product_submit_button')), warnIfMissed: false);
      await tester.pump();
      expect(products.createCalls, hasLength(1));

      products.pending!.complete(const ProductDetail(
          id: _createdProductId, name: 'Produto Z', brand: '', category: '', status: 'ACTIVE'));
      await tester.pumpAndSettle();
      expect(selected.single.id, _createdProductId);
    });

    testWidgets('Locais esconde o cadastro de produto e Produtos esconde o cadastro de local', (tester) async {
      await pumpSelector(tester, _SearchByQuery({}), places: _UnusedPlaceRepository());
      await search(tester, 'nada');
      expect(find.byKey(const Key('register_manual_place_button')), findsOneWidget);
      expect(find.byKey(const Key('register_manual_product_button')), findsOneWidget);

      await chooseKind(tester, 'Locais');
      expect(find.byKey(const Key('register_manual_place_button')), findsOneWidget);
      expect(find.byKey(const Key('register_manual_product_button')), findsNothing);

      await chooseKind(tester, 'Produtos');
      expect(find.byKey(const Key('register_manual_place_button')), findsNothing);
      expect(find.byKey(const Key('register_manual_product_button')), findsOneWidget);
    });
  });

  // --------------------------------------------------------------------------------------------------
  // Tela de criação: multi-alvo com tipos diferentes
  // --------------------------------------------------------------------------------------------------
  group('ReviewCreateScreen: local e produto na mesma publicação', () {
    Future<void> pumpScreen(
      WidgetTester tester, {
      required _CapturingReviewRepository reviews,
      required _FakeProductRepository products,
      ValueChanged<FeedReview>? onCreated,
    }) async {
      tester.view.physicalSize = const Size(800, 3200);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      await tester.pumpWidget(MaterialApp(
        theme: AppTheme.lightTheme,
        home: ReviewCreateScreen(
          repository: reviews,
          searchRepository: _SearchByQuery({'cafeteria': [_place]}),
          productRepository: products,
          onReviewCreated: onCreated,
        ),
      ));
    }

    Future<void> search(WidgetTester tester, String query) async {
      await tester.enterText(find.widgetWithText(TextFormField, 'O que você quer avaliar? *'), query);
      await tester.pump(const Duration(milliseconds: 400));
      await tester.pumpAndSettle();
    }

    testWidgets('cadastrar produto no segundo alvo preserva o local, os comentários e o relato; publica os dois',
        (tester) async {
      final reviews = _CapturingReviewRepository();
      final products = _FakeProductRepository();
      FeedReview? created;
      await pumpScreen(tester, reviews: reviews, products: products, onCreated: (r) => created = r);

      // Alvo 1: local existente, com comentário próprio; relato geral preenchido antes do cadastro do produto
      await search(tester, 'cafeteria');
      await tester.tap(find.text('Selecionar'));
      await tester.pumpAndSettle();
      await tester.enterText(
          find.widgetWithText(TextFormField, 'Comentário sobre este alvo (opcional)'), 'Ambiente tranquilo');
      await tester.enterText(find.widgetWithText(TextFormField, 'Conte sua experiência geral...'), 'Boa visita');

      // Alvo 2: produto inexistente cadastrado pelo sheet
      await tester.tap(find.text('Adicionar Outro Alvo / Item'));
      await tester.pumpAndSettle();
      await search(tester, 'Bolo de Fubá');
      await tester.tap(find.byKey(const Key('register_manual_product_button')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('manual_product_submit_button')));
      await tester.pumpAndSettle();

      // O alvo 1 e o relato continuam como estavam
      expect(find.text('Cafeteria Central'), findsOneWidget);
      expect(find.text('Bolo de Fubá'), findsOneWidget);
      expect(find.text('Ambiente tranquilo'), findsOneWidget);
      expect(find.text('Boa visita'), findsOneWidget);

      final commentFields = find.widgetWithText(TextFormField, 'Comentário sobre este alvo (opcional)');
      await tester.enterText(commentFields.at(1), 'Massa fofinha');
      await tester.tap(find.text('Publicar Avaliação'));
      await tester.pumpAndSettle();

      expect(created, isNotNull);
      expect(reviews.calls, 1);
      final targets = reviews.captured!.targets;
      expect(targets.map((t) => t.rateableTargetId).toList(), [_placeId, _createdProductId]);
      expect(targets.map((t) => t.targetType).toList(), ['PLACE', 'PRODUCT']);
      expect(targets[0].specificComment, 'Ambiente tranquilo');
      expect(targets[1].specificComment, 'Massa fofinha');
      expect(reviews.captured!.contextPlaceId, _placeId, reason: 'o produto não troca o local de contexto');
      expect(reviews.captured!.experienceText, 'Boa visita');
    });

    testWidgets('falha ao cadastrar o produto não publica nada nem simula sucesso; o formulário fica intacto',
        (tester) async {
      final reviews = _CapturingReviewRepository();
      final products = _FakeProductRepository()..failure = const NetworkException('Sem conexão com o servidor.');
      await pumpScreen(tester, reviews: reviews, products: products);

      await search(tester, 'cafeteria');
      await tester.tap(find.text('Selecionar'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Adicionar Outro Alvo / Item'));
      await tester.pumpAndSettle();
      await search(tester, 'Produto Fantasma');
      await tester.tap(find.byKey(const Key('register_manual_product_button')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('manual_product_submit_button')));
      await tester.pumpAndSettle();

      expect(find.text('Sem conexão com o servidor.'), findsOneWidget);
      await tester.tap(find.text('Cancelar'));
      await tester.pumpAndSettle();

      // Alvo 1 preservado; o alvo 2 continua sem seleção e nada foi enviado
      expect(find.text('Cafeteria Central'), findsOneWidget);
      expect(find.text('O que você quer avaliar? *'), findsOneWidget);
      expect(reviews.calls, 0);
      expect(find.text('Avaliação publicada com sucesso!'), findsNothing);
    });
  });
}
