import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rewit_mobile/app/router/app_router.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/features/feed/domain/entities/feed_entities.dart';
import 'package:rewit_mobile/features/feed/presentation/widgets/review_card.dart';
import 'package:rewit_mobile/features/place/domain/entities/place_detail.dart';
import 'package:rewit_mobile/features/place/domain/entities/target_reviews_page.dart';
import 'package:rewit_mobile/features/place/domain/entities/target_stats.dart';
import 'package:rewit_mobile/features/place/domain/repositories/place_repository.dart';
import 'package:rewit_mobile/features/place/presentation/screens/place_detail_screen.dart';
import 'package:rewit_mobile/features/place/presentation/state/place_detail_notifier.dart';
import 'dart:typed_data';
import 'package:rewit_mobile/features/product/domain/entities/product_detail.dart';
import 'package:rewit_mobile/features/product/domain/entities/product_identifier.dart';
import 'package:rewit_mobile/features/product/domain/entities/products_in_place_page.dart';
import 'package:rewit_mobile/features/product/domain/repositories/product_repository.dart';
import 'package:rewit_mobile/shared/widgets/error_view.dart';
import 'package:rewit_mobile/shared/widgets/loading_indicator.dart';

class _FakeProductRepoForPlace implements ProductRepository {
  @override
  Future<ProductsInPlacePage> getProductsInPlace(
    String placeId, {
    int page = 0,
    int size = 20,
  }) async {
    return const ProductsInPlacePage(
      products: [
        ProductDetail(
          id: 'prod-croissant',
          name: 'Croissant de Amêndoas',
          brand: 'Padaria Modelo',
          category: 'PADARIA',
          status: 'ACTIVE',
        ),
      ],
      pageNumber: 0,
      pageSize: 20,
      totalElements: 1,
      totalPages: 1,
      isLast: true,
    );
  }

  @override
  Future<ProductDetail> getProductById(String id) async => throw UnimplementedError();
  @override
  Future<List<ProductIdentifier>> getProductIdentifiers(String id) async => throw UnimplementedError();
  @override
  Future<ProductDetail> getProductByIdentifier({required String type, required String value}) async => throw UnimplementedError();
  @override
  Future<TargetStats> getProductStats(String id) async => throw UnimplementedError();
  @override
  Future<TargetReviewsPage> getProductReviews(String productId, {int page = 0, int size = 10, String sort = 'newest', bool verifiedOnly = false}) async => throw UnimplementedError();
  @override
  Future<Uint8List> getProductImageBytes(String imageUrl) async => throw UnimplementedError();
}

class _FakePlaceRepository implements PlaceRepository {
  PlaceDetail? placeToReturn;
  TargetStats? statsToReturn;
  TargetReviewsPage? reviewsPageToReturn;
  Exception? placeException;

  @override
  Future<PlaceDetail> getPlaceById(String id) async {
    if (placeException != null) throw placeException!;
    return placeToReturn ??
        const PlaceDetail(
          id: 'place-123',
          name: 'Padaria Modelo',
          slug: 'padaria-modelo',
          category: 'PADARIA',
          description: 'Pães artesanais e café fresco todas as manhãs.',
          addressText: 'Rua das Palmeiras',
          streetNumber: '456',
          neighborhood: 'Batel',
          city: 'Curitiba',
          state: 'PR',
          latitude: -25.438,
          longitude: -49.282,
          validationRadiusMeters: 80,
          origin: 'USER',
          isVerified: true,
          status: 'ACTIVE',
        );
  }

  @override
  Future<TargetStats> getTargetStats(String id) async {
    return statsToReturn ??
        const TargetStats(
          targetId: 'place-123',
          averageRating: 4.8,
          reviewsCount: 25,
        );
  }

