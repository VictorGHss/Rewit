import 'dart:typed_data';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rewit_mobile/app/theme/app_theme.dart';
import 'package:rewit_mobile/app/theme/theme_controller.dart';
import 'package:rewit_mobile/features/auth/data/models/auth_user_dto.dart';
import 'package:rewit_mobile/features/auth/domain/entities/auth_state.dart';
import 'package:rewit_mobile/features/auth/domain/repositories/auth_repository.dart';
import 'package:rewit_mobile/features/auth/domain/services/social_sign_in_service.dart';
import 'package:rewit_mobile/features/auth/presentation/state/auth_notifier.dart';
import 'package:rewit_mobile/features/check_ins/domain/entities/check_in_entities.dart';
import 'package:rewit_mobile/features/check_ins/domain/repositories/check_in_repository.dart';
import 'package:rewit_mobile/features/check_ins/presentation/screens/check_in_screen.dart';
import 'package:rewit_mobile/features/feed/domain/entities/feed_entities.dart';
import 'package:rewit_mobile/features/feed/domain/repositories/feed_repository.dart';
import 'package:rewit_mobile/features/feed/presentation/state/feed_notifier.dart';
import 'package:rewit_mobile/features/home/presentation/screens/home_screen.dart';
import 'package:rewit_mobile/features/notifications/domain/entities/in_app_notification.dart';
import 'package:rewit_mobile/features/notifications/domain/entities/notifications_page.dart';
import 'package:rewit_mobile/features/notifications/domain/repositories/notification_repository.dart';
import 'package:rewit_mobile/features/notifications/presentation/screens/notifications_screen.dart';
import 'package:rewit_mobile/features/place/domain/entities/target_reviews_page.dart';
import 'package:rewit_mobile/features/place/domain/entities/target_stats.dart';
import 'package:rewit_mobile/features/product/domain/entities/create_product_input.dart';
import 'package:rewit_mobile/features/product/domain/entities/product_detail.dart';
import 'package:rewit_mobile/features/product/domain/entities/product_identifier.dart';
import 'package:rewit_mobile/features/product/domain/entities/products_in_place_page.dart';
import 'package:rewit_mobile/features/product/domain/repositories/product_repository.dart';
import 'package:rewit_mobile/features/profile/domain/entities/user_profile.dart';
import 'package:rewit_mobile/features/profile/presentation/screens/account_settings_screen.dart';
import 'package:rewit_mobile/features/profile/presentation/screens/user_profile_screen.dart';
import 'package:rewit_mobile/features/rankings/domain/entities/ranking_entities.dart';
import 'package:rewit_mobile/features/rankings/domain/repositories/ranking_repository.dart';
import 'package:rewit_mobile/features/rankings/presentation/screens/rankings_screen.dart';
import 'package:rewit_mobile/features/review_creation/domain/entities/review_creation_input.dart';
import 'package:rewit_mobile/features/review_creation/domain/repositories/review_creation_repository.dart';
import 'package:rewit_mobile/features/review_detail/domain/entities/helpful_result.dart';
import 'package:rewit_mobile/features/review_detail/domain/entities/review_media.dart';
import 'package:rewit_mobile/features/review_detail/domain/entities/update_review_input.dart';
import 'package:rewit_mobile/features/review_detail/presentation/screens/review_detail_screen.dart';
import 'package:rewit_mobile/features/scanner/presentation/screens/scanner_screen.dart';
import 'package:rewit_mobile/features/scanner/presentation/state/scanner_notifier.dart';
import 'package:rewit_mobile/features/search/domain/entities/search_entities.dart';
import 'package:rewit_mobile/features/search/domain/repositories/search_repository.dart';
import 'package:rewit_mobile/features/search/presentation/screens/search_screen.dart';
import 'package:rewit_mobile/shared/widgets/app_button.dart';
import 'package:rewit_mobile/shared/widgets/app_text_field.dart';
import 'package:rewit_mobile/shared/widgets/error_banner.dart';
import 'package:rewit_mobile/shared/widgets/error_view.dart';
import 'package:rewit_mobile/shared/widgets/loading_indicator.dart';

double _contrast(Color a, Color b) {
  final la = a.computeLuminance();
  final lb = b.computeLuminance();
  final lighter = la > lb ? la : lb;
  final darker = la > lb ? lb : la;
  return (lighter + 0.05) / (darker + 0.05);
}

class _StubAuthRepo implements AuthRepository {
  @override
  Future<bool> hasStoredSession() async => true;

