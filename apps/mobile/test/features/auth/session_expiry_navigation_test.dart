import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:rewit_mobile/app/app.dart';
import 'package:rewit_mobile/app/config/app_config.dart';
import 'package:rewit_mobile/app/router/app_router.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/core/network/http_client.dart';
import 'package:rewit_mobile/core/network/session_refresher.dart';
import 'package:rewit_mobile/core/network/token_refreshing_client.dart';
import 'package:rewit_mobile/core/storage/session_token_coordinator.dart';
import 'package:rewit_mobile/core/storage/token_storage.dart';
import 'package:rewit_mobile/features/auth/data/models/auth_tokens.dart';
import 'package:rewit_mobile/features/auth/data/models/auth_user_dto.dart';
import 'package:rewit_mobile/features/auth/data/repositories/auth_repository_impl.dart';
import 'package:rewit_mobile/features/auth/domain/entities/auth_state.dart';
import 'package:rewit_mobile/features/auth/domain/repositories/auth_repository.dart';
import 'package:rewit_mobile/features/auth/domain/services/social_sign_in_service.dart';
import 'package:rewit_mobile/features/auth/presentation/screens/login_screen.dart';
import 'package:rewit_mobile/features/auth/presentation/state/auth_notifier.dart';
import 'package:rewit_mobile/features/home/presentation/screens/home_screen.dart';
import 'package:rewit_mobile/features/profile/presentation/screens/account_settings_screen.dart';

const _user = AuthUserDto(id: 'u1', email: 'ana@rewit.test', handle: 'ana', displayName: 'Ana');
const _session = Authenticated(user: _user, tokens: AuthTokens(accessToken: 'a', refreshToken: 'r'));

class _FakeAuthRepository implements AuthRepository {
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
  Future<Authenticated> login({required String email, required String password}) async => _session;
  @override
  Future<Authenticated> reactivate({required String email, required String password}) async => _session;
  @override
  Future<void> deactivateAccount() async {}
  @override
  Future<void> changePassword({required String currentPassword, required String newPassword}) async {}
  @override
  Future<Authenticated> refreshTokens() async => _session;
  @override
  Future<AuthUserDto> getMe() async => _user;
  @override
  Future<void> logout() async {}
  @override
  Future<bool> hasStoredSession() async => true;
}

