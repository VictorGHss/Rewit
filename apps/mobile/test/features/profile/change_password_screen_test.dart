import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rewit_mobile/app/theme/app_theme.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/features/auth/data/models/auth_tokens.dart';
import 'package:rewit_mobile/features/auth/data/models/auth_user_dto.dart';
import 'package:rewit_mobile/features/auth/domain/entities/auth_state.dart';
import 'package:rewit_mobile/features/auth/domain/repositories/auth_repository.dart';
import 'package:rewit_mobile/features/auth/presentation/state/auth_notifier.dart';
import 'package:rewit_mobile/features/profile/presentation/screens/change_password_screen.dart';

class FakeAuthRepoForChangePassword implements AuthRepository {
  bool shouldDelay = false;
  Completer<void>? delayCompleter;
  int changePasswordCallCount = 0;
  String? lastCurrentPassword;
  String? lastNewPassword;
  ApiException? apiExceptionToThrow;
  NetworkException? networkExceptionToThrow;

  @override
  Future<bool> hasStoredSession() async => true;

  @override
  Future<AuthUserDto> getMe() async => const AuthUserDto(
        id: 'user-change-pwd-1',
        email: 'user@rewit.app',
        handle: 'user_pwd',
        displayName: 'User Pwd',
      );

  @override
  Future<Authenticated> login({required String email, required String password}) async =>
      const Authenticated(
        user: AuthUserDto(
          id: 'user-change-pwd-1',
          email: 'user@rewit.app',
          handle: 'user_pwd',
          displayName: 'User Pwd',
        ),
        tokens: AuthTokens(accessToken: 'token-a', refreshToken: 'token-b'),
      );

  @override
  Future<Authenticated> refreshTokens() async => throw UnimplementedError();

  @override
  Future<void> logout() async {}

  @override
  Future<void> deactivateAccount() async {}

  @override
  Future<Authenticated> reactivate({required String email, required String password}) async =>
      throw UnimplementedError();

  @override
  Future<void> changePassword({
    required String currentPassword,
    required String newPassword,
  }) async {
    changePasswordCallCount++;
    lastCurrentPassword = currentPassword;
    lastNewPassword = newPassword;

    if (shouldDelay && delayCompleter != null) {
      await delayCompleter!.future;
    }

    if (apiExceptionToThrow != null) {
      throw apiExceptionToThrow!;
    }

    if (networkExceptionToThrow != null) {
      throw networkExceptionToThrow!;
    }
  }
}

