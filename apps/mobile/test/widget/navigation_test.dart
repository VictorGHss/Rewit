import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rewit_mobile/app/app.dart';
import 'package:rewit_mobile/app/config/app_config.dart';
import 'package:rewit_mobile/app/router/app_router.dart';
import 'package:rewit_mobile/app/theme/app_theme.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/features/auth/data/models/auth_tokens.dart';
import 'package:rewit_mobile/features/auth/data/models/auth_user_dto.dart';
import 'package:rewit_mobile/features/auth/domain/entities/auth_state.dart';
import 'package:rewit_mobile/features/auth/domain/repositories/auth_repository.dart';
import 'package:rewit_mobile/features/auth/domain/services/social_sign_in_service.dart';
import 'package:rewit_mobile/features/auth/presentation/screens/login_screen.dart';
import 'package:rewit_mobile/features/auth/presentation/state/auth_notifier.dart';
import 'package:rewit_mobile/features/check_ins/domain/entities/check_in_entities.dart';
import 'package:rewit_mobile/features/check_ins/domain/repositories/check_in_repository.dart';
import 'package:rewit_mobile/features/collection/presentation/screens/collection_screen.dart';
import 'package:rewit_mobile/features/feed/domain/entities/feed_entities.dart';
import 'package:rewit_mobile/features/feed/domain/repositories/feed_repository.dart';
import 'package:rewit_mobile/features/feed/presentation/state/feed_notifier.dart';
import 'package:rewit_mobile/features/feed/presentation/state/feed_state.dart';
import 'package:rewit_mobile/features/interests/domain/entities/user_interests.dart';
import 'package:rewit_mobile/features/interests/domain/repositories/interest_repository.dart';
import 'package:rewit_mobile/features/notifications/domain/entities/notifications_page.dart';
import 'package:rewit_mobile/features/notifications/domain/repositories/notification_repository.dart';
import 'package:rewit_mobile/features/profile/domain/entities/user_profile.dart';
import 'package:rewit_mobile/features/profile/domain/repositories/user_profile_repository.dart';
import 'package:rewit_mobile/features/profile/presentation/screens/account_settings_screen.dart';
import 'package:rewit_mobile/features/profile/presentation/screens/user_profile_screen.dart';
import 'package:rewit_mobile/features/rankings/domain/entities/ranking_entities.dart';
import 'package:rewit_mobile/features/rankings/domain/repositories/ranking_repository.dart';
import 'package:rewit_mobile/features/review_creation/domain/entities/review_creation_input.dart';
import 'package:rewit_mobile/features/review_creation/domain/repositories/review_creation_repository.dart';
import 'package:rewit_mobile/features/review_detail/domain/entities/helpful_result.dart';
import 'package:rewit_mobile/features/review_detail/domain/entities/review_media.dart';
import 'package:rewit_mobile/features/review_detail/domain/entities/update_review_input.dart';

class StubNotificationRepo implements NotificationRepository {
  @override
  Future<NotificationsPage> getNotifications({int page = 0, int size = 20}) async =>
      const NotificationsPage(
        items: [],
        pageNumber: 0,
        pageSize: 20,
        totalElements: 0,
        totalPages: 0,
        isLast: true,
      );

  @override
  Future<int> getUnreadCount() async => 3;

  @override
  Future<void> markAsRead(String notificationId) async {}

  @override
  Future<void> markAllAsRead() async {}
}

class StubAuthRepo implements AuthRepository {
  bool logoutCalled = false;
  bool failLogin = false;
  String? avatarUrl;

  StubAuthRepo({this.avatarUrl});

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

  @override
  Future<bool> hasStoredSession() async => true;

  @override
  Future<AuthUserDto> getMe() async => AuthUserDto(
        id: 'user-1',
        email: 'ana@rewit.com',
        handle: 'anapaula',
        displayName: 'Ana Paula',
        avatarUrl: avatarUrl,
        isVerified: true,
        reputationScore: 120,
      );

  @override
  Future<Authenticated> login({required String email, required String password}) async {
    if (failLogin) {
      throw const ApiException(
        ProblemDetail(
          type: 'about:blank',
          title: 'Credenciais inválidas',
          status: 401,
          detail: 'Credenciais inválidas.',
          code: 'INVALID_CREDENTIALS',
        ),
      );
    }
    return Authenticated(
      user: await getMe(),
      tokens: const AuthTokens(accessToken: 'a', refreshToken: 'r'),
    );
  }