  @override
  Future<AuthUserDto> getMe() async => const AuthUserDto(
        id: 'u-1',
        email: 'clara@rewit.com',
        handle: 'clarasilva',
        displayName: 'Clara Silva',
        isVerified: true,
        reputationScore: 80,
      );

  @override
  Future<Authenticated> login({required String email, required String password}) =>
      throw UnimplementedError();

  @override
  Future<Authenticated> refreshTokens() => throw UnimplementedError();

  @override
  Future<void> logout() async {}

  @override
  Future<void> deactivateAccount() async {}

  @override
  Future<Authenticated> reactivate({required String email, required String password}) =>
      throw UnimplementedError();

  @override
  Future<void> changePassword({required String currentPassword, required String newPassword}) async {}

  @override
  Future<Authenticated> register({
    required String displayName,
    required String handle,
    required String email,
    required String password,
  }) =>
      throw UnimplementedError();

  @override
  Future<String> requestSocialNonce() => throw UnimplementedError();

  @override
  Future<Authenticated> signInWithSocial({
    required SocialAuthProvider provider,
    required String idToken,
    required String nonce,
  }) =>
      throw UnimplementedError();

  @override
  Future<Authenticated> reactivateWithSocial({
    required SocialAuthProvider provider,
    required String idToken,
    required String nonce,
  }) =>
      throw UnimplementedError();
}

class _StubFeedRepo implements FeedRepository {
  final FeedReview sampleReview = FeedReview(
    id: 'rev-1',
    author: const FeedAuthor(
      id: 'u-2',
      handle: 'lucas',
      displayName: 'Lucas Andrade',
    ),
    experienceText: 'Ótimo atendimento e pão artesanal fresquinho.',
    isVerifiedOnSite: true,
    visibility: 'PUBLIC',
    status: 'ACTIVE',
    createdAt: DateTime.utc(2026, 10, 27, 10, 0),
    helpfulCount: 4,
    targets: const [
      FeedTarget(
        id: 't-1',
        targetId: 'p-1',
        rating: 4.5,
        targetType: 'PLACE',
        targetName: 'Padaria Artesanal',
      ),
    ],
  );

  @override
  Future<FeedPage> getFeed({int page = 0, int size = 10}) async => FeedPage(
        items: [sampleReview],
        page: 0,
        size: 10,
        windowSize: 1,
        totalPages: 1,
      );

  @override
  Future<FeedReview> getReviewById(String reviewId) async => sampleReview;

  @override
  Future<HelpfulResult> toggleHelpful(String reviewId, {required bool currentlyHelpful}) =>
      throw UnimplementedError();

  @override
  Future<List<ReviewMediaItem>> getReviewMedia(String reviewId) async => const [];

  @override
  Future<FeedReview> updateReview(String reviewId, UpdateReviewInput input) =>
      throw UnimplementedError();

  @override
  Future<void> deleteReview(String reviewId) => throw UnimplementedError();
}

