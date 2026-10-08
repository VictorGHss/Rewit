import 'dart:typed_data';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rewit_mobile/app/router/app_router.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/features/feed/domain/entities/feed_entities.dart';
import 'package:rewit_mobile/features/feed/presentation/widgets/review_card.dart';
import 'package:rewit_mobile/features/place/domain/entities/place_detail.dart';
import 'package:rewit_mobile/features/place/domain/entities/target_reviews_page.dart';
import 'package:rewit_mobile/features/place/domain/entities/target_stats.dart';
import 'package:rewit_mobile/features/product/domain/entities/product_detail.dart';
import 'package:rewit_mobile/features/product/domain/entities/product_identifier.dart';
import 'package:rewit_mobile/features/product/domain/entities/products_in_place_page.dart';
import 'package:rewit_mobile/features/product/domain/repositories/product_repository.dart';
import 'package:rewit_mobile/features/product/presentation/screens/product_detail_screen.dart';
import 'package:rewit_mobile/features/product/presentation/state/product_detail_notifier.dart';
import 'package:rewit_mobile/shared/widgets/error_view.dart';
import 'package:rewit_mobile/shared/widgets/loading_indicator.dart';

class _FakeProductRepository implements ProductRepository {
  ProductDetail? productToReturn;
  List<ProductIdentifier>? identifiersToReturn;
  TargetStats? statsToReturn;
  TargetReviewsPage? reviewsPageToReturn;
  Exception? productException;
  Uint8List? imageBytesToReturn;

  @override
  Future<ProductDetail> getProductById(String id) async {
    if (productException != null) throw productException!;
    return productToReturn ??
        const ProductDetail(
          id: 'prod-123',
          name: 'Café Especial Torrado',
          brand: 'Orfeu',
          model: '250g em Grãos',
          description: 'Café premiado de altitude.',
          category: 'BEBIDA',
          imageUrl: 'https://cdn.rewit.test/cafe.png',
          status: 'ACTIVE',
        );
  }

  @override
  Future<List<ProductIdentifier>> getProductIdentifiers(String id) async {
    return identifiersToReturn ??
        const [
          ProductIdentifier(identifierType: 'EAN', identifierValue: '7891234567890'),
          ProductIdentifier(identifierType: 'ISBN', identifierValue: '978-3-16-148410-0'),
        ];
  }

  @override
  Future<ProductDetail> getProductByIdentifier({
    required String type,
    required String value,
  }) async {
    return getProductById('prod-123');
  }

  @override
  Future<ProductsInPlacePage> getProductsInPlace(
    String placeId, {
    int page = 0,
    int size = 20,
  }) async {
    return const ProductsInPlacePage(
      products: [],
      pageNumber: 0,
      pageSize: 20,
      totalElements: 0,
      totalPages: 0,
      isLast: true,
    );
  }

  @override
  Future<TargetStats> getProductStats(String id) async {
    return statsToReturn ??
        const TargetStats(
          targetId: 'prod-123',
          averageRating: 4.9,
          reviewsCount: 30,
        );
  }

  @override
  Future<TargetReviewsPage> getProductReviews(
    String productId, {
    int page = 0,
    int size = 10,
    String sort = 'newest',
    bool verifiedOnly = false,
  }) async {
    return reviewsPageToReturn ??
        TargetReviewsPage(
          reviews: [
            FeedReview(
              id: 'rev-prod-1',
              author: const FeedAuthor(
                id: 'user-barista',
                displayName: 'Ana Barista',
                handle: 'anabarista',
              ),
              experienceText: 'Aroma floral e acidez equilibrada!',
              visibility: 'PUBLIC',
              status: 'ACTIVE',
              createdAt: DateTime(2026, 10, 8),
              targets: const [
                FeedTarget(
                  id: 't-prod-1',
                  targetId: 'prod-123',
                  rating: 5.0,
                ),
              ],
            ),
          ],
          pageNumber: 0,
          pageSize: 10,
          totalElements: 1,
          totalPages: 1,
          isLast: true,
        );
  }

  @override
  Future<Uint8List> getProductImageBytes(String imageUrl) async {
    return imageBytesToReturn ??
        Uint8List.fromList([
          // 1x1 transparent GIF bytes
          0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x01, 0x00, 0x01, 0x00,
          0x80, 0x00, 0x00, 0xff, 0xff, 0xff, 0x00, 0x00, 0x00, 0x21,
          0xf9, 0x04, 0x01, 0x00, 0x00, 0x00, 0x00, 0x2c, 0x00, 0x00,
          0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x02, 0x02, 0x44,
          0x01, 0x00, 0x3b,
        ]);
  }
}