  @override
  Future<TargetReviewsPage> getTargetReviews(
    String targetId, {
    int page = 0,
    int size = 10,
    String sort = 'newest',
    bool verifiedOnly = false,
  }) async {
    return reviewsPageToReturn ??
        TargetReviewsPage(
          reviews: [
            FeedReview(
              id: 'rev-model-1',
              author: const FeedAuthor(
                id: 'user-author-1',
                displayName: 'Carlos Souza',
                handle: 'carlos_souza',
              ),
              experienceText: 'O melhor croissant da cidade!',
              visibility: 'PUBLIC',
              status: 'ACTIVE',
              createdAt: DateTime(2026, 10, 7),
              targets: const [
                FeedTarget(
                  id: 't-1',
                  targetId: 'place-123',
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

  group('PlaceDetailScreen Widget Tests', () {
    late _FakePlaceRepository repository;

    setUp(() {
      repository = _FakePlaceRepository();
    });

    testWidgets('exibe indicador de carregamento durante a busca inicial', (tester) async {
      final notifier = PlaceDetailNotifier(
        repository: repository,
        placeId: 'place-123',
      );

      await tester.pumpWidget(
        buildTestableWidget(
          child: PlaceDetailScreen(
            placeId: 'place-123',
            notifier: notifier,
          ),
        ),
      );

      expect(find.byType(LoadingIndicator), findsOneWidget);
      expect(find.text('Carregando detalhes do local...'), findsOneWidget);
    });

    testWidgets('exibe estado de Local indisponível quando a API retorna 404', (tester) async {
      repository.placeException = const ApiException(
        ProblemDetail(
          type: 'https://api.rewit.com/errors/place-not-found',
          status: 404,
          title: 'Not Found',
          detail: 'Local não encontrado',
          code: 'PLACE_NOT_FOUND',
        ),
      );

      final notifier = PlaceDetailNotifier(
        repository: repository,
        placeId: 'place-unknown',
      );

      await tester.pumpWidget(
        buildTestableWidget(
          child: PlaceDetailScreen(
            placeId: 'place-unknown',
            notifier: notifier,
          ),
        ),
      );

      await notifier.loadPlace();
      await tester.pumpAndSettle();

      expect(find.text('Local indisponível'), findsOneWidget);
      expect(
        find.text('Este local não foi encontrado ou não está mais ativo na comunidade.'),
        findsOneWidget,
      );
      expect(find.byIcon(Icons.location_off_outlined), findsOneWidget);
    });

    testWidgets('exibe ErrorView quando ocorre erro genérico de rede', (tester) async {
      repository.placeException = Exception('Falha de conexão');

      final notifier = PlaceDetailNotifier(
        repository: repository,
        placeId: 'place-err',
      );

      await tester.pumpWidget(
        buildTestableWidget(
          child: PlaceDetailScreen(
            placeId: 'place-err',
            notifier: notifier,
          ),
        ),
      );

      await notifier.loadPlace();
      await tester.pumpAndSettle();

      expect(find.byType(ErrorView), findsOneWidget);
      expect(find.text('Não foi possível carregar o local'), findsOneWidget);
    });

    testWidgets('exibe detalhes completos do local, estatísticas, endereço e botão Avaliar', (tester) async {
      final notifier = PlaceDetailNotifier(
        repository: repository,
        placeId: 'place-123',
      );

      await tester.pumpWidget(
        buildTestableWidget(
          child: PlaceDetailScreen(
            placeId: 'place-123',
            notifier: notifier,
          ),
        ),
      );

      await notifier.loadPlace();
      await tester.pumpAndSettle();

      // Nome e Categoria
      expect(find.text('Padaria Modelo'), findsOneWidget);
      expect(find.text('PADARIA'), findsOneWidget);
      expect(find.text('Verificado'), findsOneWidget);

      // Estatísticas
      expect(find.text('4.8'), findsOneWidget);
      expect(find.text('(25 avaliações)'), findsOneWidget);

      // Botão Avaliar este local
      expect(find.text('Avaliar este local'), findsOneWidget);

      // Informações Físicas
      expect(find.text('Pães artesanais e café fresco todas as manhãs.'), findsOneWidget);
      expect(find.text('Rua das Palmeiras, 456 - Batel - Curitiba, PR'), findsOneWidget);
      expect(find.text('Raio de validação presencial: 80 metros'), findsOneWidget);

      // Lista de Avaliações
      expect(find.byType(ReviewCard), findsOneWidget);
      expect(find.text('O melhor croissant da cidade!'), findsOneWidget);
    });

    testWidgets('exibe estado vazio amigável quando o local ainda não tem avaliações', (tester) async {
      repository.statsToReturn = const TargetStats(
        targetId: 'place-123',
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

      final notifier = PlaceDetailNotifier(
        repository: repository,
        placeId: 'place-123',
      );

      await tester.pumpWidget(
        buildTestableWidget(
          child: PlaceDetailScreen(
            placeId: 'place-123',
            notifier: notifier,
          ),
        ),
      );

      await notifier.loadPlace();
      await tester.pumpAndSettle();

      expect(find.text('Sem avaliações ainda'), findsOneWidget);
      expect(find.text('Nenhuma avaliação ainda'), findsOneWidget);
      expect(
        find.text('Seja a primeira pessoa a compartilhar sua experiência sobre este local!'),
        findsOneWidget,
      );
    });

    testWidgets('botão Avaliar este local navega para AppRouter.reviewCreate com parâmetros do local', (tester) async {
      final notifier = PlaceDetailNotifier(
        repository: repository,
        placeId: 'place-123',
      );

      Object? receivedArguments;

      await tester.pumpWidget(
        buildTestableWidget(
          child: PlaceDetailScreen(
            placeId: 'place-123',
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

      await notifier.loadPlace();
      await tester.pumpAndSettle();

      final evaluateButton = find.widgetWithText(ElevatedButton, 'Avaliar este local');
      expect(evaluateButton, findsOneWidget);

      await tester.tap(evaluateButton);
      await tester.pumpAndSettle();

      expect(find.text('ReviewCreateScreen Destino'), findsOneWidget);
      expect(receivedArguments, isA<Map<String, dynamic>>());
      final argsMap = receivedArguments as Map<String, dynamic>;
      expect(argsMap['targetId'], 'place-123');
      expect(argsMap['targetName'], 'Padaria Modelo');
      expect(argsMap['targetType'], 'PLACE');
      expect(argsMap['category'], 'PADARIA');
    });

    testWidgets('toque no ReviewCard navega para AppRouter.reviewDetail', (tester) async {
      final notifier = PlaceDetailNotifier(
        repository: repository,
        placeId: 'place-123',
      );

      FeedReview? receivedReview;

      await tester.pumpWidget(
        buildTestableWidget(
          child: PlaceDetailScreen(
            placeId: 'place-123',
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

      await notifier.loadPlace();
      await tester.pumpAndSettle();

      // Rola até o card da avaliação e clica
      await tester.scrollUntilVisible(find.text('O melhor croissant da cidade!'), 200);
      await tester.pumpAndSettle();
      await tester.tap(find.text('O melhor croissant da cidade!'));
      await tester.pumpAndSettle();

      expect(find.text('ReviewDetailScreen Destino'), findsOneWidget);
      expect(receivedReview?.id, 'rev-model-1');
    });

    testWidgets('toque no autor da avaliação navega para AppRouter.profile', (tester) async {
      final notifier = PlaceDetailNotifier(
        repository: repository,
        placeId: 'place-123',
      );

      String? receivedAuthorId;

      await tester.pumpWidget(
        buildTestableWidget(
          child: PlaceDetailScreen(
            placeId: 'place-123',
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

      await notifier.loadPlace();
      await tester.pumpAndSettle();

      // Rola até o autor da avaliação e clica
      await tester.scrollUntilVisible(find.text('Carlos Souza'), 200);
      await tester.pumpAndSettle();
      await tester.tap(find.text('Carlos Souza'));
      await tester.pumpAndSettle();

      expect(find.text('UserProfileScreen Destino'), findsOneWidget);
      expect(receivedAuthorId, 'user-author-1');
    });

    testWidgets('exibe produtos disponíveis no local e navega para AppRouter.productDetail com contextPlace ao tocar', (tester) async {
      final notifier = PlaceDetailNotifier(
        repository: repository,
        placeId: 'place-123',
      );

      final fakeProductRepo = _FakeProductRepoForPlace();
      Object? receivedProductArgs;

      await tester.pumpWidget(
        buildTestableWidget(
          child: PlaceDetailScreen(
            placeId: 'place-123',
            notifier: notifier,
            productRepository: fakeProductRepo,
          ),
          routes: {
            AppRouter.productDetail: (context) {
              receivedProductArgs = ModalRoute.of(context)?.settings.arguments;
              return const Scaffold(body: Text('ProductDetailScreen Destino'));
            },
          },
        ),
      );

      await notifier.loadPlace();
      await tester.pumpAndSettle();

      expect(find.text('Produtos neste local'), findsOneWidget);
      expect(find.textContaining('Croissant de Amêndoas'), findsOneWidget);

      await tester.tap(find.textContaining('Croissant de Amêndoas'));
      await tester.pumpAndSettle();

      expect(find.text('ProductDetailScreen Destino'), findsOneWidget);
      expect(receivedProductArgs, isA<Map<String, dynamic>>());
      final argsMap = receivedProductArgs as Map<String, dynamic>;
      expect(argsMap['productId'], 'prod-croissant');
      expect(argsMap['contextPlace'], isA<PlaceDetail>());
    });
  });
}
