import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rewit_mobile/app/theme/app_theme.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/features/auth/data/models/auth_tokens.dart';
import 'package:rewit_mobile/features/auth/data/models/auth_user_dto.dart';
import 'package:rewit_mobile/features/auth/domain/entities/auth_state.dart';
import 'package:rewit_mobile/features/auth/domain/repositories/auth_repository.dart';
import 'package:rewit_mobile/features/auth/presentation/screens/login_screen.dart';
import 'package:rewit_mobile/features/auth/presentation/state/auth_notifier.dart';

class MockAuthRepo implements AuthRepository {
  bool shouldSucceed = true;
  String? lastEmail;
  String? lastPassword;

  @override
  Future<bool> hasStoredSession() async => false;

  @override
  Future<AuthUserDto> getMe() async => const AuthUserDto(
        id: '1',
        email: 'user@test.com',
        handle: 'testuser',
        displayName: 'Test',
      );

  @override
  Future<Authenticated> login({required String email, required String password}) async {
    lastEmail = email;
    lastPassword = password;
    if (shouldSucceed) {
      return const Authenticated(
        user: AuthUserDto(
          id: '1',
          email: 'user@test.com',
          handle: 'testuser',
          displayName: 'Test',
        ),
        tokens: AuthTokens(accessToken: 'a', refreshToken: 'r'),
      );
    } else {
      throw const ApiException(ProblemDetail(
        type: 'about:blank',
        title: 'Credenciais inválidas',
        status: 401,
        detail: 'E-mail ou senha inválidos.',
        code: 'INVALID_CREDENTIALS',
      ));
    }
  }

  @override
  Future<Authenticated> refreshTokens() async => throw UnimplementedError();

  @override
  Future<void> logout() async {}

  bool reactivateCalled = false;

  @override
  Future<Authenticated> reactivate({required String email, required String password}) async {
    reactivateCalled = true;
    lastEmail = email;
    lastPassword = password;
    if (shouldSucceed) {
      return const Authenticated(
        user: AuthUserDto(
          id: '1',
          email: 'user@test.com',
          handle: 'testuser',
          displayName: 'Test',
        ),
        tokens: AuthTokens(accessToken: 'a', refreshToken: 'r'),
      );
    } else {
      throw const ApiException(ProblemDetail(
        type: 'about:blank',
        title: 'Credenciais inválidas',
        status: 401,
        detail: 'Credenciais inválidas.',
        code: 'INVALID_CREDENTIALS',
      ));
    }
  }

  @override
  Future<void> deactivateAccount() async {}
}

void main() {
  Widget buildSubject(AuthNotifier notifier, {VoidCallback? onLoginSuccess}) {
    return MaterialApp(
      theme: AppTheme.lightTheme,
      home: LoginScreen(
        authNotifier: notifier,
        onLoginSuccess: onLoginSuccess,
      ),
    );
  }

  group('LoginScreen Widget Tests', () {
    testWidgets('renderiza campos de e-mail, senha e botão de entrar', (tester) async {
      final repo = MockAuthRepo();
      final notifier = AuthNotifier(authRepository: repo);

      await tester.pumpWidget(buildSubject(notifier));

      expect(find.text('Rewit'), findsOneWidget);
      expect(find.byType(TextFormField), findsNWidgets(2));
      expect(find.text('Entrar'), findsOneWidget);
    });

    testWidgets('exibe mensagens de validação ao submeter campos em branco', (tester) async {
      final repo = MockAuthRepo();
      final notifier = AuthNotifier(authRepository: repo);

      await tester.pumpWidget(buildSubject(notifier));

      await tester.tap(find.text('Entrar'));
      await tester.pumpAndSettle();

      expect(find.text('Informe seu e-mail'), findsOneWidget);
      expect(find.text('Informe sua senha'), findsOneWidget);
      expect(repo.lastEmail, isNull);
    });

    testWidgets('exibe banner de erro quando login falha por credenciais inválidas', (tester) async {
      final repo = MockAuthRepo()..shouldSucceed = false;
      final notifier = AuthNotifier(authRepository: repo);

      await tester.pumpWidget(buildSubject(notifier));

      await tester.enterText(find.byType(TextFormField).first, 'teste@rewit.com');
      await tester.enterText(find.byType(TextFormField).last, 'senhaIncorreta123');
      await tester.tap(find.text('Entrar'));
      await tester.pumpAndSettle();

      expect(find.text('E-mail ou senha inválidos.'), findsOneWidget);
    });

    testWidgets('chama onLoginSuccess quando login é bem sucedido', (tester) async {
      final repo = MockAuthRepo()..shouldSucceed = true;
      final notifier = AuthNotifier(authRepository: repo);
      bool successCalled = false;

      await tester.pumpWidget(
        buildSubject(notifier, onLoginSuccess: () {
          successCalled = true;
        }),
      );

      await tester.enterText(find.byType(TextFormField).first, 'correto@rewit.com');
      await tester.enterText(find.byType(TextFormField).last, 'senhaValida123');
      await tester.tap(find.text('Entrar'));
      await tester.pumpAndSettle();

      expect(successCalled, isTrue);
      expect(repo.lastEmail, 'correto@rewit.com');
    });
  });
}