void main() {
  group('AuthNotifier.handleSessionExpired com SessionRefresher', () {
    late int clearCalls;

    Future<AuthNotifier> signedIn(SessionRefreshResult result) async {
      clearCalls = 0;
      final notifier = AuthNotifier(
        authRepository: _FakeAuthRepository(),
        sessionRefresher: SessionRefresher(refreshAccessToken: () async {
          switch (result.outcome) {
            case SessionRefreshOutcome.refreshed:
              return result.accessToken!;
            case SessionRefreshOutcome.rejected:
              return '';
            case SessionRefreshOutcome.unavailable:
              throw StateError('servidor fora do ar');
          }
        }),
        clearLocalSession: () async => clearCalls++,
      );
      await notifier.checkAuthStatus();
      expect(notifier.state, isA<Authenticated>());
      return notifier;
    }

    test('refresh aceito mantém a sessão e não apaga tokens', () async {
      final notifier = await signedIn(const SessionRefreshResult.refreshed('novo'));
      await notifier.handleSessionExpired();
      expect(notifier.state, isA<Authenticated>());
      expect(clearCalls, 0);
    });

    test('refresh recusado apaga os tokens locais e expira a sessão', () async {
      final notifier = await signedIn(const SessionRefreshResult.rejected());
      await notifier.handleSessionExpired();
      expect(notifier.state, isA<Unauthenticated>());
      expect((notifier.state as Unauthenticated).errorCode, 'SESSION_EXPIRED');
      expect(clearCalls, 1);
    });

    test('servidor indisponível não descarta a sessão', () async {
      final notifier = await signedIn(const SessionRefreshResult.unavailable());
      await notifier.handleSessionExpired();
      expect(notifier.state, isA<Authenticated>());
      expect(clearCalls, 0);
    });

    test('fora de uma sessão ativa (verificação ou login em curso) o 401 é tratado pelo próprio fluxo', () async {
      clearCalls = 0;
      final notifier = AuthNotifier(
        authRepository: _FakeAuthRepository(),
        sessionRefresher: SessionRefresher(refreshAccessToken: () async => ''),
        clearLocalSession: () async => clearCalls++,
      );
      await notifier.handleSessionExpired();
      expect(notifier.state, isA<AuthInitial>());
      expect(clearCalls, 0);
    });
  });

  group('Navegação ao expirar a sessão (RewitApp)', () {
    testWidgets('sessão expirada numa tela interna volta direto ao login, sem deixar a tela aberta por cima',
        (tester) async {
      final notifier = AuthNotifier(
        authRepository: _FakeAuthRepository(),
        sessionRefresher: SessionRefresher(refreshAccessToken: () async => ''),
        clearLocalSession: () async {},
      );
      await notifier.checkAuthStatus();

      await tester.pumpWidget(RewitApp(
        config: const AppConfig(apiBaseUrl: 'https://api.rewit.test'),
        router: AppRouter(authNotifier: notifier),
      ));
      await tester.pumpAndSettle();
      expect(find.byType(HomeScreen), findsOneWidget);

      Navigator.of(tester.element(find.byType(HomeScreen))).pushNamed(AppRouter.accountSettings);
      await tester.pumpAndSettle();
      expect(find.byType(AccountSettingsScreen), findsOneWidget);

      await notifier.handleSessionExpired();
      await tester.pumpAndSettle();

      expect(find.byType(AccountSettingsScreen), findsNothing);
      expect(find.byType(LoginScreen), findsOneWidget);
      expect(find.text('Sua sessão expirou. Faça login novamente.'), findsOneWidget);

      // Voltar não reabre a tela anterior por trás do login
      final navigator = tester.state<NavigatorState>(find.byType(Navigator).first);
      expect(navigator.canPop(), isFalse);
    });

    testWidgets('sessão renovada mantém o usuário na tela em que estava', (tester) async {
      final notifier = AuthNotifier(
        authRepository: _FakeAuthRepository(),
        sessionRefresher: SessionRefresher(refreshAccessToken: () async => 'novo'),
        clearLocalSession: () async {},
      );
      await notifier.checkAuthStatus();

      await tester.pumpWidget(RewitApp(
        config: const AppConfig(apiBaseUrl: 'https://api.rewit.test'),
        router: AppRouter(authNotifier: notifier),
      ));
      await tester.pumpAndSettle();
      Navigator.of(tester.element(find.byType(HomeScreen))).pushNamed(AppRouter.accountSettings);
      await tester.pumpAndSettle();

      await notifier.handleSessionExpired();
      await tester.pumpAndSettle();

      expect(find.byType(AccountSettingsScreen), findsOneWidget);
      expect(notifier.state, isA<Authenticated>());
    });
  });

  group('AuthNotifier e ACCOUNT_DISABLED', () {
    test('ACCOUNT_DISABLED limpa tokens sem chamar SessionRefresher, usa mensagem neutra e desabilita reativação social', () async {
      var refreshCalls = 0;
      final storage = InMemoryTokenStorage(
        initialAccessToken: 'access-1',
        initialRefreshToken: 'refresh-1',
      );
      final notifier = AuthNotifier(
        authRepository: _FakeAuthRepository(),
        sessionRefresher: SessionRefresher(
          refreshAccessToken: () async {
            refreshCalls++;
            return 'should-not-run';
          },
        ),
        clearLocalSession: storage.clearTokens,
      );
      await notifier.checkAuthStatus();

      final seqBefore = notifier.sessionCheckSequence;
      await notifier.handleSessionExpired(
        const SessionExpirationDetails(
          errorCode: 'ACCOUNT_DISABLED',
          statusCode: 401,
        ),
      );

      expect(refreshCalls, 0);
      expect(await storage.getAccessToken(), isNull);
      expect(await storage.getRefreshToken(), isNull);
      expect(notifier.state, isA<Unauthenticated>());
      final unauth = notifier.state as Unauthenticated;
      expect(unauth.errorCode, 'ACCOUNT_DISABLED');
      expect(unauth.errorMessage, AuthNotifier.accountDisabledMessage);
      expect(unauth.socialProvider, isNull);
      expect(unauth.canOfferSocialReactivation, isFalse);

      // sessionCheckSince resolve imediatamente como rejected mesmo sem SessionRefresher ter rodado
      final outcome = await notifier.sessionCheckSince(seqBefore);
      expect(outcome, SessionRefreshOutcome.rejected);
    });

    test('ACCOUNT_DISABLED sem SessionRefresher também resolve sessionCheckSince sem travar', () async {
      final storage = InMemoryTokenStorage(
        initialAccessToken: 'access-1',
        initialRefreshToken: 'refresh-1',
      );
      final notifier = AuthNotifier(
        authRepository: _FakeAuthRepository(),
        clearLocalSession: storage.clearTokens,
      );
      await notifier.checkAuthStatus();

      final seqBefore = notifier.sessionCheckSequence;
      await notifier.handleSessionExpired(
        const SessionExpirationDetails(errorCode: 'ACCOUNT_DISABLED', statusCode: 401),
      );

      expect(await storage.getAccessToken(), isNull);
      expect((notifier.state as Unauthenticated).errorCode, 'ACCOUNT_DISABLED');
      expect(
        await notifier.sessionCheckSince(seqBefore),
        SessionRefreshOutcome.rejected,
      );
    });

    test('refresh em curso (refreshed tardio) não sobrescreve ACCOUNT_DISABLED e gravação tardia de tokens é neutralizada', () async {
      final storage = InMemoryTokenStorage(
        initialAccessToken: 'access-1',
        initialRefreshToken: 'refresh-1',
      );
      final refreshCompleter = Completer<String>();
      late final SessionRefresher refresher;
      refresher = SessionRefresher(
        refreshAccessToken: () async {
          final token = await refreshCompleter.future;
          // Simula AuthRepositoryImpl.refreshTokens() gravando novos tokens tardiamente no armazenamento
          await storage.saveTokens(
            accessToken: token,
            refreshToken: 'refresh-tardio',
          );
          return token;
        },
      );

      final notifier = AuthNotifier(
        authRepository: _FakeAuthRepository(),
        sessionRefresher: refresher,
        clearLocalSession: storage.clearTokens,
      );
      await notifier.checkAuthStatus();

      var notifications = 0;
      notifier.addListener(() => notifications++);

      final seqBeforeA = notifier.sessionCheckSequence;
      // 1. Requisição A dispara AUTHENTICATION_REQUIRED e inicia refresh em voo
      final futureA = notifier.handleSessionExpired(
        const SessionExpirationDetails(
          errorCode: 'AUTHENTICATION_REQUIRED',
          statusCode: 401,
        ),
      );
      final checkA = notifier.sessionCheckSince(seqBeforeA);
      expect(checkA, isNotNull);

      // 2. Enquanto refresh A ainda aguarda rede, requisição B recebe ACCOUNT_DISABLED
      await notifier.handleSessionExpired(
        const SessionExpirationDetails(
          errorCode: 'ACCOUNT_DISABLED',
          statusCode: 401,
        ),
      );

      // B encerra imediatamente sem esperar refresh A
      expect(notifier.state, isA<Unauthenticated>());
      expect((notifier.state as Unauthenticated).errorCode, 'ACCOUNT_DISABLED');
      expect(await storage.getAccessToken(), isNull);
      expect(await storage.getRefreshToken(), isNull);
      expect(await checkA, SessionRefreshOutcome.rejected);

      // 3. Refresh A conclui tardiamente gravando tokens no storage
      refreshCompleter.complete('access-tardio');
      await futureA;

      // Estado terminal ACCOUNT_DISABLED permanece e tokens tardios foram neutralizados
      expect(notifier.state, isA<Unauthenticated>());
      expect((notifier.state as Unauthenticated).errorCode, 'ACCOUNT_DISABLED');
      expect(notifier.isAuthenticated, isFalse);
      expect(await storage.getAccessToken(), isNull);
      expect(await storage.getRefreshToken(), isNull);
      expect(notifications, 1);
    });

    test('refresh unavailable concorrente não impede término imediato por ACCOUNT_DISABLED', () async {
      final storage = InMemoryTokenStorage(
        initialAccessToken: 'access-1',
        initialRefreshToken: 'refresh-1',
      );
      final networkGate = Completer<void>();
      final notifier = AuthNotifier(
        authRepository: _FakeAuthRepository(),
        sessionRefresher: SessionRefresher(
          refreshAccessToken: () async {
            await networkGate.future;
            throw StateError('rede indisponível');
          },
        ),
        clearLocalSession: storage.clearTokens,
      );
      await notifier.checkAuthStatus();

      final futureA = notifier.handleSessionExpired(
        const SessionExpirationDetails(
          errorCode: 'AUTHENTICATION_REQUIRED',
          statusCode: 401,
        ),
      );

      await notifier.handleSessionExpired(
        const SessionExpirationDetails(
          errorCode: 'ACCOUNT_DISABLED',
          statusCode: 401,
        ),
      );

      networkGate.complete();
      await futureA;

      expect(notifier.state, isA<Unauthenticated>());
      expect((notifier.state as Unauthenticated).errorCode, 'ACCOUNT_DISABLED');
      expect(await storage.getAccessToken(), isNull);
    });

    test('chamadas simultâneas e repetidas de ACCOUNT_DISABLED são idempotentes', () async {
      var clearCalls = 0;
      final clearGate = Completer<void>();
      final notifier = AuthNotifier(
        authRepository: _FakeAuthRepository(),
        sessionRefresher: SessionRefresher(refreshAccessToken: () async => 'x'),
        clearLocalSession: () async {
          clearCalls++;
          await clearGate.future;
        },
      );
      await notifier.checkAuthStatus();

      var notifications = 0;
      notifier.addListener(() => notifications++);

      const details = SessionExpirationDetails(
        errorCode: 'ACCOUNT_DISABLED',
        statusCode: 401,
      );
      final first = notifier.handleSessionExpired(details);
      final second = notifier.handleSessionExpired(details);
      clearGate.complete();
      await Future.wait([first, second]);

      // Chamada repetida após já estar desautenticado com ACCOUNT_DISABLED
      await notifier.handleSessionExpired(details);

      expect(clearCalls, 1);
      expect(notifications, 1);
      expect((notifier.state as Unauthenticated).errorCode, 'ACCOUNT_DISABLED');
    });

    test('tear-off legado onSessionExpired: auth.handleSessionExpired permanece compatível em HTTP 401', () async {
      final storage = InMemoryTokenStorage(
        initialAccessToken: 'access-1',
        initialRefreshToken: 'refresh-1',
      );
      final notifier = AuthNotifier(
        authRepository: _FakeAuthRepository(),
        sessionRefresher: SessionRefresher(refreshAccessToken: () async => ''),
        clearLocalSession: storage.clearTokens,
      );
      await notifier.checkAuthStatus();

      final client = RewitHttpClient(
        baseUrl: 'https://api.rewit.test',
        client: MockClient((_) async => http.Response(
              jsonEncode({'status': 401, 'code': 'AUTHENTICATION_REQUIRED'}),
              401,
            )),
        tokenStorage: storage,
        onSessionExpired: notifier.handleSessionExpired,
      );

      await expectLater(client.get('/me'), throwsA(anything));
      await pumpEventQueue();

      expect((notifier.state as Unauthenticated).errorCode, 'SESSION_EXPIRED');
      expect(await storage.getAccessToken(), isNull);
    });

    testWidgets('RewitApp desempilha rotas em ACCOUNT_DISABLED e LoginScreen não oferece reativação social nem mostra SESSION_EXPIRED', (tester) async {
      final storage = InMemoryTokenStorage(
        initialAccessToken: 'access-1',
        initialRefreshToken: 'refresh-1',
      );
      final notifier = AuthNotifier(
        authRepository: _FakeAuthRepository(),
        sessionRefresher: SessionRefresher(refreshAccessToken: () async => 'unused'),
        clearLocalSession: storage.clearTokens,
      );
      await notifier.checkAuthStatus();

      await tester.pumpWidget(RewitApp(
        config: const AppConfig(apiBaseUrl: 'https://api.rewit.test'),
        router: AppRouter(authNotifier: notifier),
      ));
      await tester.pumpAndSettle();

      Navigator.of(tester.element(find.byType(HomeScreen))).pushNamed(AppRouter.accountSettings);
      await tester.pumpAndSettle();
      expect(find.byType(AccountSettingsScreen), findsOneWidget);

      await notifier.handleSessionExpired(
        const SessionExpirationDetails(
          errorCode: 'ACCOUNT_DISABLED',
          statusCode: 401,
        ),
      );
      await tester.pumpAndSettle();

      expect(find.byType(AccountSettingsScreen), findsNothing);
      expect(find.byType(LoginScreen), findsOneWidget);
      expect(find.text(AuthNotifier.accountDisabledMessage), findsOneWidget);
      expect(find.text('Sua sessão expirou. Faça login novamente.'), findsNothing);
      expect(find.byKey(const Key('social_reactivate')), findsNothing);
      expect(find.text('Reativar Conta'), findsNothing);
      expect(await storage.getAccessToken(), isNull);
      expect(tester.state<NavigatorState>(find.byType(Navigator).first).canPop(), isFalse);
    });
  });

  group('Integridade de sessão sob concorrência (C-01, C-02 e invalidação)', () {
    test('C-01 completo: refresh obsoleto da conta A concluído após ACCOUNT_DISABLED e login na conta B não sobrescreve tokens nem estado', () async {
      final storage = InMemoryTokenStorage();
      final tokenCoordinator = SessionTokenCoordinator(storage: storage);
      final refreshAStarted = Completer<void>();
      final refreshARelease = Completer<void>();

      late final AuthNotifier notifier;
      late final AuthRepositoryImpl repository;

      final transport = MockClient((request) async {
        final path = request.url.path;
        if (path.endsWith('/auth/login')) {
          final body = jsonDecode(request.body) as Map<String, dynamic>;
          final email = body['email'] as String;
          final isB = email.startsWith('b@');
          return http.Response(
            jsonEncode({
              'user': {
                'id': isB ? 'user-b' : 'user-a',
                'email': email,
                'handle': isB ? 'contab' : 'contaa',
                'displayName': isB ? 'Conta B' : 'Conta A',
              },
              'accessToken': isB ? 'access-b' : 'access-a',
              'refreshToken': isB ? 'refresh-b' : 'refresh-a',
              'tokenType': 'Bearer',
              'expiresIn': 900,
            }),
            200,
          );
        }
        if (path.endsWith('/auth/refresh')) {
          refreshAStarted.complete();
          await refreshARelease.future;
          return http.Response(
            jsonEncode({
              'user': {
                'id': 'user-a',
                'email': 'a@rewit.test',
                'handle': 'contaa',
                'displayName': 'Conta A',
              },
              'accessToken': 'stale-access-a-200',
              'refreshToken': 'stale-refresh-a-200',
              'tokenType': 'Bearer',
              'expiresIn': 900,
            }),
            200,
          );
        }
        if (path.endsWith('/ping-401-required')) {
          return http.Response(
            jsonEncode({
              'status': 401,
              'code': 'AUTHENTICATION_REQUIRED',
              'detail': 'Token expirado',
            }),
            401,
          );
        }
        if (path.endsWith('/action-disabled')) {
          return http.Response(
            jsonEncode({
              'status': 401,
              'code': 'ACCOUNT_DISABLED',
              'detail': 'Conta desativada',
            }),
            401,
          );
        }
        return http.Response('{"ok":true}', 200);
      });

      final refresher = SessionRefresher(
        tokenCoordinator: tokenCoordinator,
        requestRefresh: () async {
          final session = await repository.requestTokenRefresh();
          return RefreshedSessionCredentials(
            accessToken: session.tokens.accessToken,
            refreshToken: session.tokens.refreshToken,
          );
        },
      );

      final httpClient = RewitHttpClient(
        baseUrl: 'https://api.rewit.test',
        client: TokenRefreshingClient(
          inner: transport,
          sessionRefresher: refresher,
        ),
        tokenStorage: storage,
        onSessionExpiredWithDetails: (details) {
          unawaited(notifier.handleSessionExpired(details));
        },
      );

      repository = AuthRepositoryImpl(
        httpClient: httpClient,
        tokenStorage: storage,
        tokenCoordinator: tokenCoordinator,
      );

      notifier = AuthNotifier(
        authRepository: repository,
        sessionRefresher: refresher,
        tokenCoordinator: tokenCoordinator,
        clearLocalSession: tokenCoordinator.invalidateAndClear,
      );

      // 1. Login na Conta A
      expect(await notifier.login('a@rewit.test', 'Senha@123'), isTrue);
      expect(await storage.getAccessToken(), 'access-a');

      // 2. Requisição da Conta A inicia refresh que fica suspenso na rede
      final pendingStaleRequest =
          expectLater(httpClient.get('/ping-401-required'), throwsA(anything));
      await refreshAStarted.future;

      // 3. Conta A recebe 401 ACCOUNT_DISABLED
      await expectLater(
        httpClient.post('/action-disabled'),
        throwsA(isA<ApiException>()),
      );
      await pumpEventQueue();
      expect(notifier.state, isA<Unauthenticated>());
      expect((notifier.state as Unauthenticated).errorCode, 'ACCOUNT_DISABLED');
      expect(await storage.getAccessToken(), isNull);

      // 4. Usuário faz login válido na Conta B antes de o refresh antigo responder
      expect(await notifier.login('b@rewit.test', 'Senha@123'), isTrue);
      expect((notifier.state as Authenticated).user.id, 'user-b');
      expect(await storage.getAccessToken(), 'access-b');
      expect(await storage.getRefreshToken(), 'refresh-b');

      // 5. Refresh atrasado da Conta A retorna HTTP 200
      refreshARelease.complete();
      await pendingStaleRequest;
      await pumpEventQueue();

      // Estado e armazenamento persistente preservam integralmente a Conta B
      expect(notifier.state, isA<Authenticated>());
      expect((notifier.state as Authenticated).user.id, 'user-b');
      expect(await storage.getAccessToken(), 'access-b');
      expect(await storage.getRefreshToken(), 'refresh-b');
    });

    test('C-02a: checkAuthStatus durante _inFlightDisabledExpiry aguarda conclusão sem ir para Authenticating nem chamar getMe', () async {
      final storage = InMemoryTokenStorage(
        initialAccessToken: 'access-a',
        initialRefreshToken: 'refresh-a',
      );
      final tokenCoordinator = SessionTokenCoordinator(storage: storage);
      final clearStarted = Completer<void>();
      final clearRelease = Completer<void>();
      var getMeCalls = 0;

      final repo = _CallbackAuthRepository(
        onHasSession: () async => true,
        onGetMe: () async {
          getMeCalls++;
          return _user;
        },
      );

      final notifier = AuthNotifier(
        authRepository: repo,
        tokenCoordinator: tokenCoordinator,
        clearLocalSession: () async {
          clearStarted.complete();
          await clearRelease.future;
          await storage.clearTokens();
        },
      );
      await notifier.checkAuthStatus();
      expect(getMeCalls, 1);
      getMeCalls = 0;

      // Simular _inFlightDisabledExpiry ativa via clearLocalSession lenta sem tokenCoordinator no notifier
      final gatedNotifier = AuthNotifier(
        authRepository: repo,
        clearLocalSession: () async {
          if (!clearStarted.isCompleted) {
            clearStarted.complete();
          }
          await clearRelease.future;
          await storage.clearTokens();
        },
      );
      await gatedNotifier.checkAuthStatus();
      getMeCalls = 0;

      final states = <AuthState>[];
      var notifications = 0;
      gatedNotifier.addListener(() {
        notifications++;
        states.add(gatedNotifier.state);
      });

      final disabledFuture = gatedNotifier.handleSessionExpired(
        const SessionExpirationDetails(
          errorCode: 'ACCOUNT_DISABLED',
          statusCode: 401,
        ),
      );
      // checkAuthStatus chamada enquanto ACCOUNT_DISABLED ainda está limpando
      final checkFuture = gatedNotifier.checkAuthStatus();

      clearRelease.complete();
      await Future.wait([disabledFuture, checkFuture]);

      expect(getMeCalls, 0);
      expect(states.whereType<Authenticating>(), isEmpty);
      expect(notifications, 1);
      expect(gatedNotifier.state, isA<Unauthenticated>());
      final finalState = gatedNotifier.state as Unauthenticated;
      expect(finalState.errorCode, 'ACCOUNT_DISABLED');
      expect(finalState.errorMessage, AuthNotifier.accountDisabledMessage);
    });

    test('C-02b: checkAuthStatus chamada após ACCOUNT_DISABLED concluída preserva erro sem notificações extras', () async {
      var getMeCalls = 0;
      var hasSession = true;
      final repo = _CallbackAuthRepository(
        onHasSession: () async => hasSession,
        onGetMe: () async {
          getMeCalls++;
          return _user;
        },
      );
      final notifier = AuthNotifier(
        authRepository: repo,
        clearLocalSession: () async {
          hasSession = false;
        },
      );
      await notifier.checkAuthStatus();
      getMeCalls = 0;

      await notifier.handleSessionExpired(
        const SessionExpirationDetails(
          errorCode: 'ACCOUNT_DISABLED',
          statusCode: 401,
        ),
      );

      var notifications = 0;
      notifier.addListener(() => notifications++);

      await notifier.checkAuthStatus();

      expect(getMeCalls, 0);
      expect(notifications, 0);
      expect((notifier.state as Unauthenticated).errorCode, 'ACCOUNT_DISABLED');
      expect(
        (notifier.state as Unauthenticated).errorMessage,
        AuthNotifier.accountDisabledMessage,
      );
    });

    test('C-02c: checkAuthStatus já em curso em getMe ao chegar ACCOUNT_DISABLED não restaura sessão em nenhum desfecho', () async {
      for (final outcome in ['success', 'api401', 'network', 'unknown']) {
        final getMeStarted = Completer<void>();
        final getMeRelease = Completer<void>();
        final repo = _CallbackAuthRepository(
          onHasSession: () async => true,
          onGetMe: () async {
            getMeStarted.complete();
            await getMeRelease.future;
            switch (outcome) {
              case 'success':
                return _user;
              case 'api401':
                throw const ApiException(
                  ProblemDetail(
                    type: 'about:blank',
                    title: 'Não autorizado',
                    status: 401,
                    detail: 'Sessão expirada',
                    code: 'AUTHENTICATION_REQUIRED',
                  ),
                );
              case 'network':
                throw const NetworkException('Sem conexão');
              default:
                throw StateError('Falha inesperada');
            }
          },
        );

        final storage = InMemoryTokenStorage(
          initialAccessToken: 'acc-a',
          initialRefreshToken: 'ref-a',
        );
        final notifier = AuthNotifier(
          authRepository: repo,
          clearLocalSession: storage.clearTokens,
        );

        final inFlightCheck = notifier.checkAuthStatus();
        await getMeStarted.future;

        await notifier.handleSessionExpired(
          const SessionExpirationDetails(
            errorCode: 'ACCOUNT_DISABLED',
            statusCode: 401,
          ),
        );

        getMeRelease.complete();
        await inFlightCheck;

        expect(
          notifier.state,
          isA<Unauthenticated>(),
          reason: 'Desfecho "$outcome" não pode restaurar Authenticated',
        );
        expect(
          (notifier.state as Unauthenticated).errorCode,
          'ACCOUNT_DISABLED',
          reason: 'Desfecho "$outcome" não pode sobrescrever ACCOUNT_DISABLED',
        );
        expect(await storage.getAccessToken(), isNull);
      }
    });

    test('checkAuthStatus durante refreshTokens em curso após ACCOUNT_DISABLED e login B preserva conta B', () async {
      final storage = InMemoryTokenStorage(
        initialAccessToken: 'acc-a',
        initialRefreshToken: 'ref-a',
      );
      final tokenCoordinator = SessionTokenCoordinator(storage: storage);
      final refreshStarted = Completer<void>();
      final refreshRelease = Completer<void>();

      const userB = AuthUserDto(
        id: 'user-b',
        email: 'b@rewit.test',
        handle: 'b',
        displayName: 'Conta B',
      );

      final repo = _CallbackAuthRepository(
        onHasSession: () async => true,
        onGetMe: () async => throw const ApiException(
          ProblemDetail(
            type: 'about:blank',
            title: 'Não autorizado',
            status: 401,
            detail: 'Sessão expirada',
            code: 'AUTHENTICATION_REQUIRED',
          ),
        ),
        onRefreshTokens: () async {
          final genAtStart = tokenCoordinator.currentGeneration;
          refreshStarted.complete();
          await refreshRelease.future;
          final saved = await tokenCoordinator.saveRefreshedTokens(
            accessToken: 'stale-acc-a',
            refreshToken: 'stale-ref-a',
            expectedGeneration: genAtStart,
          );
          if (!saved) {
            throw const ApiException(
              ProblemDetail(
                type: 'about:blank',
                title: 'Sessão invalidada',
                status: 401,
                detail: 'Sessão invalidada durante a renovação',
                code: 'SESSION_INVALIDATED',
              ),
            );
          }
          return _session;
        },
        onLogin: () async {
          await tokenCoordinator.saveNewSessionTokens(
            accessToken: 'acc-b',
            refreshToken: 'ref-b',
          );
          return const Authenticated(
            user: userB,
            tokens: AuthTokens(accessToken: 'acc-b', refreshToken: 'ref-b'),
          );
        },
        onLogout: () async {
          fail('logout não pode ser chamado após sessão já trocada para Conta B');
        },
      );

      final notifier = AuthNotifier(
        authRepository: repo,
        tokenCoordinator: tokenCoordinator,
        clearLocalSession: tokenCoordinator.invalidateAndClear,
      );

      final checkFuture = notifier.checkAuthStatus();
      await refreshStarted.future;

      await notifier.handleSessionExpired(
        const SessionExpirationDetails(
          errorCode: 'ACCOUNT_DISABLED',
          statusCode: 401,
        ),
      );
      expect(await notifier.login('b@rewit.test', 'Senha@123'), isTrue);

      refreshRelease.complete();
      await checkFuture;

      expect((notifier.state as Authenticated).user.id, 'user-b');
      expect(await storage.getAccessToken(), 'acc-b');
      expect(await storage.getRefreshToken(), 'ref-b');
    });

    test('deactivateAccount, changePassword e logout impedem limpeza tardia de refresh obsoleto na conta B', () async {
      for (final action in ['deactivate', 'changePassword', 'logout']) {
        final storage = InMemoryTokenStorage();
        final tokenCoordinator = SessionTokenCoordinator(storage: storage);
        final refreshStarted = Completer<void>();
        final refreshRelease = Completer<void>();

        late final AuthNotifier notifier;
        late final AuthRepositoryImpl repository;

        final transport = MockClient((request) async {
          final path = request.url.path;
          if (path.endsWith('/auth/login')) {
            final body = jsonDecode(request.body) as Map<String, dynamic>;
            final isB = (body['email'] as String).startsWith('b@');
            return http.Response(
              jsonEncode({
                'user': {
                  'id': isB ? 'user-b' : 'user-a',
                  'email': body['email'],
                  'handle': isB ? 'contab' : 'contaa',
                  'displayName': isB ? 'Conta B' : 'Conta A',
                },
                'accessToken': isB ? 'access-b' : 'access-a',
                'refreshToken': isB ? 'refresh-b' : 'refresh-a',
                'tokenType': 'Bearer',
                'expiresIn': 900,
              }),
              200,
            );
          }
          if (path.endsWith('/auth/refresh')) {
            refreshStarted.complete();
            await refreshRelease.future;
            return http.Response(
              jsonEncode({
                'user': {
                  'id': 'user-a',
                  'email': 'a@rewit.test',
                  'handle': 'contaa',
                  'displayName': 'Conta A',
                },
                'accessToken': 'stale-access-a',
                'refreshToken': 'stale-refresh-a',
                'tokenType': 'Bearer',
                'expiresIn': 900,
              }),
              200,
            );
          }
          if (path.endsWith('/ping-401')) {
            return http.Response(
              jsonEncode({
                'status': 401,
                'code': 'AUTHENTICATION_REQUIRED',
                'detail': 'Expirado',
              }),
              401,
            );
          }
          return http.Response('{}', 200);
        });

        final refresher = SessionRefresher(
          tokenCoordinator: tokenCoordinator,
          requestRefresh: () async {
            final session = await repository.requestTokenRefresh();
            return RefreshedSessionCredentials(
              accessToken: session.tokens.accessToken,
              refreshToken: session.tokens.refreshToken,
            );
          },
        );

        final httpClient = RewitHttpClient(
          baseUrl: 'https://api.rewit.test',
          client: TokenRefreshingClient(
            inner: transport,
            sessionRefresher: refresher,
          ),
          tokenStorage: storage,
          onSessionExpiredWithDetails: (details) {
            unawaited(notifier.handleSessionExpired(details));
          },
        );

        repository = AuthRepositoryImpl(
          httpClient: httpClient,
          tokenStorage: storage,
          tokenCoordinator: tokenCoordinator,
        );

        notifier = AuthNotifier(
          authRepository: repository,
          sessionRefresher: refresher,
          tokenCoordinator: tokenCoordinator,
          clearLocalSession: tokenCoordinator.invalidateAndClear,
        );

        expect(await notifier.login('a@rewit.test', 'Senha@123'), isTrue);

        final staleCall =
            expectLater(httpClient.get('/ping-401'), throwsA(anything));
        await refreshStarted.future;

        if (action == 'deactivate') {
          expect(await notifier.deactivateAccount(), isTrue);
        } else if (action == 'changePassword') {
          expect(
            await notifier.changePassword(
              currentPassword: 'old',
              newPassword: 'new',
            ),
            isTrue,
          );
        } else {
          await notifier.logout();
        }
        expect(await storage.getAccessToken(), isNull);

        expect(await notifier.login('b@rewit.test', 'Senha@123'), isTrue);

        refreshRelease.complete();
        await staleCall;
        await pumpEventQueue();

        expect(
          (notifier.state as Authenticated).user.id,
          'user-b',
          reason: '$action não pode afetar Conta B',
        );
        expect(await storage.getAccessToken(), 'access-b');
        expect(await storage.getRefreshToken(), 'refresh-b');
      }
    });

    test('deactivateAccount não restaura Authenticated quando ACCOUNT_DISABLED vence em paralelo', () async {
      final deactivateStarted = Completer<void>();
      final deactivateRelease = Completer<void>();
      final storage = InMemoryTokenStorage(
        initialAccessToken: 'acc-a',
        initialRefreshToken: 'ref-a',
      );
      final repo = _CallbackAuthRepository(
        onHasSession: () async => true,
        onGetMe: () async => _user,
        onDeactivate: () async {
          deactivateStarted.complete();
          await deactivateRelease.future;
          throw const NetworkException('Falha tardia');
        },
      );
      final notifier = AuthNotifier(
        authRepository: repo,
        clearLocalSession: storage.clearTokens,
      );
      await notifier.checkAuthStatus();

      final deactivateFuture = notifier.deactivateAccount();
      await deactivateStarted.future;

      await notifier.handleSessionExpired(
        const SessionExpirationDetails(
          errorCode: 'ACCOUNT_DISABLED',
          statusCode: 401,
        ),
      );

      deactivateRelease.complete();
      await expectLater(deactivateFuture, throwsA(isA<NetworkException>()));

      expect(notifier.state, isA<Unauthenticated>());
      expect((notifier.state as Unauthenticated).errorCode, 'ACCOUNT_DISABLED');
    });
  });

  group('Propriedade das tentativas de autenticação (Testes A–I)', () {
    Map<String, dynamic> authBody(String tag) => <String, dynamic>{
          'user': <String, dynamic>{
            'id': 'user-$tag',
            'email': '$tag@rewit.test',
            'handle': 'user$tag',
            'displayName': 'User ${tag.toUpperCase()}',
          },
          'accessToken': 'access-$tag',
          'refreshToken': 'refresh-$tag',
          'tokenType': 'Bearer',
          'expiresIn': 900,
        };

    test('Teste A: login A antigo após logout e login B não sobrescreve tokens nem sessão e próxima requisição usa token B', () async {
      final storage = _SpyTokenStorage();
      final coordinator = SessionTokenCoordinator(storage: storage);
      final loginAStarted = Completer<void>();
      final loginARelease = Completer<void>();
      String? lastProtectedAuthHeader;

      late final AuthNotifier notifier;
      late final AuthRepositoryImpl repository;

      final transport = MockClient((request) async {
        final path = request.url.path;
        if (path.endsWith('/auth/login')) {
          final body = jsonDecode(request.body) as Map<String, dynamic>;
          final email = body['email'] as String;
          if (email.startsWith('a@')) {
            loginAStarted.complete();
            await loginARelease.future;
            return http.Response(jsonEncode(authBody('a')), 200);
          }
          return http.Response(jsonEncode(authBody('b')), 200);
        }
        if (path.endsWith('/protected')) {
          lastProtectedAuthHeader = request.headers['Authorization'];
          return http.Response('{"ok":true}', 200);
        }
        return http.Response('{}', 200);
      });

      final refresher = SessionRefresher(
        tokenCoordinator: coordinator,
        requestRefresh: () async {
          final s = await repository.requestTokenRefresh();
          return RefreshedSessionCredentials(
            accessToken: s.tokens.accessToken,
            refreshToken: s.tokens.refreshToken,
          );
        },
      );
      final httpClient = RewitHttpClient(
        baseUrl: 'https://api.rewit.test',
        client: TokenRefreshingClient(inner: transport, sessionRefresher: refresher),
        tokenStorage: storage,
        onSessionExpiredWithDetails: (d) => unawaited(notifier.handleSessionExpired(d)),
      );
      repository = AuthRepositoryImpl(
        httpClient: httpClient,
        tokenStorage: storage,
        tokenCoordinator: coordinator,
      );
      notifier = AuthNotifier(
        authRepository: repository,
        sessionRefresher: refresher,
        tokenCoordinator: coordinator,
        clearLocalSession: coordinator.invalidateAndClear,
      );

      final futureA = notifier.login('a@rewit.test', 'Senha@123');
      await loginAStarted.future;

      await notifier.logout();
      expect(notifier.state, isA<Unauthenticated>());
      expect(await storage.getAccessToken(), isNull);

      expect(await notifier.login('b@rewit.test', 'Senha@123'), isTrue);
      final opsBeforeLateA = storage.ops.length;

      loginARelease.complete();
      expect(await futureA, isFalse);

      expect(storage.ops.length, opsBeforeLateA);
      expect(notifier.state, isA<Authenticated>());
      expect((notifier.state as Authenticated).user.id, 'user-b');
      expect(await storage.getAccessToken(), 'access-b');
      expect(await storage.getRefreshToken(), 'refresh-b');
      expect(coordinator.activeAuthenticationAttemptId, isNull);
      expect(coordinator.currentGeneration, greaterThanOrEqualTo(1));

      await httpClient.get('/protected');
      expect(lastProtectedAuthHeader, 'Bearer access-b');
    });

    test('Teste B: duas tentativas concorrentes iniciadas na mesma geração (B responde primeiro, A depois)', () async {
      final storage = _SpyTokenStorage();
      final coordinator = SessionTokenCoordinator(storage: storage);
      final aStarted = Completer<void>();
      final aRelease = Completer<void>();

      final httpClient = RewitHttpClient(
        baseUrl: 'https://api.rewit.test',
        client: MockClient((request) async {
          final email = (jsonDecode(request.body) as Map<String, dynamic>)['email'] as String;
          if (email.startsWith('a@')) {
            aStarted.complete();
            await aRelease.future;
            return http.Response(jsonEncode(authBody('a')), 200);
          }
          return http.Response(jsonEncode(authBody('b')), 200);
        }),
        tokenStorage: storage,
      );
      final repo = AuthRepositoryImpl(
        httpClient: httpClient,
        tokenStorage: storage,
        tokenCoordinator: coordinator,
      );
      final notifier = AuthNotifier(
        authRepository: repo,
        tokenCoordinator: coordinator,
      );

      expect(coordinator.currentGeneration, 0);
      final futureA = notifier.login('a@rewit.test', 'Senha@123');
      await aStarted.future;
      expect(coordinator.currentGeneration, 0);

      final futureB = notifier.login('b@rewit.test', 'Senha@123');
      expect(await futureB, isTrue);

      aRelease.complete();
      expect(await futureA, isFalse);

      expect((notifier.state as Authenticated).user.id, 'user-b');
      expect(await storage.getAccessToken(), 'access-b');
      expect(await storage.getRefreshToken(), 'refresh-b');
      expect(coordinator.currentGeneration, 1);
      expect(coordinator.activeAuthenticationAttemptId, isNull);
      expect(storage.ops, <String>['save:access-b/refresh-b']);
    });

    test('Teste C: respostas na ordem oposta (A responde primeiro, B depois) não publicam sessão temporária de A', () async {
      final storage = _SpyTokenStorage();
      final coordinator = SessionTokenCoordinator(storage: storage);
      final aStarted = Completer<void>();
      final aRelease = Completer<void>();
      final bStarted = Completer<void>();
      final bRelease = Completer<void>();

      final httpClient = RewitHttpClient(
        baseUrl: 'https://api.rewit.test',
        client: MockClient((request) async {
          final email = (jsonDecode(request.body) as Map<String, dynamic>)['email'] as String;
          if (email.startsWith('a@')) {
            aStarted.complete();
            await aRelease.future;
            return http.Response(jsonEncode(authBody('a')), 200);
          }
          bStarted.complete();
          await bRelease.future;
          return http.Response(jsonEncode(authBody('b')), 200);
        }),
        tokenStorage: storage,
      );
      final repo = AuthRepositoryImpl(
        httpClient: httpClient,
        tokenStorage: storage,
        tokenCoordinator: coordinator,
      );
      final notifier = AuthNotifier(
        authRepository: repo,
        tokenCoordinator: coordinator,
      );

      final emittedStates = <AuthState>[];
      notifier.addListener(() => emittedStates.add(notifier.state));

      final futureA = notifier.login('a@rewit.test', 'Senha@123');
      await aStarted.future;

      final futureB = notifier.login('b@rewit.test', 'Senha@123');
      await bStarted.future;

      // A responde primeiro mesmo tendo perdido propriedade para B
      aRelease.complete();
      expect(await futureA, isFalse);
      expect(notifier.state, isA<Authenticating>());
      expect(storage.ops, isEmpty);
      expect(coordinator.currentGeneration, 0);

      // B responde depois e estabelece a sessão final
      bRelease.complete();
      expect(await futureB, isTrue);

      expect(
        emittedStates.whereType<Authenticated>().map((s) => s.user.id).toList(),
        <String>['user-b'],
      );
      expect(emittedStates.whereType<Unauthenticated>(), isEmpty);
      expect((notifier.state as Authenticated).user.id, 'user-b');
      expect(await storage.getAccessToken(), 'access-b');
      expect(await storage.getRefreshToken(), 'refresh-b');
      expect(coordinator.currentGeneration, 1);
      expect(coordinator.activeAuthenticationAttemptId, isNull);
    });

    test('Teste D: quando tentativa mais nova B falha (401 ou rede), tentativa antiga A não ressuscita ao responder 200', () async {
      for (final failureMode in ['401', 'network']) {
        final storage = _SpyTokenStorage();
        final coordinator = SessionTokenCoordinator(storage: storage);
        final aStarted = Completer<void>();
        final aRelease = Completer<void>();

        final httpClient = RewitHttpClient(
          baseUrl: 'https://api.rewit.test',
          client: MockClient((request) async {
            final email = (jsonDecode(request.body) as Map<String, dynamic>)['email'] as String;
            if (email.startsWith('a@')) {
              aStarted.complete();
              await aRelease.future;
              return http.Response(jsonEncode(authBody('a')), 200);
            }
            if (failureMode == 'network') {
              throw http.ClientException('Sem rede');
            }
            return http.Response(
              jsonEncode({'status': 401, 'code': 'INVALID_CREDENTIALS', 'detail': 'Inválido'}),
              401,
            );
          }),
          tokenStorage: storage,
        );
        final repo = AuthRepositoryImpl(
          httpClient: httpClient,
          tokenStorage: storage,
          tokenCoordinator: coordinator,
        );
        final notifier = AuthNotifier(
          authRepository: repo,
          tokenCoordinator: coordinator,
        );

        final futureA = notifier.login('a@rewit.test', 'Senha@123');
        await aStarted.future;

        final futureB = notifier.login('b@rewit.test', 'errada');
        expect(await futureB, isFalse);
        expect(notifier.state, isA<Unauthenticated>());

        aRelease.complete();
        expect(await futureA, isFalse);

        expect(notifier.state, isA<Unauthenticated>(), reason: 'Modo $failureMode não pode ressuscitar A');
        expect(await storage.getAccessToken(), isNull);
        expect(await storage.getRefreshToken(), isNull);
        expect(coordinator.currentGeneration, 0);
        expect(coordinator.activeAuthenticationAttemptId, isNull);
      }
    });

    test('Teste E: tentativa antiga A já em saveTokens(A) suspensa no I/O compensa antes de B gravar e preserva tokens e estado B', () async {
      final storage = _SpyTokenStorage();
      final coordinator = SessionTokenCoordinator(storage: storage);
      final writeAStarted = Completer<void>();
      final writeARelease = Completer<void>();
      storage.onSave = (access, _) async {
        if (access == 'access-a') {
          writeAStarted.complete();
          await writeARelease.future;
        }
      };

      final httpClient = RewitHttpClient(
        baseUrl: 'https://api.rewit.test',
        client: MockClient((request) async {
          final email = (jsonDecode(request.body) as Map<String, dynamic>)['email'] as String;
          return http.Response(
            jsonEncode(authBody(email.startsWith('a@') ? 'a' : 'b')),
            200,
          );
        }),
        tokenStorage: storage,
      );
      final repo = AuthRepositoryImpl(
        httpClient: httpClient,
        tokenStorage: storage,
        tokenCoordinator: coordinator,
      );
      final notifier = AuthNotifier(
        authRepository: repo,
        tokenCoordinator: coordinator,
      );

      final futureA = notifier.login('a@rewit.test', 'Senha@123');
      await writeAStarted.future;

      final futureB = notifier.login('b@rewit.test', 'Senha@123');

      writeARelease.complete();
      expect(await futureA, isFalse);
      expect(await futureB, isTrue);

      expect(storage.ops, <String>[
        'save:access-a/refresh-a',
        'clear',
        'save:access-b/refresh-b',
      ]);
      expect((notifier.state as Authenticated).user.id, 'user-b');
      expect(await storage.getAccessToken(), 'access-b');
      expect(await storage.getRefreshToken(), 'refresh-b');
      expect(coordinator.activeAuthenticationAttemptId, isNull);
      expect(coordinator.currentGeneration, greaterThanOrEqualTo(2));
    });

    test('Teste F: logout invalida login A pendente e impede gravação tardia ao retornar 200', () async {
      final storage = _SpyTokenStorage();
      final coordinator = SessionTokenCoordinator(storage: storage);
      final aStarted = Completer<void>();
      final aRelease = Completer<void>();

      final httpClient = RewitHttpClient(
        baseUrl: 'https://api.rewit.test',
        client: MockClient((request) async {
          if (request.url.path.endsWith('/auth/login')) {
            aStarted.complete();
            await aRelease.future;
            return http.Response(jsonEncode(authBody('a')), 200);
          }
          return http.Response('{}', 200);
        }),
        tokenStorage: storage,
      );
      final repo = AuthRepositoryImpl(
        httpClient: httpClient,
        tokenStorage: storage,
        tokenCoordinator: coordinator,
      );
      final notifier = AuthNotifier(
        authRepository: repo,
        tokenCoordinator: coordinator,
        clearLocalSession: coordinator.invalidateAndClear,
      );

      final futureA = notifier.login('a@rewit.test', 'Senha@123');
      await aStarted.future;

      await notifier.logout();
      expect(notifier.state, isA<Unauthenticated>());

      aRelease.complete();
      expect(await futureA, isFalse);

      expect(notifier.state, isA<Unauthenticated>());
      expect(await storage.getAccessToken(), isNull);
      expect(await storage.getRefreshToken(), isNull);
      expect(coordinator.activeAuthenticationAttemptId, isNull);
    });

    test('Teste G: ACCOUNT_DISABLED invalida tentativa A pendente mesmo se responder 200 depois', () async {
      final storage = _SpyTokenStorage();
      final coordinator = SessionTokenCoordinator(storage: storage);
      final aStarted = Completer<void>();
      final aRelease = Completer<void>();

      final httpClient = RewitHttpClient(
        baseUrl: 'https://api.rewit.test',
        client: MockClient((request) async {
          if (request.url.path.endsWith('/auth/login')) {
            aStarted.complete();
            await aRelease.future;
            return http.Response(jsonEncode(authBody('a')), 200);
          }
          return http.Response('{}', 200);
        }),
        tokenStorage: storage,
      );
      final repo = AuthRepositoryImpl(
        httpClient: httpClient,
        tokenStorage: storage,
        tokenCoordinator: coordinator,
      );
      final notifier = AuthNotifier(
        authRepository: repo,
        tokenCoordinator: coordinator,
        clearLocalSession: coordinator.invalidateAndClear,
      );

      final futureA = notifier.login('a@rewit.test', 'Senha@123');
      await aStarted.future;

      await notifier.handleSessionExpired(
        const SessionExpirationDetails(
          errorCode: 'ACCOUNT_DISABLED',
          statusCode: 401,
        ),
      );
      expect((notifier.state as Unauthenticated).errorCode, 'ACCOUNT_DISABLED');

      aRelease.complete();
      expect(await futureA, isFalse);

      expect(notifier.state, isA<Unauthenticated>());
      expect((notifier.state as Unauthenticated).errorCode, 'ACCOUNT_DISABLED');
      expect(await storage.getAccessToken(), isNull);
      expect(await storage.getRefreshToken(), isNull);
      expect(coordinator.activeAuthenticationAttemptId, isNull);
    });

    test('Teste H: registro, login social e reativações (local/social) capturam attemptId no início e respeitam invalidação', () async {
      // H1: register A substituído por login B
      {
        final storage = _SpyTokenStorage();
        final coordinator = SessionTokenCoordinator(storage: storage);
        final registerStarted = Completer<void>();
        final registerRelease = Completer<void>();
        final httpClient = RewitHttpClient(
          baseUrl: 'https://api.rewit.test',
          client: MockClient((request) async {
            if (request.url.path.endsWith('/auth/register')) {
              registerStarted.complete();
              await registerRelease.future;
              return http.Response(jsonEncode(authBody('reg-a')), 201);
            }
            return http.Response(jsonEncode(authBody('b')), 200);
          }),
          tokenStorage: storage,
        );
        final repo = AuthRepositoryImpl(
          httpClient: httpClient,
          tokenStorage: storage,
          tokenCoordinator: coordinator,
        );
        final notifier = AuthNotifier(
          authRepository: repo,
          tokenCoordinator: coordinator,
        );

        final regFuture = notifier.register(
          displayName: 'Reg A',
          handle: 'rega',
          email: 'rega@rewit.test',
          password: 'Password@123',
        );
        await registerStarted.future;

        expect(await notifier.login('b@rewit.test', 'Password@123'), isTrue);
        registerRelease.complete();
        expect(await regFuture, isFalse);
        expect((notifier.state as Authenticated).user.id, 'user-b');
        expect(await storage.getAccessToken(), 'access-b');
        expect(await storage.getRefreshToken(), 'refresh-b');
      }

      // H2: signInWithSocial e reactivateWithSocial substituídos após requestSocialNonce / obtainIdToken / exchange
      {
        final storage = _SpyTokenStorage();
        final coordinator = SessionTokenCoordinator(storage: storage);
        final idTokenStarted = Completer<void>();
        final idTokenRelease = Completer<void>();
        var exchangeCalls = 0;

        final httpClient = RewitHttpClient(
          baseUrl: 'https://api.rewit.test',
          client: MockClient((request) async {
            if (request.url.path.endsWith('/social/nonce')) {
              return http.Response(jsonEncode({'nonce': 'n1'}), 200);
            }
            if (request.url.path.contains('/auth/social/')) {
              exchangeCalls++;
              return http.Response(jsonEncode(authBody('social-a')), 200);
            }
            return http.Response(jsonEncode(authBody('local-b')), 200);
          }),
          tokenStorage: storage,
        );
        final repo = AuthRepositoryImpl(
          httpClient: httpClient,
          tokenStorage: storage,
          tokenCoordinator: coordinator,
        );
        final socialService = _GateSocialSignInService(
          onObtain: () async {
            idTokenStarted.complete();
            await idTokenRelease.future;
            return 'provider-id-token';
          },
        );
        final notifier = AuthNotifier(
          authRepository: repo,
          tokenCoordinator: coordinator,
          socialSignInService: socialService,
        );

        final socialFuture = notifier.signInWithSocial(SocialAuthProvider.google);
        await idTokenStarted.future;

        // Nova intenção local durante obtainIdToken torna tentativa social obsoleta
        expect(await notifier.login('b@rewit.test', 'Password@123'), isTrue);
        idTokenRelease.complete();
        expect(await socialFuture, isFalse);
        expect(exchangeCalls, 0);
        expect((notifier.state as Authenticated).user.id, 'user-local-b');
        expect(await storage.getAccessToken(), 'access-local-b');
      }

      // H3: reactivate local e reactivateWithSocial tardios após logout/ACCOUNT_DISABLED
      {
        final storage = _SpyTokenStorage();
        final coordinator = SessionTokenCoordinator(storage: storage);
        final reactivateStarted = Completer<void>();
        final reactivateRelease = Completer<void>();
        final socialReactStarted = Completer<void>();
        final socialReactRelease = Completer<void>();

        final httpClient = RewitHttpClient(
          baseUrl: 'https://api.rewit.test',
          client: MockClient((request) async {
            if (request.url.path.endsWith('/auth/reactivate')) {
              reactivateStarted.complete();
              await reactivateRelease.future;
              return http.Response(jsonEncode(authBody('react-a')), 200);
            }
            if (request.url.path.endsWith('/social/nonce')) {
              return http.Response(jsonEncode({'nonce': 'n-react'}), 200);
            }
            if (request.url.path.endsWith('/social/apple/reactivate')) {
              socialReactStarted.complete();
              await socialReactRelease.future;
              return http.Response(jsonEncode(authBody('sreact-a')), 200);
            }
            return http.Response(jsonEncode(authBody('winner-c')), 200);
          }),
          tokenStorage: storage,
        );
        final repo = AuthRepositoryImpl(
          httpClient: httpClient,
          tokenStorage: storage,
          tokenCoordinator: coordinator,
        );
        final notifier = AuthNotifier(
          authRepository: repo,
          tokenCoordinator: coordinator,
          clearLocalSession: coordinator.invalidateAndClear,
          socialSignInService: _GateSocialSignInService(onObtain: () async => 'apple-tok'),
        );

        final reactFuture = notifier.reactivate('a@rewit.test', 'Password@123');
        await reactivateStarted.future;
        await notifier.logout();
        reactivateRelease.complete();
        expect(await reactFuture, isFalse);
        expect(notifier.state, isA<Unauthenticated>());
        expect(await storage.getAccessToken(), isNull);

        final sReactFuture = notifier.reactivateWithSocial(SocialAuthProvider.apple);
        await socialReactStarted.future;
        await notifier.handleSessionExpired(
          const SessionExpirationDetails(errorCode: 'ACCOUNT_DISABLED', statusCode: 401),
        );
        socialReactRelease.complete();
        expect(await sReactFuture, isFalse);
        expect((notifier.state as Unauthenticated).errorCode, 'ACCOUNT_DISABLED');
        expect(await storage.getAccessToken(), isNull);
        expect(await storage.getRefreshToken(), isNull);
      }
    });
  });

  group('Política 2: Preservação da sessão anterior em falha de autenticação (Testes A–K)', () {
    Map<String, dynamic> authBody(String tag, {String suffix = ''}) =>
        <String, dynamic>{
          'user': <String, dynamic>{
            'id': 'user-$tag',
            'email': '$tag@rewit.test',
            'handle': 'user$tag',
            'displayName': 'User ${tag.toUpperCase()}',
          },
          'accessToken': 'access-$tag$suffix',
          'refreshToken': 'refresh-$tag$suffix',
          'tokenType': 'Bearer',
          'expiresIn': 900,
        };

    test('Teste A + K: login Y falha com 401 INVALID_CREDENTIALS com sessão X ativa preserva X e checkAuthStatus mantém X', () async {
      final storage = _SpyTokenStorage();
      final coordinator = SessionTokenCoordinator(storage: storage);
      final seenGetMeAuth = <String?>[];

      final httpClient = RewitHttpClient(
        baseUrl: 'https://api.rewit.test',
        client: MockClient((request) async {
          if (request.url.path.endsWith('/auth/login')) {
            final email = (jsonDecode(request.body) as Map<String, dynamic>)['email'] as String;
            if (email.startsWith('x@')) {
              return http.Response(jsonEncode(authBody('x')), 200);
            }
            return http.Response.bytes(
              utf8.encode(jsonEncode({
                'status': 401,
                'code': 'INVALID_CREDENTIALS',
                'detail': 'Credenciais inválidas',
              })),
              401,
            );
          }
          if (request.url.path.endsWith('/auth/me')) {
            seenGetMeAuth.add(request.headers['Authorization']);
            return http.Response(jsonEncode(authBody('x')['user']), 200);
          }
          return http.Response('{}', 200);
        }),
        tokenStorage: storage,
      );
      final repo = AuthRepositoryImpl(
        httpClient: httpClient,
        tokenStorage: storage,
        tokenCoordinator: coordinator,
      );
      final notifier = AuthNotifier(
        authRepository: repo,
        tokenCoordinator: coordinator,
        clearLocalSession: coordinator.invalidateAndClear,
      );

      expect(await notifier.login('x@rewit.test', 'Senha@123'), isTrue);
      final genX = coordinator.currentGeneration;

      final emitted = <AuthState>[];
      notifier.addListener(() => emitted.add(notifier.state));

      expect(await notifier.login('y@rewit.test', 'errada'), isFalse);

      expect(emitted.whereType<Unauthenticated>(), isEmpty);
      expect(notifier.state, isA<Authenticated>());
      expect((notifier.state as Authenticated).user.id, 'user-x');
      expect(notifier.lastAttemptError?.code, 'INVALID_CREDENTIALS');
      expect(notifier.lastAttemptError?.message, 'Credenciais inválidas');
      expect(notifier.hasPendingPreviousSessionSnapshot, isFalse);
      expect(await storage.getAccessToken(), 'access-x');
      expect(await storage.getRefreshToken(), 'refresh-x');
      expect(coordinator.currentGeneration, genX);
      expect(coordinator.activeAuthenticationAttemptId, isNull);

      // Teste K: revalidação após falha A mantém X
      await notifier.checkAuthStatus();
      expect(seenGetMeAuth, <String?>['Bearer access-x']);
      expect((notifier.state as Authenticated).user.id, 'user-x');
      expect(await storage.getAccessToken(), 'access-x');
      expect(await storage.getRefreshToken(), 'refresh-x');
    });

    test('Teste B + K: login Y falha por NetworkException preserva sessão X ativa e checkAuthStatus mantém X', () async {
      final storage = _SpyTokenStorage();
      final coordinator = SessionTokenCoordinator(storage: storage);

      final httpClient = RewitHttpClient(
        baseUrl: 'https://api.rewit.test',
        client: MockClient((request) async {
          if (request.url.path.endsWith('/auth/login')) {
            final email = (jsonDecode(request.body) as Map<String, dynamic>)['email'] as String;
            if (email.startsWith('x@')) {
              return http.Response(jsonEncode(authBody('x')), 200);
            }
            throw http.ClientException('Sem conexão');
          }
          if (request.url.path.endsWith('/auth/me')) {
            return http.Response(jsonEncode(authBody('x')['user']), 200);
          }
          return http.Response('{}', 200);
        }),
        tokenStorage: storage,
      );
      final repo = AuthRepositoryImpl(
        httpClient: httpClient,
        tokenStorage: storage,
        tokenCoordinator: coordinator,
      );
      final notifier = AuthNotifier(
        authRepository: repo,
        tokenCoordinator: coordinator,
        clearLocalSession: coordinator.invalidateAndClear,
      );

      expect(await notifier.login('x@rewit.test', 'Senha@123'), isTrue);
      final genX = coordinator.currentGeneration;

      expect(await notifier.login('y@rewit.test', 'Senha@123'), isFalse);

      expect((notifier.state as Authenticated).user.id, 'user-x');
      expect(notifier.lastAttemptError?.message, isNotEmpty);
      expect(notifier.hasPendingPreviousSessionSnapshot, isFalse);
      expect(await storage.getAccessToken(), 'access-x');
      expect(await storage.getRefreshToken(), 'refresh-x');
      expect(coordinator.currentGeneration, genX);

      await notifier.checkAuthStatus();
      expect((notifier.state as Authenticated).user.id, 'user-x');
      expect(await storage.getAccessToken(), 'access-x');
      expect(await storage.getRefreshToken(), 'refresh-x');
    });

    test('Teste C + K: A e B concorrentes com X ativa (B falha, A termina 200 depois) restaura X sem publicar A', () async {
      final storage = _SpyTokenStorage();
      final coordinator = SessionTokenCoordinator(storage: storage);
      final aStarted = Completer<void>();
      final aRelease = Completer<void>();

      final httpClient = RewitHttpClient(
        baseUrl: 'https://api.rewit.test',
        client: MockClient((request) async {
          if (request.url.path.endsWith('/auth/login')) {
            final email = (jsonDecode(request.body) as Map<String, dynamic>)['email'] as String;
            if (email.startsWith('x@')) {
              return http.Response(jsonEncode(authBody('x')), 200);
            }
            if (email.startsWith('a@')) {
              aStarted.complete();
              await aRelease.future;
              return http.Response(jsonEncode(authBody('a')), 200);
            }
            return http.Response(
              jsonEncode({'status': 401, 'code': 'INVALID_CREDENTIALS', 'detail': 'Senha incorreta B'}),
              401,
            );
          }
          if (request.url.path.endsWith('/auth/me')) {
            return http.Response(jsonEncode(authBody('x')['user']), 200);
          }
          return http.Response('{}', 200);
        }),
        tokenStorage: storage,
      );
      final repo = AuthRepositoryImpl(
        httpClient: httpClient,
        tokenStorage: storage,
        tokenCoordinator: coordinator,
      );
      final notifier = AuthNotifier(
        authRepository: repo,
        tokenCoordinator: coordinator,
        clearLocalSession: coordinator.invalidateAndClear,
      );

      expect(await notifier.login('x@rewit.test', 'Senha@123'), isTrue);
      final genX = coordinator.currentGeneration;

      final futureA = notifier.login('a@rewit.test', 'Senha@123');
      await aStarted.future;

      final futureB = notifier.login('b@rewit.test', 'errada');
      expect(await futureB, isFalse);

      expect((notifier.state as Authenticated).user.id, 'user-x');
      expect(notifier.lastAttemptError?.message, 'Senha incorreta B');

      aRelease.complete();
      expect(await futureA, isFalse);

      expect((notifier.state as Authenticated).user.id, 'user-x');
      expect(await storage.getAccessToken(), 'access-x');
      expect(await storage.getRefreshToken(), 'refresh-x');
      expect(coordinator.currentGeneration, genX);
      expect(coordinator.activeAuthenticationAttemptId, isNull);
      expect(notifier.hasPendingPreviousSessionSnapshot, isFalse);
      expect(storage.ops, <String>['save:access-x/refresh-x']);

      await notifier.checkAuthStatus();
      expect((notifier.state as Authenticated).user.id, 'user-x');
      expect(await storage.getAccessToken(), 'access-x');
    });

    test('Teste D: A e B concorrentes com X ativa (A responde 200 primeiro, B falha depois) preserva snapshot para B restaurar X', () async {
      final storage = _SpyTokenStorage();
      final coordinator = SessionTokenCoordinator(storage: storage);
      final aStarted = Completer<void>();
      final aRelease = Completer<void>();
      final bStarted = Completer<void>();
      final bRelease = Completer<void>();

      final httpClient = RewitHttpClient(
        baseUrl: 'https://api.rewit.test',
        client: MockClient((request) async {
          final email = (jsonDecode(request.body) as Map<String, dynamic>)['email'] as String;
          if (email.startsWith('x@')) {
            return http.Response(jsonEncode(authBody('x')), 200);
          }
          if (email.startsWith('a@')) {
            aStarted.complete();
            await aRelease.future;
            return http.Response(jsonEncode(authBody('a')), 200);
          }
          bStarted.complete();
          await bRelease.future;
          return http.Response(
            jsonEncode({'status': 401, 'code': 'INVALID_CREDENTIALS', 'detail': 'Falha B'}),
            401,
          );
        }),
        tokenStorage: storage,
      );
      final repo = AuthRepositoryImpl(
        httpClient: httpClient,
        tokenStorage: storage,
        tokenCoordinator: coordinator,
      );
      final notifier = AuthNotifier(
        authRepository: repo,
        tokenCoordinator: coordinator,
      );

      expect(await notifier.login('x@rewit.test', 'Senha@123'), isTrue);
      final genX = coordinator.currentGeneration;

      final emitted = <AuthState>[];
      notifier.addListener(() => emitted.add(notifier.state));

      final futureA = notifier.login('a@rewit.test', 'Senha@123');
      await aStarted.future;

      final futureB = notifier.login('b@rewit.test', 'errada');
      await bStarted.future;

      aRelease.complete();
      expect(await futureA, isFalse);
      expect(notifier.state, isA<Authenticating>());
      expect(notifier.hasPendingPreviousSessionSnapshot, isTrue);

      bRelease.complete();
      expect(await futureB, isFalse);

      expect(emitted.whereType<Unauthenticated>(), isEmpty);
      expect(
        emitted.whereType<Authenticated>().map((s) => s.user.id).toList(),
        <String>['user-x'],
      );
      expect((notifier.state as Authenticated).user.id, 'user-x');
      expect(notifier.lastAttemptError?.message, 'Falha B');
      expect(notifier.hasPendingPreviousSessionSnapshot, isFalse);
      expect(await storage.getAccessToken(), 'access-x');
      expect(await storage.getRefreshToken(), 'refresh-x');
      expect(coordinator.currentGeneration, genX);
      expect(coordinator.activeAuthenticationAttemptId, isNull);
    });

    test('Teste E: nova tentativa Y bem-sucedida descarta snapshot de X e impede restauração por falhas tardias de X', () async {
      final storage = _SpyTokenStorage();
      final coordinator = SessionTokenCoordinator(storage: storage);
      final meOfXStarted = Completer<void>();
      final meOfXRelease = Completer<void>();

      final httpClient = RewitHttpClient(
        baseUrl: 'https://api.rewit.test',
        client: MockClient((request) async {
          if (request.url.path.endsWith('/auth/login')) {
            final email = (jsonDecode(request.body) as Map<String, dynamic>)['email'] as String;
            return http.Response(jsonEncode(authBody(email.startsWith('y@') ? 'y' : 'x')), 200);
          }
          if (request.url.path.endsWith('/auth/me')) {
            meOfXStarted.complete();
            await meOfXRelease.future;
            throw const NetworkException('Falha tardia de X');
          }
          return http.Response('{}', 200);
        }),
        tokenStorage: storage,
      );
      final repo = AuthRepositoryImpl(
        httpClient: httpClient,
        tokenStorage: storage,
        tokenCoordinator: coordinator,
      );
      final notifier = AuthNotifier(
        authRepository: repo,
        tokenCoordinator: coordinator,
      );

      expect(await notifier.login('x@rewit.test', 'Senha@123'), isTrue);
      final genX = coordinator.currentGeneration;

      // Simular operação em curso relacionada a X antes de Y suceder
      final staleCheck = notifier.checkAuthStatus();
      await meOfXStarted.future;

      expect(await notifier.login('y@rewit.test', 'Senha@123'), isTrue);
      expect(notifier.hasPendingPreviousSessionSnapshot, isFalse);
      expect(notifier.lastAttemptError, isNull);
      expect((notifier.state as Authenticated).user.id, 'user-y');
      expect(await storage.getAccessToken(), 'access-y');
      expect(await storage.getRefreshToken(), 'refresh-y');
      expect(coordinator.currentGeneration, greaterThan(genX));

      meOfXRelease.complete();
      await staleCheck;

      expect((notifier.state as Authenticated).user.id, 'user-y');
      expect(await storage.getAccessToken(), 'access-y');
      expect(await storage.getRefreshToken(), 'refresh-y');
    });

    test('Teste F: logout durante nova tentativa Y (401 ou 200 tardio) descarta snapshot de X, limpa storage e mantém Unauthenticated', () async {
      for (final lateStatus in [401, 200]) {
        final storage = _SpyTokenStorage();
        final coordinator = SessionTokenCoordinator(storage: storage);
        final yStarted = Completer<void>();
        final yRelease = Completer<void>();

        final httpClient = RewitHttpClient(
          baseUrl: 'https://api.rewit.test',
          client: MockClient((request) async {
            if (request.url.path.endsWith('/auth/login')) {
              final email = (jsonDecode(request.body) as Map<String, dynamic>)['email'] as String;
              if (email.startsWith('x@')) {
                return http.Response(jsonEncode(authBody('x')), 200);
              }
              yStarted.complete();
              await yRelease.future;
              return lateStatus == 200
                  ? http.Response(jsonEncode(authBody('y')), 200)
                  : http.Response(
                      jsonEncode({'status': 401, 'code': 'INVALID_CREDENTIALS', 'detail': 'Inválido'}),
                      401,
                    );
            }
            return http.Response('{}', 200);
          }),
          tokenStorage: storage,
        );
        final repo = AuthRepositoryImpl(
          httpClient: httpClient,
          tokenStorage: storage,
          tokenCoordinator: coordinator,
        );
        final notifier = AuthNotifier(
          authRepository: repo,
          tokenCoordinator: coordinator,
          clearLocalSession: coordinator.invalidateAndClear,
        );

        expect(await notifier.login('x@rewit.test', 'Senha@123'), isTrue);

        final futureY = notifier.login('y@rewit.test', 'Senha@123');
        await yStarted.future;
        expect(notifier.hasPendingPreviousSessionSnapshot, isTrue);

        await notifier.logout();
        expect(notifier.state, isA<Unauthenticated>());
        expect(notifier.hasPendingPreviousSessionSnapshot, isFalse);
        expect(await storage.getAccessToken(), isNull);
        expect(await storage.getRefreshToken(), isNull);

        yRelease.complete();
        expect(await futureY, isFalse);

        expect(
          notifier.state,
          isA<Unauthenticated>(),
          reason: 'Desfecho tardio $lateStatus após logout não pode restaurar X nem autenticar Y',
        );
        expect(notifier.hasPendingPreviousSessionSnapshot, isFalse);
        expect(await storage.getAccessToken(), isNull);
        expect(await storage.getRefreshToken(), isNull);
      }
    });

    test('Teste G: ACCOUNT_DISABLED durante nova tentativa Y (401 ou 200 tardio) prevalece com mensagem neutra e descarta snapshot de X', () async {
      for (final lateStatus in [401, 200]) {
        final storage = _SpyTokenStorage();
        final coordinator = SessionTokenCoordinator(storage: storage);
        final yStarted = Completer<void>();
        final yRelease = Completer<void>();

        final httpClient = RewitHttpClient(
          baseUrl: 'https://api.rewit.test',
          client: MockClient((request) async {
            if (request.url.path.endsWith('/auth/login')) {
              final email = (jsonDecode(request.body) as Map<String, dynamic>)['email'] as String;
              if (email.startsWith('x@')) {
                return http.Response(jsonEncode(authBody('x')), 200);
              }
              yStarted.complete();
              await yRelease.future;
              return lateStatus == 200
                  ? http.Response(jsonEncode(authBody('y')), 200)
                  : http.Response(
                      jsonEncode({'status': 401, 'code': 'INVALID_CREDENTIALS', 'detail': 'Inválido'}),
                      401,
                    );
            }
            return http.Response('{}', 200);
          }),
          tokenStorage: storage,
        );
        final repo = AuthRepositoryImpl(
          httpClient: httpClient,
          tokenStorage: storage,
          tokenCoordinator: coordinator,
        );
        final notifier = AuthNotifier(
          authRepository: repo,
          tokenCoordinator: coordinator,
          clearLocalSession: coordinator.invalidateAndClear,
        );

        expect(await notifier.login('x@rewit.test', 'Senha@123'), isTrue);

        final futureY = notifier.login('y@rewit.test', 'Senha@123');
        await yStarted.future;

        await notifier.handleSessionExpired(
          const SessionExpirationDetails(
            errorCode: 'ACCOUNT_DISABLED',
            statusCode: 401,
          ),
        );
        expect(notifier.hasPendingPreviousSessionSnapshot, isFalse);
        expect((notifier.state as Unauthenticated).errorCode, 'ACCOUNT_DISABLED');
        expect((notifier.state as Unauthenticated).errorMessage, AuthNotifier.accountDisabledMessage);
        expect(await storage.getAccessToken(), isNull);
        expect(await storage.getRefreshToken(), isNull);

        yRelease.complete();
        expect(await futureY, isFalse);

        expect(notifier.state, isA<Unauthenticated>());
        expect((notifier.state as Unauthenticated).errorCode, 'ACCOUNT_DISABLED');
        expect((notifier.state as Unauthenticated).errorMessage, AuthNotifier.accountDisabledMessage);
        expect(notifier.hasPendingPreviousSessionSnapshot, isFalse);
        expect(await storage.getAccessToken(), isNull);
        expect(await storage.getRefreshToken(), isNull);
      }
    });

    test('Teste H + K: refresh legítimo de X conclui durante Y que depois falha mantém tokens renovados de X', () async {
      final storage = _SpyTokenStorage();
      final coordinator = SessionTokenCoordinator(storage: storage);
      final yStarted = Completer<void>();
      final yRelease = Completer<void>();
      final seenGetMeAuth = <String?>[];

      late final AuthRepositoryImpl repository;
      late final AuthNotifier notifier;

      final transport = MockClient((request) async {
        final path = request.url.path;
        if (path.endsWith('/auth/login')) {
          final email = (jsonDecode(request.body) as Map<String, dynamic>)['email'] as String;
          if (email.startsWith('x@')) {
            return http.Response(jsonEncode(authBody('x', suffix: '1')), 200);
          }
          yStarted.complete();
          await yRelease.future;
          return http.Response(
            jsonEncode({'status': 401, 'code': 'INVALID_CREDENTIALS', 'detail': 'Falha Y'}),
            401,
          );
        }
        if (path.endsWith('/auth/refresh')) {
          return http.Response(jsonEncode(authBody('x', suffix: '2')), 200);
        }
        if (path.endsWith('/ping-401-then-200')) {
          if (request.headers['Authorization'] == 'Bearer access-x2') {
            return http.Response('{"ok":true}', 200);
          }
          return http.Response(
            jsonEncode({'status': 401, 'code': 'AUTHENTICATION_REQUIRED', 'detail': 'Expirado'}),
            401,
          );
        }
        if (path.endsWith('/auth/me')) {
          seenGetMeAuth.add(request.headers['Authorization']);
          return http.Response(jsonEncode(authBody('x')['user']), 200);
        }
        return http.Response('{}', 200);
      });

      final refresher = SessionRefresher(
        tokenCoordinator: coordinator,
        requestRefresh: () async {
          final s = await repository.requestTokenRefresh();
          return RefreshedSessionCredentials(
            accessToken: s.tokens.accessToken,
            refreshToken: s.tokens.refreshToken,
          );
        },
      );
      final httpClient = RewitHttpClient(
        baseUrl: 'https://api.rewit.test',
        client: TokenRefreshingClient(inner: transport, sessionRefresher: refresher),
        tokenStorage: storage,
        onSessionExpiredWithDetails: (d) => unawaited(notifier.handleSessionExpired(d)),
      );
      repository = AuthRepositoryImpl(
        httpClient: httpClient,
        tokenStorage: storage,
        tokenCoordinator: coordinator,
      );
      notifier = AuthNotifier(
        authRepository: repository,
        sessionRefresher: refresher,
        tokenCoordinator: coordinator,
        clearLocalSession: coordinator.invalidateAndClear,
      );

      expect(await notifier.login('x@rewit.test', 'Senha@123'), isTrue);
      final genX = coordinator.currentGeneration;

      final futureY = notifier.login('y@rewit.test', 'errada');
      await yStarted.future;

      // Refresh legítimo da sessão X ocorre enquanto Y está pendente
      await httpClient.get('/ping-401-then-200');
      expect(await storage.getAccessToken(), 'access-x2');
      expect(await storage.getRefreshToken(), 'refresh-x2');
      expect(coordinator.currentGeneration, genX);

      // Y falha: restaura X e mantém os tokens renovados x2 intactos
      yRelease.complete();
      expect(await futureY, isFalse);

      expect((notifier.state as Authenticated).user.id, 'user-x');
      expect(await storage.getAccessToken(), 'access-x2');
      expect(await storage.getRefreshToken(), 'refresh-x2');
      expect(coordinator.currentGeneration, genX);
      expect(notifier.hasPendingPreviousSessionSnapshot, isFalse);

      await notifier.checkAuthStatus();
      expect(seenGetMeAuth, <String?>['Bearer access-x2']);
      expect((notifier.state as Authenticated).user.id, 'user-x');
      expect(await storage.getAccessToken(), 'access-x2');
      expect(await storage.getRefreshToken(), 'refresh-x2');
    });

    test('Teste I: duas tentativas A e B falham em sequência com X ativa (apenas B publica resultado e restaura X)', () async {
      final storage = _SpyTokenStorage();
      final coordinator = SessionTokenCoordinator(storage: storage);
      final aStarted = Completer<void>();
      final aRelease = Completer<void>();
      final bStarted = Completer<void>();
      final bRelease = Completer<void>();

      final httpClient = RewitHttpClient(
        baseUrl: 'https://api.rewit.test',
        client: MockClient((request) async {
          final email = (jsonDecode(request.body) as Map<String, dynamic>)['email'] as String;
          if (email.startsWith('x@')) {
            return http.Response(jsonEncode(authBody('x')), 200);
          }
          if (email.startsWith('a@')) {
            aStarted.complete();
            await aRelease.future;
            return http.Response(
              jsonEncode({'status': 401, 'code': 'INVALID_CREDENTIALS', 'detail': 'Erro A'}),
              401,
            );
          }
          bStarted.complete();
          await bRelease.future;
          return http.Response(
            jsonEncode({'status': 401, 'code': 'INVALID_CREDENTIALS', 'detail': 'Erro B'}),
            401,
          );
        }),
        tokenStorage: storage,
      );
      final repo = AuthRepositoryImpl(
        httpClient: httpClient,
        tokenStorage: storage,
        tokenCoordinator: coordinator,
      );
      final notifier = AuthNotifier(
        authRepository: repo,
        tokenCoordinator: coordinator,
      );

      expect(await notifier.login('x@rewit.test', 'Senha@123'), isTrue);
      final genX = coordinator.currentGeneration;

      final emitted = <AuthState>[];
      notifier.addListener(() => emitted.add(notifier.state));

      final futureA = notifier.login('a@rewit.test', 'erradaA');
      await aStarted.future;

      final futureB = notifier.login('b@rewit.test', 'erradaB');
      await bStarted.future;

      // A falha primeiro, mas já perdeu propriedade para B
      aRelease.complete();
      expect(await futureA, isFalse);
      expect(notifier.state, isA<Authenticating>());
      expect(notifier.lastAttemptError, isNull);
      expect(notifier.hasPendingPreviousSessionSnapshot, isTrue);

      // B falha depois como proprietário final e restaura X
      bRelease.complete();
      expect(await futureB, isFalse);

      expect(emitted.whereType<Unauthenticated>(), isEmpty);
      expect((notifier.state as Authenticated).user.id, 'user-x');
      expect(notifier.lastAttemptError?.message, 'Erro B');
      expect(notifier.hasPendingPreviousSessionSnapshot, isFalse);
      expect(await storage.getAccessToken(), 'access-x');
      expect(await storage.getRefreshToken(), 'refresh-x');
      expect(coordinator.currentGeneration, genX);
      expect(coordinator.activeAuthenticationAttemptId, isNull);
    });

    test('Teste J: sem sessão anterior e sem tokens, falha de login Y vai para Unauthenticated com storage vazio', () async {
      final storage = _SpyTokenStorage();
      final coordinator = SessionTokenCoordinator(storage: storage);

      final httpClient = RewitHttpClient(
        baseUrl: 'https://api.rewit.test',
        client: MockClient((_) async => http.Response.bytes(
              utf8.encode(jsonEncode({
                'status': 401,
                'code': 'INVALID_CREDENTIALS',
                'detail': 'Credenciais inválidas',
              })),
              401,
            )),
        tokenStorage: storage,
      );
      final repo = AuthRepositoryImpl(
        httpClient: httpClient,
        tokenStorage: storage,
        tokenCoordinator: coordinator,
      );
      final notifier = AuthNotifier(
        authRepository: repo,
        tokenCoordinator: coordinator,
      );

      expect(notifier.hasPendingPreviousSessionSnapshot, isFalse);
      expect(await notifier.login('y@rewit.test', 'errada'), isFalse);

      expect(notifier.state, isA<Unauthenticated>());
      final unauth = notifier.state as Unauthenticated;
      expect(unauth.errorCode, 'INVALID_CREDENTIALS');
      expect(unauth.errorMessage, 'Credenciais inválidas');
      expect(notifier.lastAttemptError, isNull);
      expect(notifier.hasPendingPreviousSessionSnapshot, isFalse);
      expect(await storage.getAccessToken(), isNull);
      expect(await storage.getRefreshToken(), isNull);
      expect(coordinator.currentGeneration, 0);
      expect(coordinator.activeAuthenticationAttemptId, isNull);
    });
  });
}