class _StubSearchRepo implements SearchRepository {
  @override
  Future<SearchPage> search({required String query, int page = 0, int size = 20}) async =>
      const SearchPage(
        items: [
          SearchResultItem(
            id: 'p-1',
            name: 'Padaria Artesanal',
            slug: 'padaria-artesanal',
            category: 'PADARIA',
            targetType: TargetType.place,
            rawTargetType: 'PLACE',
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

class _StubRankingRepo implements RankingRepository {
  @override
  Future<bool> getParticipation() async => true;

  @override
  Future<bool> updateParticipation(bool showInRankings) async => showInRankings;

  @override
  Future<RankingPage> getRanking(
    RankingMetric metric,
    RankingFilters filters, {
    int page = 0,
    int size = 20,
  }) async =>
      const RankingPage(
        entries: [
          RankingEntry(
            rankPosition: 1,
            user: RankingUser(
              id: 'u-1',
              handle: 'clarasilva',
              displayName: 'Clara Silva',
            ),
            score: 7,
            isCurrentUser: true,
          ),
        ],
        pageNumber: 0,
        totalElements: 1,
        isLast: true,
      );
}

class _StubCheckInRepo implements CheckInRepository {
  @override
  Future<List<NearbyPlace>> getNearbyPlaces({
    required double latitude,
    required double longitude,
  }) async =>
      const [];

  @override
  Future<MyCheckInsPage> getMyCheckIns({int page = 0, int size = 20}) async =>
      const MyCheckInsPage(
        items: [],
        pageNumber: 0,
        totalElements: 0,
        isLast: true,
      );
}

class _StubReviewCreationRepo implements ReviewCreationRepository {
  @override
  Future<FeedReview> createReview(CreateReviewInput input) => throw UnimplementedError();
}

class _StubNotificationRepo implements NotificationRepository {
  @override
  Future<NotificationsPage> getNotifications({int page = 0, int size = 20}) async =>
      NotificationsPage(
        items: [
          InAppNotification(
            id: 'notif-1',
            type: NotificationType.reviewHelpful,
            reviewId: 'rev-1',
            createdAt: DateTime.utc(2026, 10, 27, 11, 0),
          ),
        ],
        pageNumber: 0,
        pageSize: 20,
        totalElements: 1,
        totalPages: 1,
        isLast: true,
      );

  @override
  Future<int> getUnreadCount() async => 1;

  @override
  Future<void> markAsRead(String notificationId) async {}

  @override
  Future<void> markAllAsRead() async {}
}

class _StubProductRepo implements ProductRepository {
  @override
  Future<ProductDetail> getProductByIdentifier({
    required String type,
    required String value,
  }) async =>
      const ProductDetail(
        id: 'prod-1',
        name: 'Café Torrado Especial',
        brand: 'Serra Sul',
        model: '250g',
        category: 'BEBIDAS',
        status: 'ACTIVE',
      );

  @override
  Future<ProductDetail> createProduct(CreateProductInput input) => throw UnimplementedError();

  @override
  Future<ProductDetail> getProductById(String id) => throw UnimplementedError();

  @override
  Future<List<ProductIdentifier>> getProductIdentifiers(String id) => throw UnimplementedError();

  @override
  Future<ProductsInPlacePage> getProductsInPlace(String placeId, {int page = 0, int size = 20}) =>
      throw UnimplementedError();

  @override
  Future<TargetReviewsPage> getProductReviews(
    String productId, {
    int page = 0,
    int size = 10,
    String sort = 'newest',
    bool verifiedOnly = false,
  }) =>
      throw UnimplementedError();

  @override
  Future<TargetStats> getProductStats(String id) => throw UnimplementedError();

  @override
  Future<Uint8List> getProductImageBytes(String imageUrl) async => Uint8List(0);
}

Future<void> _verifyScreenInTheme({
  required WidgetTester tester,
  required String screenName,
  required Widget screen,
  required ThemeData theme,
}) async {
  final isDark = theme.brightness == Brightness.dark;
  final themeLabel = isDark ? 'escuro' : 'claro';

  await tester.pumpWidget(
    MaterialApp(
      themeAnimationDuration: Duration.zero,
      theme: theme,
      home: screen,
    ),
  );
  await tester.pump();
  await tester.pump(const Duration(milliseconds: 50));

  expect(
    tester.takeException(),
    isNull,
    reason: 'Nenhuma exceção de renderização/layout em $screenName ($themeLabel)',
  );

  if (isDark) {
    final cards = tester.widgetList<Card>(find.byType(Card));
    for (final card in cards) {
      final resolvedColor = card.color ?? theme.cardTheme.color!;
      expect(
        resolvedColor,
        isNot(Colors.white),
        reason: '$screenName não pode ter Card branco no modo escuro',
      );
      expect(
        resolvedColor.computeLuminance(),
        lessThan(0.05),
        reason: '$screenName deve manter superfícies escuras em Cards',
      );
    }
  }
}

void main() {
  group('9. Componentes compartilhados nos temas claro e escuro', () {
    for (final entry in {
      'claro': AppTheme.lightTheme,
      'escuro': AppTheme.darkTheme,
    }.entries) {
      testWidgets('AppButton (preenchido e outlined em loading) mantém spinner legível no tema ${entry.key}',
          (tester) async {
        final theme = entry.value;

        await tester.pumpWidget(
          MaterialApp(
            themeAnimationDuration: Duration.zero,
            theme: theme,
            home: Scaffold(
              body: Column(
                children: [
                  AppButton(
                    key: const Key('filled_loading_btn'),
                    label: 'Salvar',
                    isLoading: true,
                    onPressed: () {},
                  ),
                  AppButton(
                    key: const Key('outlined_loading_btn'),
                    label: 'Cancelar',
                    isOutlined: true,
                    isLoading: true,
                    onPressed: () {},
                  ),
                ],
              ),
            ),
          ),
        );

        final outlinedProgress = tester.widget<CircularProgressIndicator>(
          find.descendant(
            of: find.byKey(const Key('outlined_loading_btn')),
            matching: find.byType(CircularProgressIndicator),
          ),
        );
        final outlinedSpinnerColor =
            (outlinedProgress.valueColor as AlwaysStoppedAnimation<Color>).value;

        // Corrige regressão antiga: variante outlined não pode usar Colors.white fixo invisível no modo claro
        expect(outlinedSpinnerColor, isNot(Colors.white));
        expect(
          _contrast(outlinedSpinnerColor, theme.scaffoldBackgroundColor),
          greaterThanOrEqualTo(4.5),
          reason: 'Spinner de AppButton outlined precisa de contraste >= 4.5:1 no tema ${entry.key}',
        );
        expect(
          _contrast(outlinedSpinnerColor, theme.cardTheme.color!),
          greaterThanOrEqualTo(4.5),
        );

        final filledProgress = tester.widget<CircularProgressIndicator>(
          find.descendant(
            of: find.byKey(const Key('filled_loading_btn')),
            matching: find.byType(CircularProgressIndicator),
          ),
        );
        final filledSpinnerColor =
            (filledProgress.valueColor as AlwaysStoppedAnimation<Color>).value;
        final filledBg = theme.elevatedButtonTheme.style!.backgroundColor!
            .resolve(const <WidgetState>{})!;
        expect(_contrast(filledSpinnerColor, filledBg), greaterThanOrEqualTo(4.5));
      });

      testWidgets('AppTextField, ErrorBanner, ErrorView e LoadingIndicator renderizam no tema ${entry.key}',
          (tester) async {
        final theme = entry.value;
        final textController = TextEditingController();
        var retried = false;

        await tester.pumpWidget(
          MaterialApp(
            themeAnimationDuration: Duration.zero,
            theme: theme,
            home: Scaffold(
              body: SingleChildScrollView(
                child: Column(
                  children: [
                    AppTextField(
                      controller: textController,
                      labelText: 'E-mail',
                      hintText: 'voce@exemplo.com',
                    ),
                    const ErrorBanner(
                      message: 'Falha temporária de conexão.',
                      retryAfterSeconds: 5,
                    ),
                    const ErrorBanner(
                      message: 'Alterações salvas com sucesso.',
                      isSuccess: true,
                    ),
                    ErrorView(
                      title: 'Serviço indisponível',
                      message: 'Tente novamente em instantes.',
                      onRetry: () => retried = true,
                    ),
                    const LoadingIndicator(message: 'Carregando dados...'),
                  ],
                ),
              ),
            ),
          ),
        );

        expect(find.text('E-mail'), findsOneWidget);
        expect(find.text('Falha temporária de conexão.'), findsOneWidget);
        expect(find.text('Aguarde 5 segundos antes de tentar novamente.'), findsOneWidget);
        expect(find.text('Alterações salvas com sucesso.'), findsOneWidget);
        expect(find.text('Carregando dados...'), findsOneWidget);

        await tester.tap(find.text('Tentar novamente'));
        expect(retried, isTrue);
        expect(tester.takeException(), isNull);
        textController.dispose();
      });
    }
  });

  group('10. Regressão visual e estrutural das telas principais em claro e escuro', () {
    for (final entry in {
      'claro': AppTheme.lightTheme,
      'escuro': AppTheme.darkTheme,
    }.entries) {
      final themeLabel = entry.key;
      final theme = entry.value;

      testWidgets('HomeScreen e ReviewCard no tema $themeLabel', (tester) async {
        final authNotifier = AuthNotifier(authRepository: _StubAuthRepo());
        await authNotifier.checkAuthStatus();
        final feedRepo = _StubFeedRepo();
        final feedNotifier = FeedNotifier(feedRepository: feedRepo);
        await feedNotifier.loadInitial();

        await _verifyScreenInTheme(
          tester: tester,
          screenName: 'HomeScreen',
          screen: HomeScreen(
            authNotifier: authNotifier,
            feedNotifier: feedNotifier,
            searchRepository: _StubSearchRepo(),
            notificationRepository: _StubNotificationRepo(),
          ),
          theme: theme,
        );

        expect(find.text('Lucas Andrade'), findsOneWidget);
        expect(find.text('Ótimo atendimento e pão artesanal fresquinho.'), findsOneWidget);
        expect(find.text('Local • 4.5'), findsOneWidget);

        feedNotifier.dispose();
        authNotifier.dispose();
      });

      testWidgets('RankingsScreen no tema $themeLabel', (tester) async {
        final authNotifier = AuthNotifier(authRepository: _StubAuthRepo());
        await authNotifier.checkAuthStatus();

        await _verifyScreenInTheme(
          tester: tester,
          screenName: 'RankingsScreen',
          screen: RankingsScreen(
            repository: _StubRankingRepo(),
            authNotifier: authNotifier,
          ),
          theme: theme,
        );

        authNotifier.dispose();
      });

      testWidgets('SearchScreen e SearchResultCard no tema $themeLabel', (tester) async {
        final authNotifier = AuthNotifier(authRepository: _StubAuthRepo());
        await authNotifier.checkAuthStatus();

        await _verifyScreenInTheme(
          tester: tester,
          screenName: 'SearchScreen',
          screen: SearchScreen(
            searchRepository: _StubSearchRepo(),
            authNotifier: authNotifier,
          ),
          theme: theme,
        );

        authNotifier.dispose();
      });

      testWidgets('CheckInScreen no tema $themeLabel', (tester) async {
        await _verifyScreenInTheme(
          tester: tester,
          screenName: 'CheckInScreen',
          screen: CheckInScreen(
            reviewCreationRepository: _StubReviewCreationRepo(),
            locationService: null,
            checkInRepository: _StubCheckInRepo(),
          ),
          theme: theme,
        );
      });

      testWidgets('UserProfileScreen no tema $themeLabel', (tester) async {
        final authNotifier = AuthNotifier(authRepository: _StubAuthRepo());
        await authNotifier.checkAuthStatus();

        await _verifyScreenInTheme(
          tester: tester,
          screenName: 'UserProfileScreen',
          screen: UserProfileScreen(
            authNotifier: authNotifier,
            initialProfile: const UserProfile(
              id: 'u-1',
              handle: 'clarasilva',
              displayName: 'Clara Silva',
              bio: 'Exploradora de cafeterias e livrarias.',
              stats: UserStats(
                totalReviews: 12,
                verifiedReviewsCount: 5,
                followersCount: 34,
                followingCount: 21,
                helpfulVotesReceived: 18,
              ),
            ),
          ),
          theme: theme,
        );

        authNotifier.dispose();
      });

      testWidgets('AccountSettingsScreen no tema $themeLabel', (tester) async {
        final authNotifier = AuthNotifier(authRepository: _StubAuthRepo());
        await authNotifier.checkAuthStatus();

        await _verifyScreenInTheme(
          tester: tester,
          screenName: 'AccountSettingsScreen',
          screen: AccountSettingsScreen(
            authNotifier: authNotifier,
            themeController: ThemeController(storage: InMemoryThemePreferenceStorage()),
          ),
          theme: theme,
        );

        authNotifier.dispose();
      });

      testWidgets('NotificationsScreen no tema $themeLabel', (tester) async {
        await _verifyScreenInTheme(
          tester: tester,
          screenName: 'NotificationsScreen',
          screen: NotificationsScreen(repository: _StubNotificationRepo()),
          theme: theme,
        );
      });

      testWidgets('ReviewDetailScreen no tema $themeLabel', (tester) async {
        final feedRepo = _StubFeedRepo();

        await _verifyScreenInTheme(
          tester: tester,
          screenName: 'ReviewDetailScreen',
          screen: ReviewDetailScreen(
            reviewId: feedRepo.sampleReview.id,
            initialReview: feedRepo.sampleReview,
            feedRepository: feedRepo,
          ),
          theme: theme,
        );
      });

      testWidgets('ScannerScreen e resultado de produto no tema $themeLabel', (tester) async {
        final scannerNotifier = ScannerNotifier(productRepository: _StubProductRepo());

        await _verifyScreenInTheme(
          tester: tester,
          screenName: 'ScannerScreen (scanning)',
          screen: ScannerScreen(
            notifier: scannerNotifier,
            cameraBuilder: (_, __) => const ColoredBox(color: Color(0xFF000000)),
          ),
          theme: theme,
        );

        await scannerNotifier.onCodeDetected('7891234567895');
        await tester.pump();
        expect(find.text('Produto encontrado'), findsOneWidget);
        expect(find.textContaining('Café Torrado Especial'), findsOneWidget);
        expect(tester.takeException(), isNull);

        scannerNotifier.dispose();
      });
    }
  });
}