  @override
  Future<Authenticated> refreshTokens() async => throw UnimplementedError();

  @override
  Future<void> logout() async {
    logoutCalled = true;
  }

  @override
  Future<Authenticated> reactivate({required String email, required String password}) async =>
      throw UnimplementedError();

  @override
  Future<void> deactivateAccount() async {}

  @override
  Future<void> changePassword({
    required String currentPassword,
    required String newPassword,
  }) async {}
}

class StubDualFeedRepo implements FeedRepository {
  int forYouCalls = 0;
  int followingCalls = 0;

  @override
  Future<FeedPage> getFeed({int page = 0, int size = 10}) async {
    forYouCalls++;
    return FeedPage(
      items: [
        FeedReview(
          id: 'rev-foryou-$page',
          author: const FeedAuthor(
            id: 'a-1',
            handle: 'autor_foryou',
            displayName: 'Autor Para Você',
          ),
          experienceText: 'Avaliação recomendada no Para você',
          visibility: 'PUBLIC',
          status: 'ACTIVE',
          createdAt: DateTime.utc(2026, 10, 27),
        ),
      ],
      page: page,
      size: size,
      windowSize: 1,
      totalPages: 1,
    );
  }

  @override
  Future<FeedPage> getFollowingFeed({int page = 0, int size = 10}) async {
    followingCalls++;
    return FeedPage(
      items: [
        FeedReview(
          id: 'rev-following-$page',
          author: const FeedAuthor(
            id: 'a-2',
            handle: 'autor_seguindo',
            displayName: 'Autor Seguindo',
          ),
          experienceText: 'Avaliação cronológica de quem você segue',
          visibility: 'PUBLIC',
          status: 'ACTIVE',
          createdAt: DateTime.utc(2026, 10, 27),
        ),
      ],
      page: page,
      size: size,
      windowSize: 1,
      totalPages: 1,
    );
  }

  @override
  Future<FeedReview> getReviewById(String reviewId) => throw UnimplementedError();

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

class StubRankingRepo implements RankingRepository {
  @override
  Future<bool> getParticipation() async => false;

  @override
  Future<bool> updateParticipation(bool showInRankings) async => showInRankings;

  @override
  Future<RankingPage> getRanking(
    RankingMetric metric,
    RankingFilters filters, {
    int page = 0,
    int size = 20,
  }) async =>
      const RankingPage(entries: [], pageNumber: 0, totalElements: 0, isLast: true);
}

class StubCheckInRepo implements CheckInRepository {
  @override
  Future<List<NearbyPlace>> getNearbyPlaces({
    required double latitude,
    required double longitude,
  }) async =>
      const [];

  @override
  Future<MyCheckInsPage> getMyCheckIns({int page = 0, int size = 20}) async =>
      const MyCheckInsPage(items: [], pageNumber: 0, totalElements: 0, isLast: true);
}

class StubReviewCreationRepo implements ReviewCreationRepository {
  @override
  Future<FeedReview> createReview(CreateReviewInput input) => throw UnimplementedError();
}

class StubUserProfileRepo implements UserProfileRepository {
  final String? avatarUrl;

  StubUserProfileRepo({this.avatarUrl});

  @override
  Future<UserProfile> getUserProfile(String userId) async => UserProfile(
        id: userId,
        handle: 'anapaula',
        displayName: 'Ana Paula',
        avatarUrl: avatarUrl,
        stats: const UserStats(totalReviews: 2, verifiedReviewsCount: 1),
      );

  @override
  dynamic noSuchMethod(Invocation invocation) => throw UnimplementedError();
}

class StubInterestRepo implements InterestRepository {
  @override
  Future<InterestCatalog> getCatalog() async => const InterestCatalog(
        minInterestsToComplete: 3,
        questions: [
          InterestQuestion(
            code: 'Q1',
            title: 'Comer e beber',
            question: 'O que você curte?',
            options: [
              InterestOption(code: 'CAFE', label: 'Cafeterias'),
              InterestOption(code: 'RESTAURANT', label: 'Restaurantes'),
              InterestOption(code: 'BAKERY', label: 'Padarias'),
            ],
          ),
        ],
      );