class _SpyTokenStorage implements TokenStorage {
  String? _accessToken;
  String? _refreshToken;
  final List<String> ops = <String>[];
  Future<void> Function(String access, String refresh)? onSave;

  @override
  Future<void> saveTokens({
    required String accessToken,
    required String refreshToken,
  }) async {
    ops.add('save:$accessToken/$refreshToken');
    if (onSave != null) {
      await onSave!(accessToken, refreshToken);
    }
    _accessToken = accessToken;
    _refreshToken = refreshToken;
  }

  @override
  Future<String?> getAccessToken() async => _accessToken;

  @override
  Future<String?> getRefreshToken() async => _refreshToken;

  @override
  Future<bool> hasAccessToken() async =>
      _accessToken != null && _accessToken!.isNotEmpty;

  @override
  Future<void> clearTokens() async {
    ops.add('clear');
    _accessToken = null;
    _refreshToken = null;
  }
}

class _GateSocialSignInService implements SocialSignInService {
  final Future<String> Function() onObtain;

  _GateSocialSignInService({required this.onObtain});

  @override
  bool isAvailable(SocialAuthProvider provider) => true;

  @override
  Future<String> obtainIdToken(
    SocialAuthProvider provider, {
    required String providerNonce,
  }) =>
      onObtain();
}

