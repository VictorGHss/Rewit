import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rewit_mobile/app/app.dart';
import 'package:rewit_mobile/app/config/app_config.dart';
import 'package:rewit_mobile/app/router/app_router.dart';
import 'package:rewit_mobile/core/network/session_refresher.dart';
import 'package:rewit_mobile/features/auth/data/models/auth_tokens.dart';
import 'package:rewit_mobile/features/auth/data/models/auth_user_dto.dart';
import 'package:rewit_mobile/features/auth/domain/entities/auth_state.dart';
import 'package:rewit_mobile/features/auth/domain/repositories/auth_repository.dart';
import 'package:rewit_mobile/features/auth/presentation/screens/login_screen.dart';
import 'package:rewit_mobile/features/auth/presentation/state/auth_notifier.dart';
import 'package:rewit_mobile/features/home/presentation/screens/home_screen.dart';
import 'package:rewit_mobile/features/profile/presentation/screens/account_settings_screen.dart';

const _user = AuthUserDto(id: 'u1', email: 'ana@rewit.test', handle: 'ana', displayName: 'Ana');
const _session = Authenticated(user: _user, tokens: AuthTokens(accessToken: 'a', refreshToken: 'r'));

class _FakeAuthRepository implements AuthRepository {
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
}
