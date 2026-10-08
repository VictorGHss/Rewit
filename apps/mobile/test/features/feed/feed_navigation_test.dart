import 'dart:typed_data';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rewit_mobile/app/router/app_router.dart';
import 'package:rewit_mobile/app/theme/app_theme.dart';
import 'package:rewit_mobile/features/auth/data/models/auth_user_dto.dart';
import 'package:rewit_mobile/features/auth/domain/entities/auth_state.dart';
import 'package:rewit_mobile/features/auth/domain/repositories/auth_repository.dart';
import 'package:rewit_mobile/features/auth/presentation/state/auth_notifier.dart';
import 'package:rewit_mobile/features/discussions/domain/entities/discussion_entities.dart';
import 'package:rewit_mobile/features/discussions/domain/repositories/discussion_repository.dart';
import 'package:rewit_mobile/features/feed/domain/entities/feed_entities.dart';
import 'package:rewit_mobile/features/feed/domain/repositories/feed_repository.dart';
import 'package:rewit_mobile/features/feed/presentation/state/feed_notifier.dart';
import 'package:rewit_mobile/features/feed/presentation/widgets/review_card.dart';
import 'package:rewit_mobile/features/place/domain/entities/place_detail.dart';
import 'package:rewit_mobile/features/place/domain/entities/target_reviews_page.dart';
import 'package:rewit_mobile/features/place/domain/entities/target_stats.dart';
import 'package:rewit_mobile/features/place/domain/repositories/place_repository.dart';
import 'package:rewit_mobile/features/product/domain/entities/product_detail.dart';
import 'package:rewit_mobile/features/product/domain/entities/product_identifier.dart';
import 'package:rewit_mobile/features/product/domain/entities/products_in_place_page.dart';
import 'package:rewit_mobile/features/product/domain/repositories/product_repository.dart';
import 'package:rewit_mobile/features/review_detail/domain/entities/helpful_result.dart';
import 'package:rewit_mobile/features/review_detail/domain/entities/review_media.dart';
import 'package:rewit_mobile/features/review_detail/domain/entities/update_review_input.dart';
import 'package:rewit_mobile/features/review_detail/presentation/screens/review_detail_screen.dart';

class MockAuthRepo implements AuthRepository {
  @override
  Future<bool> hasStoredSession() async => true;

  @override
  Future<AuthUserDto> getMe() async => const AuthUserDto(
        id: 'usr-nav-1',
        email: 'user@test.com',
        handle: 'navuser',
        displayName: 'Navegador Teste',
        reputationScore: 50,
      );

  @override
  Future<Authenticated> login({required String email, required String password}) async =>
      throw UnimplementedError();

  @override
  Future<Authenticated> refreshTokens() async => throw UnimplementedError();

  @override
  Future<void> logout() async {}

  @override
  Future<Authenticated> reactivate({required String email, required String password}) async =>
      throw UnimplementedError();

  @override
  Future<void> deactivateAccount() async {}
}

class MockFeedRepo implements FeedRepository {
  int getFeedCallCount = 0;

  final FeedReview sampleReview = FeedReview(
    id: 'rev-det-1',
    author: const FeedAuthor(
      id: 'author-1',
      handle: 'joaosilva',
      displayName: 'João Silva',
      isAnonymous: false,
    ),
    experienceText: 'Excelente hambúrguer artesanal!',
    visibility: 'PUBLIC',
    status: 'ACTIVE',
    isAnonymous: false,
    isVerifiedOnSite: true,
    helpfulCount: 7,
    isHelpfulByMe: false,
    targets: [
      FeedTarget(
        id: 't-1',
        targetId: 'place-burgers',
        targetType: 'PLACE',
        rating: 4.9,
        specificComment: 'Carne no ponto perfeito',
        createdAt: DateTime(2026, 10, 6),
      ),
      FeedTarget(
        id: 't-2',
        targetId: 'prod-milkshake',
        targetType: 'PRODUCT',
        rating: 4.5,
        specificComment: 'Super cremoso',
        createdAt: DateTime(2026, 10, 6),
      ),
    ],
    createdAt: DateTime(2026, 10, 6, 18, 0),
  );

  @override
  Future<FeedPage> getFeed({int page = 0, int size = 10}) async {
    getFeedCallCount++;
    return FeedPage(
      items: [sampleReview],
      page: 0,
      size: 10,
      windowSize: 1,
      totalPages: 1,
    );
  }

  @override
  Future<FeedReview> getReviewById(String reviewId) async {
    return sampleReview;
  }

  @override
  Future<HelpfulResult> toggleHelpful(String reviewId, {required bool currentlyHelpful}) async {
    return HelpfulResult(helpful: !currentlyHelpful, helpfulCount: currentlyHelpful ? 6 : 8);
  }

  @override
  Future<List<ReviewMediaItem>> getReviewMedia(String reviewId) async {
    return [];
  }

  @override
  Future<FeedReview> updateReview(String reviewId, UpdateReviewInput input) async {
    return sampleReview;
  }