class _CallbackAuthRepository extends _FakeAuthRepository {
  final Future<bool> Function()? onHasSession;
  final Future<AuthUserDto> Function()? onGetMe;
  final Future<Authenticated> Function()? onRefreshTokens;
  final Future<Authenticated> Function()? onLogin;
  final Future<void> Function()? onLogout;
  final Future<void> Function()? onDeactivate;

  _CallbackAuthRepository({
    this.onHasSession,
    this.onGetMe,
    this.onRefreshTokens,
    this.onLogin,
    this.onLogout,
    this.onDeactivate,
  });

  @override
  Future<bool> hasStoredSession() =>
      onHasSession != null ? onHasSession!() : super.hasStoredSession();

  @override
  Future<AuthUserDto> getMe() => onGetMe != null ? onGetMe!() : super.getMe();

  @override
  Future<Authenticated> refreshTokens() =>
      onRefreshTokens != null ? onRefreshTokens!() : super.refreshTokens();

  @override
  Future<Authenticated> login({
    required String email,
    required String password,
  }) =>
      onLogin != null
          ? onLogin!()
          : super.login(email: email, password: password);

  @override
  Future<void> logout() => onLogout != null ? onLogout!() : super.logout();

  @override
  Future<void> deactivateAccount() =>
      onDeactivate != null ? onDeactivate!() : super.deactivateAccount();
}