void main() {
  Widget buildTestableWidget({
    required Widget child,
    NavigatorObserver? navigatorObserver,
    Map<String, WidgetBuilder>? routes,
  }) {
    return MaterialApp(
      home: child,
      navigatorObservers: navigatorObserver != null ? [navigatorObserver] : [],
      routes: routes ?? {},
    );
  }

  group('ProductDetailScreen Widget Tests', () {
    late _FakeProductRepository repository;

    const testPlace = PlaceDetail(
      id: 'place-emporio',
      name: 'Empório Central',
      slug: 'emporio-central',
      category: 'MERCADO',
      addressText: 'Rua Principal',
      streetNumber: '10',
      neighborhood: 'Centro',
      city: 'Curitiba',
      state: 'PR',
      latitude: -25.4,
      longitude: -49.2,
      validationRadiusMeters: 50,
      origin: 'USER',
      isVerified: true,
      status: 'ACTIVE',
    );

    setUp(() {
      repository = _FakeProductRepository();
    });

    void setLargeSurface(WidgetTester tester) {
      tester.view.physicalSize = const Size(800, 2400);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(() {
        tester.view.resetPhysicalSize();
        tester.view.resetDevicePixelRatio();
      });
    }

    testWidgets('exibe indicador de carregamento durante a busca inicial', (tester) async {
      final notifier = ProductDetailNotifier(
        repository: repository,
        productId: 'prod-123',
      );

      await tester.pumpWidget(
        buildTestableWidget(
          child: ProductDetailScreen(
            productId: 'prod-123',
            notifier: notifier,
          ),
        ),
      );

      expect(find.byType(LoadingIndicator), findsOneWidget);
      expect(find.text('Carregando detalhes do produto...'), findsOneWidget);
    });

    testWidgets('exibe estado de Produto indisponível quando a API retorna 404', (tester) async {
      repository.productException = const ApiException(
        ProblemDetail(
          type: 'https://api.rewit.com/errors/product-not-found',
          status: 404,
          title: 'Not Found',
          detail: 'Produto não encontrado',
          code: 'PRODUCT_NOT_FOUND',
        ),
      );

      final notifier = ProductDetailNotifier(
        repository: repository,
        productId: 'prod-unknown',
      );

      await tester.pumpWidget(
        buildTestableWidget(
          child: ProductDetailScreen(
            productId: 'prod-unknown',
            notifier: notifier,
          ),
        ),
      );

      await notifier.loadProduct();
      await tester.pumpAndSettle();

      expect(find.text('Produto indisponível'), findsOneWidget);
      expect(
        find.text('Este produto não foi encontrado ou não está mais ativo no catálogo.'),
        findsOneWidget,
      );
      expect(find.byIcon(Icons.inventory_2_outlined), findsOneWidget);
    });

    testWidgets('exibe ErrorView quando ocorre erro genérico e permite retry', (tester) async {
      repository.productException = Exception('Falha de conexão');

      final notifier = ProductDetailNotifier(
        repository: repository,
        productId: 'prod-err',
      );

      await tester.pumpWidget(
        buildTestableWidget(
          child: ProductDetailScreen(
            productId: 'prod-err',
            notifier: notifier,
          ),
        ),
      );

      await notifier.loadProduct();
      await tester.pumpAndSettle();

      expect(find.byType(ErrorView), findsOneWidget);
      expect(find.text('Não foi possível carregar o produto'), findsOneWidget);
    });

    testWidgets('exibe detalhes completos do produto, identificadores, locais e avaliações', (tester) async {
      setLargeSurface(tester);
      final notifier = ProductDetailNotifier(
        repository: repository,
        productId: 'prod-123',
        contextPlace: testPlace,
      );

      await tester.pumpWidget(
        buildTestableWidget(
          child: ProductDetailScreen(
            productId: 'prod-123',
            notifier: notifier,
          ),
        ),
      );

      await notifier.loadProduct();
      await tester.pumpAndSettle();

      // Nome, Marca, Modelo, Categoria e Descrição
      expect(find.text('Café Especial Torrado'), findsOneWidget);
      expect(find.text('Marca: Orfeu'), findsOneWidget);
      expect(find.text('Modelo: 250g em Grãos'), findsOneWidget);
      expect(find.text('BEBIDA'), findsOneWidget);
      expect(find.text('Café premiado de altitude.'), findsOneWidget);

      // Estatísticas
      expect(find.text('4.9'), findsOneWidget);
      expect(find.text('(30 avaliações)'), findsOneWidget);

      // CTA
      expect(find.text('Avaliar este produto'), findsOneWidget);

      // Identificadores
      expect(find.text('EAN'), findsOneWidget);
      expect(find.text('7891234567890'), findsOneWidget);
      expect(find.text('ISBN'), findsOneWidget);
      expect(find.text('978-3-16-148410-0'), findsOneWidget);

      // Onde encontrar
      expect(find.text('Empório Central'), findsOneWidget);

      // Avaliação da comunidade
      expect(find.byType(ReviewCard), findsOneWidget);
      expect(find.text('Aroma floral e acidez equilibrada!'), findsOneWidget);
    });

    testWidgets('exibe placeholder quando o produto não possui imageUrl', (tester) async {
      repository.productToReturn = const ProductDetail(
        id: 'prod-no-img',
        name: 'Chá Mate',
        brand: 'Leão',
        category: 'BEBIDA',
        imageUrl: null,
        status: 'ACTIVE',
      );

      final notifier = ProductDetailNotifier(
        repository: repository,
        productId: 'prod-no-img',
      );

      await tester.pumpWidget(
        buildTestableWidget(
          child: ProductDetailScreen(
            productId: 'prod-no-img',
            notifier: notifier,
          ),
        ),
      );

      await notifier.loadProduct();
      await tester.pumpAndSettle();

      expect(find.text('Sem imagem do produto'), findsOneWidget);
    });

    testWidgets('exibe identificadores vazios amigavelmente quando lista é vazia', (tester) async {
      repository.identifiersToReturn = [];

      final notifier = ProductDetailNotifier(
        repository: repository,
        productId: 'prod-123',
      );

      await tester.pumpWidget(
        buildTestableWidget(
          child: ProductDetailScreen(
            productId: 'prod-123',
            notifier: notifier,
          ),
        ),
      );

      await notifier.loadProduct();
      await tester.pumpAndSettle();

      expect(find.text('Nenhum identificador cadastrado'), findsOneWidget);
    });

    testWidgets('exibe locais vazios amigavelmente quando nenhum local vinculado', (tester) async {
      setLargeSurface(tester);
      final notifier = ProductDetailNotifier(
        repository: repository,
        productId: 'prod-123',
        contextPlace: null,
      );

      await tester.pumpWidget(
        buildTestableWidget(
          child: ProductDetailScreen(
            productId: 'prod-123',
            notifier: notifier,
          ),
        ),
      );

      await notifier.loadProduct();
      await tester.pumpAndSettle();

      expect(find.text('Nenhum local registrado ainda'), findsOneWidget);
    });

    testWidgets('botão Avaliar este produto navega para AppRouter.reviewCreate com parâmetros do produto', (tester) async {
      final notifier = ProductDetailNotifier(
        repository: repository,
        productId: 'prod-123',
      );

      Object? receivedArguments;

      await tester.pumpWidget(
        buildTestableWidget(
          child: ProductDetailScreen(
            productId: 'prod-123',
            notifier: notifier,
          ),
          routes: {
            AppRouter.reviewCreate: (context) {
              receivedArguments = ModalRoute.of(context)?.settings.arguments;
              return const Scaffold(body: Text('ReviewCreateScreen Destino'));
            },
          },
        ),
      );

      await notifier.loadProduct();
      await tester.pumpAndSettle();

      final evaluateButton = find.widgetWithText(ElevatedButton, 'Avaliar este produto');
      expect(evaluateButton, findsOneWidget);

      await tester.tap(evaluateButton);
      await tester.pumpAndSettle();

      expect(find.text('ReviewCreateScreen Destino'), findsOneWidget);
      expect(receivedArguments, isA<Map<String, dynamic>>());
      final argsMap = receivedArguments as Map<String, dynamic>;
      expect(argsMap['targetId'], 'prod-123');
      expect(argsMap['targetName'], 'Café Especial Torrado');
      expect(argsMap['targetType'], 'PRODUCT');
      expect(argsMap['category'], 'BEBIDA');
    });

    testWidgets('toque em local em Onde encontrar navega para AppRouter.placeDetail', (tester) async {
      final notifier = ProductDetailNotifier(
        repository: repository,
        productId: 'prod-123',
        contextPlace: testPlace,
      );

      String? navigatedPlaceId;

      await tester.pumpWidget(
        buildTestableWidget(
          child: ProductDetailScreen(
            productId: 'prod-123',
            notifier: notifier,
          ),
          routes: {
            AppRouter.placeDetail: (context) {
              navigatedPlaceId = ModalRoute.of(context)?.settings.arguments as String?;
              return const Scaffold(body: Text('PlaceDetailScreen Destino'));
            },
          },
        ),
      );

      await notifier.loadProduct();
      await tester.pumpAndSettle();

      await tester.scrollUntilVisible(find.text('Empório Central'), 200);
      await tester.pumpAndSettle();

      await tester.tap(find.text('Empório Central'));
      await tester.pumpAndSettle();

      expect(find.text('PlaceDetailScreen Destino'), findsOneWidget);
      expect(navigatedPlaceId, 'place-emporio');
    });

    testWidgets('toque no ReviewCard navega para AppRouter.reviewDetail', (tester) async {
      final notifier = ProductDetailNotifier(
        repository: repository,
        productId: 'prod-123',
      );

      FeedReview? receivedReview;

      await tester.pumpWidget(
        buildTestableWidget(
          child: ProductDetailScreen(
            productId: 'prod-123',
            notifier: notifier,
          ),
          routes: {
            AppRouter.reviewDetail: (context) {
              receivedReview = ModalRoute.of(context)?.settings.arguments as FeedReview?;
              return const Scaffold(body: Text('ReviewDetailScreen Destino'));
            },
          },
        ),
      );

      await notifier.loadProduct();
      await tester.pumpAndSettle();

      await tester.scrollUntilVisible(find.text('Aroma floral e acidez equilibrada!'), 200);
      await tester.pumpAndSettle();
      await tester.tap(find.text('Aroma floral e acidez equilibrada!'));
      await tester.pumpAndSettle();

      expect(find.text('ReviewDetailScreen Destino'), findsOneWidget);
      expect(receivedReview?.id, 'rev-prod-1');
    });

    testWidgets('toque no autor da avaliação navega para AppRouter.profile', (tester) async {
      final notifier = ProductDetailNotifier(
        repository: repository,
        productId: 'prod-123',
      );

      String? receivedAuthorId;

      await tester.pumpWidget(
        buildTestableWidget(
          child: ProductDetailScreen(
            productId: 'prod-123',
            notifier: notifier,
          ),
          routes: {
            AppRouter.profile: (context) {
              receivedAuthorId = ModalRoute.of(context)?.settings.arguments as String?;
              return const Scaffold(body: Text('UserProfileScreen Destino'));
            },
          },
        ),
      );

      await notifier.loadProduct();
      await tester.pumpAndSettle();

      await tester.scrollUntilVisible(find.text('Ana Barista'), 200);
      await tester.pumpAndSettle();
      await tester.tap(find.text('Ana Barista'));
      await tester.pumpAndSettle();

      expect(find.text('UserProfileScreen Destino'), findsOneWidget);
      expect(receivedAuthorId, 'user-barista');
    });

    testWidgets('exibe estado amigável quando produto ainda não tem avaliações', (tester) async {
      setLargeSurface(tester);
      repository.statsToReturn = const TargetStats(
        targetId: 'prod-123',
        averageRating: 0.0,
        reviewsCount: 0,
      );
      repository.reviewsPageToReturn = const TargetReviewsPage(
        reviews: [],
        pageNumber: 0,
        pageSize: 10,
        totalElements: 0,
        totalPages: 0,
        isLast: true,
      );

      final notifier = ProductDetailNotifier(
        repository: repository,
        productId: 'prod-123',
      );

      await tester.pumpWidget(
        buildTestableWidget(
          child: ProductDetailScreen(
            productId: 'prod-123',
            notifier: notifier,
          ),
        ),
      );

      await notifier.loadProduct();
      await tester.pumpAndSettle();

      expect(find.text('Sem avaliações ainda'), findsOneWidget);
      expect(find.text('Nenhuma avaliação ainda'), findsOneWidget);
      expect(
        find.text('Seja a primeira pessoa a compartilhar sua experiência sobre este produto!'),
        findsOneWidget,
      );
    });
  });
}