  @override
  Future<void> deleteReview(String reviewId) async {}
}


class MockDiscussionRepo implements DiscussionRepository {
  @override
  Future<DiscussionPage> getDiscussions(String reviewId, {int page = 0, int size = 20}) async {
    return const DiscussionPage(
      content: [],
      pageNumber: 0,
      pageSize: 20,
      totalElements: 0,
      totalPages: 0,
      isLast: true,
    );
  }

  @override
  Future<DiscussionRepliesPage> getReplies(String discussionId, {int page = 0, int size = 20}) async {
    return const DiscussionRepliesPage(
      content: [],
      pageNumber: 0,
      pageSize: 20,
      totalElements: 0,
      totalPages: 0,
      isLast: true,
    );
  }

  @override
  Future<DiscussionItem> createDiscussion({
    required String reviewId,
    required String content,
    String? parentId,
  }) async {
    throw UnimplementedError();
  }

  @override
  Future<void> deleteDiscussion(String discussionId) async {}

  @override
  Future<String> reportDiscussion({
    required String discussionId,
    required ReportReason reason,
    String? detail,
  }) async {
    return 'Denúncia recebida. Obrigado por ajudar a manter a comunidade segura.';
  }
}

class MockPlaceRepo implements PlaceRepository {
  @override
  Future<PlaceDetail> getPlaceById(String id) async => const PlaceDetail(
        id: 'place-burgers',
        name: 'Burger Place',
        slug: 'burger-place',
        category: 'RESTAURANTE',
        addressText: 'Rua das Flores',
        city: 'Curitiba',
        state: 'PR',
        latitude: -25.43,
        longitude: -49.27,
        validationRadiusMeters: 50,
        origin: 'USER',
        isVerified: true,
        status: 'ACTIVE',
      );

  @override
  Future<TargetStats> getTargetStats(String id) async => const TargetStats(
        targetId: 'place-burgers',
        reviewsCount: 1,
        averageRating: 4.9,
      );

  @override
  Future<TargetReviewsPage> getTargetReviews(
    String targetId, {
    int page = 0,
    int size = 10,
    String sort = 'newest',
    bool verifiedOnly = false,
  }) async =>
      const TargetReviewsPage(
        reviews: [],
        pageNumber: 0,
        pageSize: 10,
        totalPages: 1,
        totalElements: 0,
        isLast: true,
      );
}

class MockProductRepo implements ProductRepository {
  @override
  Future<ProductDetail> getProductById(String id) async => const ProductDetail(
        id: 'prod-milkshake',
        name: 'Milkshake Especial',
        brand: 'Burger Queen',
        category: 'BEBIDA',
        status: 'ACTIVE',
      );

  @override
  Future<List<ProductIdentifier>> getProductIdentifiers(String id) async => [];

  @override
  Future<ProductDetail> getProductByIdentifier({required String type, required String value}) async =>
      throw UnimplementedError();

  @override
  Future<TargetStats> getProductStats(String id) async => const TargetStats(
        targetId: 'prod-milkshake',
        reviewsCount: 1,
        averageRating: 4.5,
      );

  @override
  Future<TargetReviewsPage> getProductReviews(
    String productId, {
    int page = 0,
    int size = 10,
    String sort = 'newest',
    bool verifiedOnly = false,
  }) async =>
      const TargetReviewsPage(
        reviews: [],
        pageNumber: 0,
        pageSize: 10,
        totalPages: 1,
        totalElements: 0,
        isLast: true,
      );

  @override
  Future<ProductsInPlacePage> getProductsInPlace(
    String placeId, {
    int page = 0,
    int size = 20,
  }) async =>
      const ProductsInPlacePage(
        products: [],
        pageNumber: 0,
        pageSize: 20,
        totalElements: 0,
        totalPages: 0,
        isLast: true,
      );

  @override
  Future<Uint8List> getProductImageBytes(String imageUrl) async => Uint8List(0);
}

class MockNavigatorObserver extends NavigatorObserver {
  final List<Route<dynamic>> pushedRoutes = [];

  @override
  void didPush(Route<dynamic> route, Route<dynamic>? previousRoute) {
    pushedRoutes.add(route);
    super.didPush(route, previousRoute);
  }
}