  @override
  Future<UserInterests> getMyInterests() async => const UserInterests(
        status: InterestOnboardingStatus.completed,
        interests: [
          InterestOption(code: 'CAFE', label: 'Cafeterias'),
          InterestOption(code: 'RESTAURANT', label: 'Restaurantes'),
          InterestOption(code: 'BAKERY', label: 'Padarias'),
        ],
      );

  @override
  Future<UserInterests> saveMyInterests({
    required List<String> codes,
    required bool completeOnboarding,
  }) async =>
      const UserInterests(
        status: InterestOnboardingStatus.completed,
        interests: [
          InterestOption(code: 'CAFE', label: 'Cafeterias'),
          InterestOption(code: 'RESTAURANT', label: 'Restaurantes'),
          InterestOption(code: 'BAKERY', label: 'Padarias'),
        ],
      );
}

void main() {
  group('AuthUserDto avatarUrl serialization & copyWith', () {
    test('parseia, normaliza, serializa e faz copyWith de avatarUrl opcional', () {
      final withoutAvatar = AuthUserDto.fromJson({
        'id': 'u-1',
        'email': 'ana@rewit.com',
        'handle': 'anapaula',
        'displayName': 'Ana Paula',
        'avatarUrl': '   ',
      });
      expect(withoutAvatar.avatarUrl, isNull);
      expect(withoutAvatar.toJson()['avatarUrl'], isNull);

      final withAvatar = AuthUserDto.fromJson({
        'id': 'u-1',
        'email': 'ana@rewit.com',
        'handle': 'anapaula',
        'displayName': 'Ana Paula',
        'avatarUrl': ' https://cdn.rewit.app/avatars/ana.jpg ',
      });
      expect(withAvatar.avatarUrl, 'https://cdn.rewit.app/avatars/ana.jpg');
      expect(withAvatar.toJson()['avatarUrl'], 'https://cdn.rewit.app/avatars/ana.jpg');

      final updated = withoutAvatar.copyWith(avatarUrl: 'https://cdn.rewit.app/a2.jpg');
      expect(updated.avatarUrl, 'https://cdn.rewit.app/a2.jpg');

      final cleared = updated.copyWith(clearAvatarUrl: true);
      expect(cleared.avatarUrl, isNull);
    });
  });

  group('HomeScreen V2 & Navigation Widget Tests', () {
    late StubAuthRepo repo;
    late AuthNotifier notifier;

    setUp(() async {
      repo = StubAuthRepo();
      notifier = AuthNotifier(authRepository: repo);
      await notifier.checkAuthStatus();
    });

    Widget buildSubject(
      AuthNotifier authNotifier, {
      FeedNotifier? feedNotifier,
    }) {
      final router = AppRouter(
        authNotifier: authNotifier,
        feedNotifier: feedNotifier,
        rankingRepository: StubRankingRepo(),
        checkInRepository: StubCheckInRepo(),
        reviewCreationRepository: StubReviewCreationRepo(),
      );
      return MaterialApp(
        theme: AppTheme.lightTheme,
        initialRoute: AppRouter.root,
        onGenerateRoute: router.onGenerateRoute,
      );
    }

    testWidgets('cabeçalho V2 exibe inicial no avatar sem cartão antigo de usuário nem hero de rankings',
        (tester) async {
      await tester.pumpWidget(buildSubject(notifier));
      await tester.pumpAndSettle();

      // Avatar na AppBar com fallback pela inicial "A" de "Ana Paula"
      expect(find.byKey(const Key('home_avatar_button')), findsOneWidget);
      expect(find.byKey(const Key('home_avatar_fallback_initial')), findsOneWidget);
      expect(find.text('A'), findsOneWidget);

      // Antigo cartão de usuário e hero card de rankings foram removidos da Home
      expect(find.byKey(const Key('home_user_header_card')), findsNothing);
      expect(find.byKey(const Key('home_rankings_entry')), findsNothing);
      expect(find.textContaining('Reputação'), findsNothing);
      expect(find.text('Feed de Atividades'), findsOneWidget);
    });

    testWidgets('sincroniza avatarUrl quando o perfil do usuário autenticado é carregado', (tester) async {
      final router = AppRouter(
        authNotifier: notifier,
        userProfileRepository: StubUserProfileRepo(
          avatarUrl: 'https://cdn.rewit.app/avatars/ana.png',
        ),
      );

      await tester.pumpWidget(MaterialApp(
        theme: AppTheme.lightTheme,
        initialRoute: AppRouter.root,
        onGenerateRoute: router.onGenerateRoute,
      ));
      await tester.pumpAndSettle();

      expect((notifier.state as Authenticated).user.avatarUrl, isNull);

      // Abre o Perfil pelo avatar da AppBar
      await tester.tap(find.byKey(const Key('home_avatar_button')));
      await tester.pumpAndSettle();

      expect(find.byType(UserProfileScreen), findsOneWidget);
      // UserProfileScreen sincronizou o avatarUrl na sessão autenticada
      expect(
        (notifier.state as Authenticated).user.avatarUrl,
        'https://cdn.rewit.app/avatars/ana.png',
      );
    });

    testWidgets('barra inferior tem 5 destinos (Início, Explorar, Rankings, Check-in, Coleção) sem AppBars duplicadas',
        (tester) async {
      await tester.pumpWidget(buildSubject(notifier));
      await tester.pumpAndSettle();

      final labels = tester
          .widgetList<NavigationDestination>(find.byType(NavigationDestination))
          .map((d) => d.label)
          .toList();
      expect(labels, ['Início', 'Explorar', 'Rankings', 'Check-in', 'Coleção']);

      // 1. Início: apenas 1 AppBar
      expect(find.byType(AppBar), findsOneWidget);

      // 2. Explorar: busca global e atalho para o scanner, sem AppBar duplicada
      await tester.tap(find.text('Explorar'));
      await tester.pumpAndSettle();
      expect(find.byType(AppBar), findsOneWidget);
      expect(find.text('Busca de Lugares'), findsOneWidget);
      expect(find.byKey(const Key('search_scanner_button')), findsOneWidget);

      // 3. Rankings: embutido sem AppBar duplicada
      await tester.tap(find.text('Rankings'));
      await tester.pumpAndSettle();
      expect(find.byType(AppBar), findsOneWidget);
      expect(find.byKey(const Key('rankings_state_required')), findsOneWidget);

      // 4. Check-in: embutido com botão de histórico preservado e sem AppBar duplicada
      await tester.tap(find.text('Check-in'));
      await tester.pumpAndSettle();
      expect(find.byType(AppBar), findsOneWidget);
      expect(find.byKey(const Key('checkin_history_button')), findsOneWidget);
      expect(find.byKey(const Key('checkin_locate_button')), findsOneWidget);

      // 5. Coleção: estado vazio honesto, sem subtabs falsas e sem AppBar duplicada
      await tester.tap(find.text('Coleção'));
      await tester.pumpAndSettle();
      expect(find.byType(AppBar), findsOneWidget);
      expect(find.byType(CollectionScreen), findsOneWidget);
      expect(find.byKey(const Key('collection_empty_state')), findsOneWidget);
      expect(find.text('Sua Coleção em breve'), findsOneWidget);

      // Volta para Início
      await tester.tap(find.text('Início'));
      await tester.pumpAndSettle();
      expect(find.text('Feed de Atividades'), findsOneWidget);
    });

    testWidgets('botão flutuante + abre bottom sheet com Nova Avaliação e Fazer Check-in (sem Publicar nem Escanear)',
        (tester) async {
      await tester.pumpWidget(buildSubject(notifier));
      await tester.pumpAndSettle();

      final fab = find.byKey(const Key('home_fab_button'));
      expect(fab, findsOneWidget);

      await tester.tap(fab);
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('home_quick_action_review_create')), findsOneWidget);
      expect(find.byKey(const Key('home_quick_action_check_in')), findsOneWidget);
      expect(find.text('Nova Avaliação'), findsOneWidget);
      expect(find.text('Fazer Check-in'), findsOneWidget);
      expect(find.textContaining('Publicar'), findsNothing);
      expect(find.textContaining('Escanear'), findsNothing);

      // Tocar em "Fazer Check-in" fecha o sheet e seleciona a aba Check-in (índice 3)
      await tester.tap(find.byKey(const Key('home_quick_action_check_in')));
      await tester.pumpAndSettle();
      expect(tester.widget<NavigationBar>(find.byType(NavigationBar)).selectedIndex, 3);

      // O FAB continua visível e "Nova Avaliação" abre a rota AppRouter.reviewCreate
      await tester.tap(find.byKey(const Key('home_fab_button')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('home_quick_action_review_create')));
      await tester.pumpAndSettle();

      expect(find.text('Criar Avaliação'), findsOneWidget);
    });

    testWidgets('Feed dual: Para você carrega /api/v2/feed inicialmente e Seguindo só carrega ao selecionar a aba',
        (tester) async {
      final feedRepo = StubDualFeedRepo();
      final feedNotifier = FeedNotifier(feedRepository: feedRepo);
      addTearDown(feedNotifier.dispose);

      await tester.pumpWidget(buildSubject(notifier, feedNotifier: feedNotifier));
      await tester.pump();
      await tester.pumpAndSettle();

      // Apenas Para você foi carregado na montagem inicial; Seguindo permanece em FeedInitial
      expect(feedRepo.forYouCalls, 1);
      expect(feedRepo.followingCalls, 0);
      expect(feedNotifier.activeFeedType, FeedType.forYou);
      expect(find.text('Autor Para Você'), findsOneWidget);
      expect(find.text('Autor Seguindo'), findsNothing);

      // Seleciona a aba "Seguindo"
      await tester.tap(find.byKey(const Key('home_feed_tab_following')));
      await tester.pump();
      await tester.pumpAndSettle();

      expect(feedRepo.forYouCalls, 1);
      expect(feedRepo.followingCalls, 1);
      expect(feedNotifier.activeFeedType, FeedType.following);
      expect(find.text('Autor Seguindo'), findsOneWidget);

      // Volta para "Para você": estado preservado sem re-fetch
      await tester.tap(find.byKey(const Key('home_feed_tab_for_you')));
      await tester.pumpAndSettle();

      expect(feedRepo.forYouCalls, 1);
      expect(feedRepo.followingCalls, 1);
      expect(feedNotifier.activeFeedType, FeedType.forYou);
      expect(find.text('Autor Para Você'), findsOneWidget);
    });

    testWidgets('salvar interesses em AppRouter.editInterests atualiza especificamente FeedType.forYou',
        (tester) async {
      final feedRepo = StubDualFeedRepo();
      final feedNotifier = FeedNotifier(feedRepository: feedRepo);
      addTearDown(feedNotifier.dispose);

      final router = AppRouter(
        authNotifier: notifier,
        feedNotifier: feedNotifier,
        interestRepository: StubInterestRepo(),
      );

      await tester.pumpWidget(MaterialApp(
        theme: AppTheme.lightTheme,
        initialRoute: AppRouter.root,
        onGenerateRoute: router.onGenerateRoute,
      ));
      await tester.pump();
      await tester.pumpAndSettle();

      expect(feedRepo.forYouCalls, 1);
      // Muda a aba ativa para Seguindo antes de editar interesses
      await tester.tap(find.byKey(const Key('home_feed_tab_following')));
      await tester.pumpAndSettle();
      expect(feedRepo.followingCalls, 1);

      // Abre Configurações da Conta -> Meus interesses
      await tester.tap(find.byKey(const Key('home_avatar_button')));
      await tester.pumpAndSettle();
      await tester.tap(find.byIcon(Icons.settings_outlined));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('edit_interests_tile')));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('edit_interests_save')));
      await tester.pumpAndSettle();

      // Apenas FeedType.forYou foi atualizado após salvar interesses
      expect(feedRepo.forYouCalls, 2);
      expect(feedRepo.followingCalls, 1);
    });

    testWidgets('logout em AccountSettingsScreen limpa pilha privada até o Login; falha de login não limpa pilha',
        (tester) async {
      final router = AppRouter(authNotifier: notifier);

      await tester.pumpWidget(RewitApp(
        config: const AppConfig(apiBaseUrl: 'https://api.rewit.test'),
        router: router,
      ));
      await tester.pumpAndSettle();

      // Empilha Perfil -> Configurações da Conta
      await tester.tap(find.byKey(const Key('home_avatar_button')));
      await tester.pumpAndSettle();
      expect(find.byType(UserProfileScreen), findsOneWidget);

      await tester.tap(find.byIcon(Icons.settings_outlined));
      await tester.pumpAndSettle();
      expect(find.byType(AccountSettingsScreen), findsOneWidget);

      // Aciona Sair da Conta com diálogo de confirmação
      final logoutBtn = find.byKey(const Key('logout_account_button'));
      await tester.ensureVisible(logoutBtn);
      await tester.tap(logoutBtn);
      await tester.pumpAndSettle();

      expect(find.text('Encerrar Sessão'), findsOneWidget);
      expect(find.text('Deseja realmente sair da sua conta?'), findsOneWidget);

      await tester.tap(find.widgetWithText(ElevatedButton, 'Sair'));
      await tester.pumpAndSettle();

      expect(repo.logoutCalled, isTrue);
      expect(notifier.isAuthenticated, isFalse);
      // Voltou ao Login sem deixar UserProfileScreen ou AccountSettingsScreen empilhadas
      expect(find.byType(LoginScreen), findsOneWidget);
      expect(find.byType(AccountSettingsScreen), findsNothing);
      expect(find.byType(UserProfileScreen), findsNothing);

      // Falha normal de login (prevUserId == null -> userId == null) não limpa rotas empilhadas sobre o Login
      repo.failLogin = true;
      Navigator.of(tester.element(find.byType(LoginScreen))).push(
        MaterialPageRoute<void>(
          builder: (_) => const Scaffold(body: Text('Tela Auxiliar Sobre Login')),
        ),
      );
      await tester.pumpAndSettle();
      expect(find.text('Tela Auxiliar Sobre Login'), findsOneWidget);

      await notifier.login('ana@rewit.com', 'errada');
      await tester.pumpAndSettle();
      expect(find.text('Tela Auxiliar Sobre Login'), findsOneWidget);
    });

    testWidgets('AppRouter renderiza LoginScreen para usuário desautenticado e HomeScreen para autenticado',
        (tester) async {
      final unauthNotifier = AuthNotifier(authRepository: repo);
      await unauthNotifier.checkAuthStatus();
      await unauthNotifier.logout();

      final router = AppRouter(authNotifier: unauthNotifier);

      await tester.pumpWidget(MaterialApp(
        key: const ValueKey('unauth-app'),
        theme: AppTheme.lightTheme,
        initialRoute: AppRouter.root,
        onGenerateRoute: router.onGenerateRoute,
      ));
      await tester.pumpAndSettle();

      expect(find.text('Rewit'), findsOneWidget);
      expect(find.widgetWithText(ElevatedButton, 'Entrar'), findsOneWidget);

      final authNotifier = AuthNotifier(authRepository: repo);
      await authNotifier.checkAuthStatus();
      final authRouter = AppRouter(authNotifier: authNotifier);

      await tester.pumpWidget(MaterialApp(
        key: const ValueKey('auth-app'),
        theme: AppTheme.lightTheme,
        initialRoute: AppRouter.root,
        onGenerateRoute: authRouter.onGenerateRoute,
      ));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('home_avatar_button')), findsOneWidget);
      expect(find.byKey(const Key('home_fab_button')), findsOneWidget);
    });

    testWidgets('exibe ícone de notificações com badge de não lidas no AppBar da HomeScreen e navega para Notificações',
        (tester) async {
      final notifRepo = StubNotificationRepo();
      final authNotifier = AuthNotifier(authRepository: repo);
      await authNotifier.checkAuthStatus();
      final router = AppRouter(
        authNotifier: authNotifier,
        notificationRepository: notifRepo,
      );

      await tester.pumpWidget(MaterialApp(
        theme: AppTheme.lightTheme,
        initialRoute: AppRouter.root,
        onGenerateRoute: router.onGenerateRoute,
      ));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('home_notifications_button')), findsOneWidget);
      expect(find.text('3'), findsOneWidget);

      await tester.tap(find.byKey(const Key('home_notifications_button')));
      await tester.pumpAndSettle();

      expect(find.text('Notificações'), findsOneWidget);
      expect(find.text('Nenhuma notificação por enquanto'), findsOneWidget);
    });
  });
}