void main() {
  group('ChangePasswordScreen Widget Tests', () {
    late FakeAuthRepoForChangePassword fakeRepo;
    late AuthNotifier authNotifier;

    setUp(() async {
      fakeRepo = FakeAuthRepoForChangePassword();
      authNotifier = AuthNotifier(authRepository: fakeRepo);
      await authNotifier.login('user@rewit.app', 'senha123');
    });

    Widget buildSubject({VoidCallback? onPasswordChanged}) {
      return MaterialApp(
        theme: AppTheme.lightTheme,
        home: Scaffold(
          body: ChangePasswordScreen(
            authNotifier: authNotifier,
            onPasswordChanged: onPasswordChanged,
          ),
        ),
      );
    }

    testWidgets('renderiza elementos da tela de alteração de senha corretamente', (tester) async {
      await tester.pumpWidget(buildSubject());

      expect(find.text('Alterar Senha'), findsOneWidget);
      expect(
        find.textContaining('todas as sessões ativas deste aplicativo e de outros dispositivos serão encerradas'),
        findsOneWidget,
      );

      expect(find.byKey(const Key('current_password_field')), findsOneWidget);
      expect(find.byKey(const Key('new_password_field')), findsOneWidget);
      expect(find.byKey(const Key('confirm_new_password_field')), findsOneWidget);
      expect(find.byKey(const Key('change_password_button')), findsOneWidget);

      // Verifica obscureText inicial nos 3 campos
      bool isFieldObscured(String fieldKey) {
        final textField = tester.widget<TextField>(
          find.descendant(of: find.byKey(Key(fieldKey)), matching: find.byType(TextField)),
        );
        return textField.obscureText;
      }

      expect(isFieldObscured('current_password_field'), isTrue);
      expect(isFieldObscured('new_password_field'), isTrue);
      expect(isFieldObscured('confirm_new_password_field'), isTrue);
    });

    testWidgets('alterna visibilidade dos campos de senha individualmente', (tester) async {
      await tester.pumpWidget(buildSubject());

      bool isFieldObscured(String fieldKey) {
        final textField = tester.widget<TextField>(
          find.descendant(of: find.byKey(Key(fieldKey)), matching: find.byType(TextField)),
        );
        return textField.obscureText;
      }

      // Senha atual
      await tester.tap(find.byKey(const Key('toggle_current_password_visibility')));
      await tester.pump();
      expect(isFieldObscured('current_password_field'), isFalse);

      await tester.tap(find.byKey(const Key('toggle_current_password_visibility')));
      await tester.pump();
      expect(isFieldObscured('current_password_field'), isTrue);

      // Nova senha
      await tester.tap(find.byKey(const Key('toggle_new_password_visibility')));
      await tester.pump();
      expect(isFieldObscured('new_password_field'), isFalse);

      await tester.tap(find.byKey(const Key('toggle_new_password_visibility')));
      await tester.pump();
      expect(isFieldObscured('new_password_field'), isTrue);

      // Confirmar nova senha
      await tester.tap(find.byKey(const Key('toggle_confirm_password_visibility')));
      await tester.pump();
      expect(isFieldObscured('confirm_new_password_field'), isFalse);

      await tester.tap(find.byKey(const Key('toggle_confirm_password_visibility')));
      await tester.pump();
      expect(isFieldObscured('confirm_new_password_field'), isTrue);
    });

    testWidgets('valida campos obrigatórios ao tentar submeter vazio', (tester) async {
      await tester.pumpWidget(buildSubject());

      await tester.tap(find.byKey(const Key('change_password_button')));
      await tester.pumpAndSettle();

      expect(find.text('Informe sua senha atual.'), findsOneWidget);
      expect(find.text('Informe a nova senha.'), findsOneWidget);
      expect(find.text('Confirme a nova senha.'), findsOneWidget);
      expect(fakeRepo.changePasswordCallCount, 0);
    });

    testWidgets('valida tamanho mínimo de 8 caracteres da nova senha', (tester) async {
      await tester.pumpWidget(buildSubject());

      await tester.enterText(find.byKey(const Key('current_password_field')), 'senhaAtual1');
      await tester.enterText(find.byKey(const Key('new_password_field')), '1234567');
      await tester.enterText(find.byKey(const Key('confirm_new_password_field')), '1234567');

      await tester.tap(find.byKey(const Key('change_password_button')));
      await tester.pumpAndSettle();

      expect(find.text('A nova senha deve ter no mínimo 8 caracteres.'), findsOneWidget);
      expect(fakeRepo.changePasswordCallCount, 0);
    });

    testWidgets('valida tamanho máximo de 128 caracteres da nova senha', (tester) async {
      await tester.pumpWidget(buildSubject());

      final longPassword = 'a' * 129;
      await tester.enterText(find.byKey(const Key('current_password_field')), 'senhaAtual1');
      await tester.enterText(find.byKey(const Key('new_password_field')), longPassword);
      await tester.enterText(find.byKey(const Key('confirm_new_password_field')), longPassword);

      await tester.tap(find.byKey(const Key('change_password_button')));
      await tester.pumpAndSettle();

      expect(find.text('A nova senha deve ter no máximo 128 caracteres.'), findsOneWidget);
      expect(fakeRepo.changePasswordCallCount, 0);
    });

    testWidgets('valida divergência entre nova senha e confirmação', (tester) async {
      await tester.pumpWidget(buildSubject());

      await tester.enterText(find.byKey(const Key('current_password_field')), 'senhaAtual1');
      await tester.enterText(find.byKey(const Key('new_password_field')), 'novaSenhaForte123');
      await tester.enterText(find.byKey(const Key('confirm_new_password_field')), 'novaSenhaDiferente');

      await tester.tap(find.byKey(const Key('change_password_button')));
      await tester.pumpAndSettle();

      expect(find.text('A confirmação de senha não confere.'), findsOneWidget);
      expect(fakeRepo.changePasswordCallCount, 0);
    });

    testWidgets('bloqueia submissão concorrente e exibe estado de carregamento', (tester) async {
      final completer = Completer<void>();
      fakeRepo.shouldDelay = true;
      fakeRepo.delayCompleter = completer;

      await tester.pumpWidget(buildSubject());

      await tester.enterText(find.byKey(const Key('current_password_field')), 'senhaAtual1');
      await tester.enterText(find.byKey(const Key('new_password_field')), 'novaSenhaValida123');
      await tester.enterText(find.byKey(const Key('confirm_new_password_field')), 'novaSenhaValida123');

      await tester.tap(find.byKey(const Key('change_password_button')));
      await tester.pump();

      // Indicador de progresso ativo
      expect(find.byType(CircularProgressIndicator), findsOneWidget);
      expect(fakeRepo.changePasswordCallCount, 1);

      // Segundo toque ignorado
      await tester.tap(find.byKey(const Key('change_password_button')));
      await tester.pump();
      expect(fakeRepo.changePasswordCallCount, 1);

      // Libera completer
      completer.complete();
      await tester.pumpAndSettle();
    });

    testWidgets('erro 401 INVALID_CREDENTIALS exibe banner amigável e preserva entradas digitadas', (tester) async {
      fakeRepo.apiExceptionToThrow = const ApiException(
        ProblemDetail(
          type: 'https://api.rewit.app/errors/invalid-credentials',
          title: 'Credenciais inválidas',
          status: 401,
          detail: 'Senha atual incorreta.',
          code: 'INVALID_CREDENTIALS',
        ),
      );

      await tester.pumpWidget(buildSubject());

      await tester.enterText(find.byKey(const Key('current_password_field')), 'senhaDigitadaErrada');
      await tester.enterText(find.byKey(const Key('new_password_field')), 'novaSenhaCorreta123');
      await tester.enterText(find.byKey(const Key('confirm_new_password_field')), 'novaSenhaCorreta123');

      await tester.tap(find.byKey(const Key('change_password_button')));
      await tester.pumpAndSettle();

      // Banner de erro visível com mensagem do backend
      expect(find.text('Senha atual incorreta.'), findsOneWidget);

      // Entradas digitadas pelo usuário são preservadas para permitir correção
      expect(find.text('senhaDigitadaErrada'), findsOneWidget);
      expect(find.text('novaSenhaCorreta123'), findsNWidgets(2));
    });

    testWidgets('erro 429 Too Many Requests exibe instrução de espera por retryAfterSeconds', (tester) async {
      fakeRepo.apiExceptionToThrow = const ApiException(
        ProblemDetail(
          type: 'https://api.rewit.app/errors/rate-limit',
          title: 'Muitas requisições',
          status: 429,
          detail: 'Muitas tentativas de alteração de senha.',
          code: 'RATE_LIMIT_EXCEEDED',
        ),
        retryAfterSeconds: 45,
      );

      await tester.pumpWidget(buildSubject());

      await tester.enterText(find.byKey(const Key('current_password_field')), 'senhaAtual');
      await tester.enterText(find.byKey(const Key('new_password_field')), 'novaSenhaCorreta123');
      await tester.enterText(find.byKey(const Key('confirm_new_password_field')), 'novaSenhaCorreta123');

      await tester.tap(find.byKey(const Key('change_password_button')));
      await tester.pumpAndSettle();

      expect(find.text('Muitas tentativas de alteração de senha.'), findsOneWidget);
      expect(find.text('Aguarde 45 segundos antes de tentar novamente.'), findsOneWidget);
    });

    testWidgets('falha de rede exibe mensagem de erro de conexão', (tester) async {
      fakeRepo.networkExceptionToThrow = const NetworkException('Falha de conexão com o servidor.');

      await tester.pumpWidget(buildSubject());

      await tester.enterText(find.byKey(const Key('current_password_field')), 'senhaAtual');
      await tester.enterText(find.byKey(const Key('new_password_field')), 'novaSenhaCorreta123');
      await tester.enterText(find.byKey(const Key('confirm_new_password_field')), 'novaSenhaCorreta123');

      await tester.tap(find.byKey(const Key('change_password_button')));
      await tester.pumpAndSettle();

      expect(find.text('Falha de conexão com o servidor.'), findsOneWidget);
    });

    testWidgets('submissão com sucesso executa callback e limpa campos', (tester) async {
      bool callbackCalled = false;

      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.lightTheme,
          home: Navigator(
            onGenerateRoute: (settings) => MaterialPageRoute(
              builder: (context) => ChangePasswordScreen(
                authNotifier: authNotifier,
                onPasswordChanged: () => callbackCalled = true,
              ),
            ),
          ),
        ),
      );

      await tester.enterText(find.byKey(const Key('current_password_field')), 'senhaAtualAntiga');
      await tester.enterText(find.byKey(const Key('new_password_field')), 'novaSenhaForte123');
      await tester.enterText(find.byKey(const Key('confirm_new_password_field')), 'novaSenhaForte123');

      await tester.tap(find.byKey(const Key('change_password_button')));
      await tester.pumpAndSettle();

      expect(fakeRepo.changePasswordCallCount, 1);
      expect(fakeRepo.lastCurrentPassword, 'senhaAtualAntiga');
      expect(fakeRepo.lastNewPassword, 'novaSenhaForte123');
      expect(callbackCalled, isTrue);

      // Notifier transicionou para Unauthenticated com PASSWORD_CHANGED
      expect(authNotifier.isAuthenticated, isFalse);
      expect(authNotifier.state, isA<Unauthenticated>());
      final unauth = authNotifier.state as Unauthenticated;
      expect(unauth.errorCode, 'PASSWORD_CHANGED');
    });
  });
}