void main() {
  group('Feed Navigation to Detail Tests', () {
    late MockAuthRepo authRepo;
    late MockFeedRepo feedRepo;
    late MockDiscussionRepo discussionRepo;
    late MockPlaceRepo placeRepo;
    late MockProductRepo productRepo;
    late AuthNotifier authNotifier;
    late FeedNotifier feedNotifier;
    late AppRouter router;
    late MockNavigatorObserver navObserver;

    setUp(() async {
      authRepo = MockAuthRepo();
      feedRepo = MockFeedRepo();
      discussionRepo = MockDiscussionRepo();
      placeRepo = MockPlaceRepo();
      productRepo = MockProductRepo();
      authNotifier = AuthNotifier(authRepository: authRepo);
      feedNotifier = FeedNotifier(feedRepository: feedRepo);
      navObserver = MockNavigatorObserver();
      await authNotifier.checkAuthStatus();

      router = AppRouter(
        authNotifier: authNotifier,
        feedNotifier: feedNotifier,
        feedRepository: feedRepo,
        discussionRepository: discussionRepo,
        placeRepository: placeRepo,
        productRepository: productRepo,
      );
    });

    testWidgets('exibe FeedView e navega para ReviewDetailScreen ao clicar no ReviewCard', (tester) async {
      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.lightTheme,
          initialRoute: AppRouter.root,
          onGenerateRoute: router.onGenerateRoute,
          navigatorObservers: [navObserver],
        ),
      );

      // Espera inicializar FeedView e carregar items
      await tester.pump();
      await tester.pumpAndSettle();

      // Confirma que está no HomeScreen com o review carregado
      expect(find.text('João Silva'), findsOneWidget);
      expect(find.text('Excelente hambúrguer artesanal!'), findsOneWidget);
      expect(find.byType(ReviewCard), findsOneWidget);

      // Clica no card de review
      await tester.tap(find.byType(ReviewCard));
      await tester.pumpAndSettle();

      // Confirma que navegou para a tela ReviewDetailScreen com seções completas
      expect(find.byType(ReviewDetailScreen), findsOneWidget);
      expect(find.text('Alvos da Avaliação'), findsOneWidget);
      expect(find.text('Carne no ponto perfeito'), findsOneWidget);
      expect(find.text('7 pessoas acharam útil'), findsOneWidget);
      expect(find.text('Comentários da Comunidade'), findsOneWidget);
    });

    testWidgets('navega para PlaceDetailScreen com targetId ao tocar no chip de PLACE', (tester) async {
      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.lightTheme,
          initialRoute: AppRouter.root,
          onGenerateRoute: router.onGenerateRoute,
          navigatorObservers: [navObserver],
        ),
      );

      await tester.pump();
      await tester.pumpAndSettle();

      expect(find.text('Local • 4.9'), findsOneWidget);

      await tester.tap(find.text('Local • 4.9'));
      await tester.pumpAndSettle();

      final lastRoute = navObserver.pushedRoutes.last;
      expect(lastRoute.settings.name, AppRouter.placeDetail);
      expect(lastRoute.settings.arguments, 'place-burgers');
    });

    testWidgets('navega para ProductDetailScreen com targetId ao tocar no chip de PRODUCT', (tester) async {
      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.lightTheme,
          initialRoute: AppRouter.root,
          onGenerateRoute: router.onGenerateRoute,
          navigatorObservers: [navObserver],
        ),
      );

      await tester.pump();
      await tester.pumpAndSettle();

      expect(find.text('Produto • 4.5'), findsOneWidget);

      await tester.tap(find.text('Produto • 4.5'));
      await tester.pumpAndSettle();

      final lastRoute = navObserver.pushedRoutes.last;
      expect(lastRoute.settings.name, AppRouter.productDetail);
      expect(lastRoute.settings.arguments, 'prod-milkshake');
    });

    testWidgets('alterna voto útil diretamente no feed e atualiza contador', (tester) async {
      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.lightTheme,
          initialRoute: AppRouter.root,
          onGenerateRoute: router.onGenerateRoute,
          navigatorObservers: [navObserver],
        ),
      );

      await tester.pump();
      await tester.pumpAndSettle();

      expect(find.text('7 úteis'), findsOneWidget);

      await tester.tap(find.text('7 úteis'));
      await tester.pumpAndSettle();

      expect(find.text('8 úteis'), findsOneWidget);
      expect(find.byIcon(Icons.thumb_up), findsOneWidget);
    });

    testWidgets('retorno de tela de detalhe preserva estado do feed sem re-fetch desnecessário', (tester) async {
      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.lightTheme,
          initialRoute: AppRouter.root,
          onGenerateRoute: router.onGenerateRoute,
          navigatorObservers: [navObserver],
        ),
      );

      await tester.pump();
      await tester.pumpAndSettle();

      expect(feedRepo.getFeedCallCount, 1);
      expect(find.text('João Silva'), findsOneWidget);

      // Navega para ReviewDetailScreen
      await tester.tap(find.byType(ReviewCard));
      await tester.pumpAndSettle();

      expect(find.byType(ReviewDetailScreen), findsOneWidget);

      // Retorna para o feed usando pop do Navigator
      Navigator.of(tester.element(find.byType(ReviewDetailScreen))).pop();
      await tester.pumpAndSettle();

      // Confirma que voltou ao HomeScreen e não realizou re-fetch no backend
      expect(find.byType(ReviewDetailScreen), findsNothing);
      expect(find.text('João Silva'), findsOneWidget);
      expect(feedRepo.getFeedCallCount, 1); // preservado sem re-fetch!
    });
  });
}
