import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:rewit_mobile/app/router/app_router.dart';
import 'package:rewit_mobile/app/theme/app_theme.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/core/network/http_client.dart';
import 'package:rewit_mobile/core/storage/token_storage.dart';
import 'package:rewit_mobile/features/auth/data/models/auth_tokens.dart';
import 'package:rewit_mobile/features/auth/data/models/auth_user_dto.dart';
import 'package:rewit_mobile/features/auth/data/repositories/auth_repository_impl.dart';
import 'package:rewit_mobile/features/auth/domain/entities/auth_state.dart';
import 'package:rewit_mobile/features/auth/domain/repositories/auth_repository.dart';
import 'package:rewit_mobile/features/auth/presentation/screens/login_screen.dart';
import 'package:rewit_mobile/features/auth/presentation/state/auth_notifier.dart';
import 'package:rewit_mobile/features/profile/presentation/screens/account_settings_screen.dart';
import 'package:rewit_mobile/features/profile/presentation/screens/change_password_screen.dart';

class MockHttpHandler extends http.BaseClient {
  final Future<http.StreamedResponse> Function(http.BaseRequest request) handler;

  MockHttpHandler(this.handler);

  @override
  Future<http.StreamedResponse> send(http.BaseRequest request) => handler(request);
}

class TestMockAuthRepo implements AuthRepository {
  bool shouldSucceedReactivate = true;
  bool shouldThrowNetworkOnReactivate = false;
  bool shouldThrowApiErrorOnDeactivate = false;
  int? deactivateStatusCode;
  int? deactivateRetryAfter;
  int deactivateCallCount = 0;
  int reactivateCallCount = 0;
  String? lastReactivateEmail;
  String? lastReactivatePassword;

  @override
  Future<bool> hasStoredSession() async => true;

  @override
  Future<AuthUserDto> getMe() async => const AuthUserDto(
        id: 'usr-lifecycle-1',
        email: 'carla@rewit.com',
        handle: 'carla',
        displayName: 'Carla Dias',
        isVerified: true,
        reputationScore: 85,
      );

  @override
  Future<Authenticated> login({required String email, required String password}) async {
    throw const ApiException(
      ProblemDetail(
        type: 'https://api.rewit.app/errors/invalid-credentials',
        title: 'Credenciais inválidas',
        status: 401,
        detail: 'Credenciais inválidas.',
        code: 'INVALID_CREDENTIALS',
      ),
    );
  }

  @override
  Future<Authenticated> refreshTokens() async => throw UnimplementedError();

  @override
  Future<void> logout() async {}

  @override
  Future<Authenticated> reactivate({
    required String email,
    required String password,
  }) async {
    reactivateCallCount++;
    lastReactivateEmail = email;
    lastReactivatePassword = password;

    if (shouldThrowNetworkOnReactivate) {
      throw const NetworkException('Sem conexão com a internet');
    }

    if (!shouldSucceedReactivate) {
      throw const ApiException(
        ProblemDetail(
          type: 'https://api.rewit.app/errors/invalid-credentials',
          title: 'Credenciais inválidas',
          status: 401,
          detail: 'Credenciais inválidas.',
          code: 'INVALID_CREDENTIALS',
        ),
      );
    }

    return const Authenticated(
      user: AuthUserDto(
        id: 'usr-lifecycle-1',
        email: 'carla@rewit.com',
        handle: 'carla',
        displayName: 'Carla Dias',
        isVerified: true,
        reputationScore: 85,
      ),
      tokens: AuthTokens(
        accessToken: 'access-reactivated-token',
        refreshToken: 'refresh-reactivated-token',
      ),
    );
  }

  @override
  Future<void> deactivateAccount() async {
    deactivateCallCount++;

    if (shouldThrowApiErrorOnDeactivate) {
      final status = deactivateStatusCode ?? 500;
      throw ApiException(
        ProblemDetail(
          type: 'https://api.rewit.app/errors/error',
          title: status == 429 ? 'Muitas Requisições' : 'Erro no Servidor',
          status: status,
          detail: status == 429
              ? 'Limite de requisições excedido.'
              : 'Ocorreu um erro interno no servidor.',
          code: status == 429 ? 'RATE_LIMIT_EXCEEDED' : 'INTERNAL_SERVER_ERROR',
        ),
        retryAfterSeconds: deactivateRetryAfter,
      );
    }
  }

  int changePasswordCallCount = 0;
  String? lastCurrentPassword;
  String? lastNewPassword;
  bool shouldThrowApiErrorOnChangePassword = false;
  int? changePasswordStatusCode;
  String? changePasswordErrorCode;
  String? changePasswordErrorDetail;
  int? changePasswordRetryAfter;

  @override
  Future<void> changePassword({
    required String currentPassword,
    required String newPassword,
  }) async {
    changePasswordCallCount++;
    lastCurrentPassword = currentPassword;
    lastNewPassword = newPassword;
    if (shouldThrowApiErrorOnChangePassword) {
      throw ApiException(
        ProblemDetail(
          type: 'about:blank',
          title: 'Erro',
          status: changePasswordStatusCode ?? 400,
          detail: changePasswordErrorDetail ?? 'Erro ao alterar senha',
          code: changePasswordErrorCode ?? 'BAD_REQUEST',
        ),
        retryAfterSeconds: changePasswordRetryAfter,
      );
    }
  }
}

void main() {
  group('AuthRepositoryImpl - Lifecycle de Conta', () {
    late InMemoryTokenStorage tokenStorage;

    setUp(() {
      tokenStorage = InMemoryTokenStorage();
    });

    test('deactivateAccount com sucesso (204 No Content) limpa tokens locais', () async {
      await tokenStorage.saveTokens(
        accessToken: 'active-access-token',
        refreshToken: 'active-refresh-token',
      );

      final mockClient = MockHttpHandler((request) async {
        expect(request.url.path, '/api/v1/me/deactivate');
        expect(request.method, 'POST');
        expect(request.headers['authorization'], 'Bearer active-access-token');

        return http.StreamedResponse(
          Stream.value([]),
          204,
        );
      });

      final httpClient = RewitHttpClient(
        baseUrl: 'https://api.rewit.test',
        client: mockClient,
        tokenStorage: tokenStorage,
      );

      final repo = AuthRepositoryImpl(
        httpClient: httpClient,
        tokenStorage: tokenStorage,
      );

      await repo.deactivateAccount();

      // Tokens devem ser removidos do armazenamento seguro
      expect(await tokenStorage.getAccessToken(), isNull);
      expect(await tokenStorage.getRefreshToken(), isNull);
    });

    test('deactivateAccount com erro HTTP 500 não limpa tokens e lança ApiException', () async {
      await tokenStorage.saveTokens(
        accessToken: 'active-access-token',
        refreshToken: 'active-refresh-token',
      );

      final mockClient = MockHttpHandler((request) async {
        final problem = {
          'type': 'https://api.rewit.app/errors/internal-error',
          'title': 'Erro Interno',
          'status': 500,
          'detail': 'Falha temporária no processamento',
          'code': 'INTERNAL_ERROR',
        };
        return http.StreamedResponse(
          Stream.value(utf8.encode(jsonEncode(problem))),
          500,
          headers: {'content-type': 'application/problem+json'},
        );
      });

      final httpClient = RewitHttpClient(
        baseUrl: 'https://api.rewit.test',
        client: mockClient,
        tokenStorage: tokenStorage,
      );

      final repo = AuthRepositoryImpl(
        httpClient: httpClient,
        tokenStorage: tokenStorage,
      );

      await expectLater(
        repo.deactivateAccount(),
        throwsA(isA<ApiException>().having((e) => e.statusCode, 'status', 500)),
      );

      // Sessão local é preservada se a requisição falhar no servidor
      expect(await tokenStorage.getAccessToken(), 'active-access-token');
      expect(await tokenStorage.getRefreshToken(), 'active-refresh-token');
    });

    test('deactivateAccount com rate limit 429 propaga retryAfterSeconds', () async {
      await tokenStorage.saveTokens(
        accessToken: 'active-access-token',
        refreshToken: 'active-refresh-token',
      );

      final mockClient = MockHttpHandler((request) async {
        final problem = {
          'type': 'https://api.rewit.app/errors/rate-limit',
          'title': 'Rate limit',
          'status': 429,
          'detail': 'Muitas requisições de desativação.',
          'code': 'RATE_LIMIT_EXCEEDED',
        };
        return http.StreamedResponse(
          Stream.value(utf8.encode(jsonEncode(problem))),
          429,
          headers: {
            'content-type': 'application/problem+json',
            'retry-after': '60',
          },
        );
      });

      final httpClient = RewitHttpClient(
        baseUrl: 'https://api.rewit.test',
        client: mockClient,
        tokenStorage: tokenStorage,
      );

      final repo = AuthRepositoryImpl(
        httpClient: httpClient,
        tokenStorage: tokenStorage,
      );

      await expectLater(
        repo.deactivateAccount(),
        throwsA(
          isA<ApiException>()
              .having((e) => e.statusCode, 'statusCode', 429)
              .having((e) => e.retryAfterSeconds, 'retryAfterSeconds', 60),
        ),
      );

      // Tokens não são removidos
      expect(await tokenStorage.getAccessToken(), 'active-access-token');
    });

    test('reactivate com sucesso (200 OK) salva tokens e retorna Authenticated', () async {
      final mockClient = MockHttpHandler((request) async {
        expect(request.url.path, '/api/v1/auth/reactivate');
        expect(request.method, 'POST');

        final body = jsonDecode(await request.finalize().bytesToString());
        expect(body['email'], 'carla@rewit.com');
        expect(body['password'], 'Senha@123');

        final responsePayload = {
          'user': {
            'id': 'usr-carla-1',
            'email': 'carla@rewit.com',
            'handle': 'carla',
            'displayName': 'Carla Dias',
            'isVerified': true,
            'reputationScore': 85,
          },
          'accessToken': 'new-reactivated-token',
          'refreshToken': 'new-refresh-token',
          'tokenType': 'Bearer',
          'expiresIn': 900,
        };

        return http.StreamedResponse(
          Stream.value(utf8.encode(jsonEncode(responsePayload))),
          200,
          headers: {'content-type': 'application/json'},
        );
      });

      final httpClient = RewitHttpClient(
        baseUrl: 'https://api.rewit.test',
        client: mockClient,
        tokenStorage: tokenStorage,
      );

      final repo = AuthRepositoryImpl(
        httpClient: httpClient,
        tokenStorage: tokenStorage,
      );

      final auth = await repo.reactivate(
        email: 'carla@rewit.com',
        password: 'Senha@123',
      );

      expect(auth.user.handle, 'carla');
      expect(auth.tokens.accessToken, 'new-reactivated-token');
      expect(await tokenStorage.getAccessToken(), 'new-reactivated-token');
      expect(await tokenStorage.getRefreshToken(), 'new-refresh-token');
    });

    test('reactivate com 401 INVALID_CREDENTIALS lança ApiException genérico', () async {
      final mockClient = MockHttpHandler((request) async {
        final problem = {
          'type': 'https://api.rewit.app/errors/invalid-credentials',
          'title': 'Credenciais inválidas',
          'status': 401,
          'detail': 'Credenciais inválidas.',
          'code': 'INVALID_CREDENTIALS',
        };
        return http.StreamedResponse(
          Stream.value(utf8.encode(jsonEncode(problem))),
          401,
          headers: {'content-type': 'application/problem+json'},
        );
      });

      final httpClient = RewitHttpClient(
        baseUrl: 'https://api.rewit.test',
        client: mockClient,
        tokenStorage: tokenStorage,
      );

      final repo = AuthRepositoryImpl(
        httpClient: httpClient,
        tokenStorage: tokenStorage,
      );

      await expectLater(
        repo.reactivate(email: 'carla@rewit.com', password: 'senhaErrada'),
        throwsA(
          isA<ApiException>()
              .having((e) => e.statusCode, 'statusCode', 401)
              .having((e) => e.errorCode, 'errorCode', 'INVALID_CREDENTIALS'),
        ),
      );
    });

    test('reactivate para conta SUSPENDED recebe 401 INVALID_CREDENTIALS genérico', () async {
      final mockClient = MockHttpHandler((request) async {
        // Backend intencionalmente não revela se a conta é SUSPENDED
        final problem = {
          'type': 'https://api.rewit.app/errors/invalid-credentials',
          'title': 'Credenciais inválidas',
          'status': 401,
          'detail': 'Credenciais inválidas.',
          'code': 'INVALID_CREDENTIALS',
        };
        return http.StreamedResponse(
          Stream.value(utf8.encode(jsonEncode(problem))),
          401,
          headers: {'content-type': 'application/problem+json'},
        );
      });

      final httpClient = RewitHttpClient(
        baseUrl: 'https://api.rewit.test',
        client: mockClient,
        tokenStorage: tokenStorage,
      );

      final repo = AuthRepositoryImpl(
        httpClient: httpClient,
        tokenStorage: tokenStorage,
      );

      await expectLater(
        repo.reactivate(email: 'suspensa@rewit.com', password: 'Senha@123'),
        throwsA(
          isA<ApiException>()
              .having((e) => e.statusCode, 'statusCode', 401)
              .having((e) => e.errorCode, 'errorCode', 'INVALID_CREDENTIALS'),
        ),
      );
    });

    test('reactivate para conta DELETED recebe 401 INVALID_CREDENTIALS genérico', () async {
      final mockClient = MockHttpHandler((request) async {
        // Backend intencionalmente não revela se a conta é DELETED
        final problem = {
          'type': 'https://api.rewit.app/errors/invalid-credentials',
          'title': 'Credenciais inválidas',
          'status': 401,
          'detail': 'Credenciais inválidas.',
          'code': 'INVALID_CREDENTIALS',
        };
        return http.StreamedResponse(
          Stream.value(utf8.encode(jsonEncode(problem))),
          401,
          headers: {'content-type': 'application/problem+json'},
        );
      });

      final httpClient = RewitHttpClient(
        baseUrl: 'https://api.rewit.test',
        client: mockClient,
        tokenStorage: tokenStorage,
      );

      final repo = AuthRepositoryImpl(
        httpClient: httpClient,
        tokenStorage: tokenStorage,
      );

      await expectLater(
        repo.reactivate(email: 'excluida@rewit.com', password: 'Senha@123'),
        throwsA(
          isA<ApiException>()
              .having((e) => e.statusCode, 'statusCode', 401)
              .having((e) => e.errorCode, 'errorCode', 'INVALID_CREDENTIALS'),
        ),
      );
    });
  });

  group('AuthNotifier - Lifecycle de Conta', () {
    test('deactivateAccount com sucesso transiciona para Unauthenticated', () async {
      final repo = TestMockAuthRepo();
      final notifier = AuthNotifier(authRepository: repo);
      await notifier.checkAuthStatus();
      expect(notifier.isAuthenticated, isTrue);

      final success = await notifier.deactivateAccount();

      expect(success, isTrue);
      expect(notifier.isAuthenticated, isFalse);
      expect(notifier.state, isA<Unauthenticated>());
      expect(repo.deactivateCallCount, 1);
    });

    test('deactivateAccount com falha restaura estado Authenticated anterior e repassa erro', () async {
      final repo = TestMockAuthRepo()..shouldThrowApiErrorOnDeactivate = true;
      final notifier = AuthNotifier(authRepository: repo);
      await notifier.checkAuthStatus();
      expect(notifier.isAuthenticated, isTrue);

      await expectLater(
        notifier.deactivateAccount(),
        throwsA(isA<ApiException>()),
      );

      // Usuário continua autenticado no app pois a desativação no backend falhou
      expect(notifier.isAuthenticated, isTrue);
      expect(notifier.state, isA<Authenticated>());
    });

    test('deactivateAccount ignora chamadas concorrentes quando já está processando', () async {
      final repo = TestMockAuthRepo();
      final notifier = AuthNotifier(authRepository: repo);
      await notifier.checkAuthStatus();

      // Dispara primeira chamada e imediatamente a segunda
      final future1 = notifier.deactivateAccount();
      final future2 = notifier.deactivateAccount();

      final results = await Future.wait([future1, future2]);

      expect(results[0], isTrue);
      expect(results[1], isFalse); // Segunda chamada bloqueada por double-submit
      expect(repo.deactivateCallCount, 1);
    });

    test('reactivate com sucesso atualiza estado para Authenticated', () async {
      final repo = TestMockAuthRepo();
      final notifier = AuthNotifier(authRepository: repo);

      final success = await notifier.reactivate('carla@rewit.com', 'Senha@123');

      expect(success, isTrue);
      expect(notifier.isAuthenticated, isTrue);
      expect(notifier.state, isA<Authenticated>());
      final auth = notifier.state as Authenticated;
      expect(auth.user.handle, 'carla');
    });

    test('reactivate com 401 INVALID_CREDENTIALS define Unauthenticated com erro genérico', () async {
      final repo = TestMockAuthRepo()..shouldSucceedReactivate = false;
      final notifier = AuthNotifier(authRepository: repo);

      final success = await notifier.reactivate('carla@rewit.com', 'senhaIncorreta');

      expect(success, isFalse);
      expect(notifier.isAuthenticated, isFalse);
      expect(notifier.state, isA<Unauthenticated>());
      final unauth = notifier.state as Unauthenticated;
      expect(unauth.errorCode, 'INVALID_CREDENTIALS');
      expect(unauth.isInvalidCredentials, isTrue);
    });

    test('reactivate ignora chamadas concorrentes quando já está processando', () async {
      final repo = TestMockAuthRepo();
      final notifier = AuthNotifier(authRepository: repo);

      final future1 = notifier.reactivate('carla@rewit.com', 'Senha@123');
      final future2 = notifier.reactivate('carla@rewit.com', 'Senha@123');

      final results = await Future.wait([future1, future2]);

      expect(results[0], isTrue);
      expect(results[1], isFalse);
      expect(repo.reactivateCallCount, 1);
    });
  });

  group('AccountSettingsScreen Widget Tests', () {
    Widget buildSubject(AuthNotifier notifier, {VoidCallback? onDeactivated}) {
      return MaterialApp(
        theme: AppTheme.lightTheme,
        home: AccountSettingsScreen(
          authNotifier: notifier,
          onDeactivated: onDeactivated,
        ),
      );
    }

    testWidgets('renderiza informações da conta e botão Desativar minha conta', (tester) async {
      final repo = TestMockAuthRepo();
      final notifier = AuthNotifier(authRepository: repo);
      await notifier.checkAuthStatus();

      await tester.pumpWidget(buildSubject(notifier));

      expect(find.text('Configurações da Conta'), findsOneWidget);
      expect(find.text('Carla Dias'), findsOneWidget);
      expect(find.text('@carla'), findsOneWidget);
      expect(find.text('Gerenciamento da Conta'), findsOneWidget);
      expect(find.text('Desativar minha conta'), findsOneWidget);
    });

    testWidgets('renderiza seção Segurança com opção de Alterar senha', (tester) async {
      final repo = TestMockAuthRepo();
      final notifier = AuthNotifier(authRepository: repo);
      await notifier.checkAuthStatus();

      await tester.pumpWidget(buildSubject(notifier));

      expect(find.text('Segurança'), findsOneWidget);
      expect(find.text('Alterar senha'), findsOneWidget);
      expect(find.byKey(const Key('change_password_tile')), findsOneWidget);
    });

    testWidgets('tocar em Alterar senha navega para ChangePasswordScreen', (tester) async {
      final repo = TestMockAuthRepo();
      final notifier = AuthNotifier(authRepository: repo);
      await notifier.checkAuthStatus();

      await tester.pumpWidget(buildSubject(notifier));

      await tester.tap(find.byKey(const Key('change_password_tile')));
      await tester.pumpAndSettle();

      expect(find.byType(ChangePasswordScreen), findsOneWidget);
      expect(find.text('Alterar Senha'), findsOneWidget);
    });

    testWidgets('abrir diálogo de desativação exibe mensagem explicativa de encerramento e reativação', (tester) async {
      final repo = TestMockAuthRepo();
      final notifier = AuthNotifier(authRepository: repo);
      await notifier.checkAuthStatus();

      await tester.pumpWidget(buildSubject(notifier));

      await tester.tap(find.text('Desativar minha conta'));
      await tester.pumpAndSettle();

      expect(find.byType(AlertDialog), findsOneWidget);
      expect(
        find.descendant(
          of: find.byType(AlertDialog),
          matching: find.textContaining('Sua conta será desativada e você será desconectado.'),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: find.byType(AlertDialog),
          matching: find.textContaining('Enquanto estiver desativada, você não poderá usar normalmente os recursos da conta.'),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: find.byType(AlertDialog),
          matching: find.textContaining('Para voltar, use o fluxo "Reativar conta" com seu e-mail e senha.'),
        ),
        findsOneWidget,
      );
      expect(find.text('Cancelar'), findsOneWidget);
    });

    testWidgets('cancelar diálogo de desativação não chama repositório', (tester) async {
      final repo = TestMockAuthRepo();
      final notifier = AuthNotifier(authRepository: repo);
      await notifier.checkAuthStatus();

      await tester.pumpWidget(buildSubject(notifier));

      await tester.tap(find.text('Desativar minha conta'));
      await tester.pumpAndSettle();

      await tester.tap(find.text('Cancelar'));
      await tester.pumpAndSettle();

      expect(repo.deactivateCallCount, 0);
      expect(notifier.isAuthenticated, isTrue);
    });

    testWidgets('confirmar diálogo dispara deactivateAccount, feedback e callback onDeactivated', (tester) async {
      final repo = TestMockAuthRepo();
      final notifier = AuthNotifier(authRepository: repo);
      await notifier.checkAuthStatus();

      bool deactivatedCallbackCalled = false;

      await tester.pumpWidget(
        buildSubject(notifier, onDeactivated: () {
          deactivatedCallbackCalled = true;
        }),
      );

      await tester.tap(find.text('Desativar minha conta'));
      await tester.pumpAndSettle();

      // Clica em 'Desativar Conta' no AlertDialog
      final confirmButton = find.widgetWithText(ElevatedButton, 'Desativar Conta');
      await tester.tap(confirmButton);
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));

      expect(repo.deactivateCallCount, 1);
      expect(deactivatedCallbackCalled, isTrue);
      expect(notifier.isAuthenticated, isFalse);
    });

    testWidgets('exibe banner de erro RFC 7807 amigável quando backend falha', (tester) async {
      final repo = TestMockAuthRepo()
        ..shouldThrowApiErrorOnDeactivate = true
        ..deactivateStatusCode = 500;
      final notifier = AuthNotifier(authRepository: repo);
      await notifier.checkAuthStatus();

      await tester.pumpWidget(buildSubject(notifier));

      await tester.tap(find.text('Desativar minha conta'));
      await tester.pumpAndSettle();

      final confirmButton = find.widgetWithText(ElevatedButton, 'Desativar Conta');
      await tester.tap(confirmButton);
      await tester.pumpAndSettle();

      expect(repo.deactivateCallCount, 1);
      // Mensagem de erro RFC 7807 é exibida sem vazar detalhes internos
      expect(find.text('Ocorreu um erro interno no servidor.'), findsOneWidget);
      // Usuário continua autenticado
      expect(notifier.isAuthenticated, isTrue);
    });

    testWidgets('exibe aviso de rate limit com segundos para aguardar quando 429 ocorre', (tester) async {
      final repo = TestMockAuthRepo()
        ..shouldThrowApiErrorOnDeactivate = true
        ..deactivateStatusCode = 429
        ..deactivateRetryAfter = 45;
      final notifier = AuthNotifier(authRepository: repo);
      await notifier.checkAuthStatus();

      await tester.pumpWidget(buildSubject(notifier));

      await tester.tap(find.text('Desativar minha conta'));
      await tester.pumpAndSettle();

      final confirmButton = find.widgetWithText(ElevatedButton, 'Desativar Conta');
      await tester.tap(confirmButton);
      await tester.pumpAndSettle();

      expect(find.text('Limite de requisições excedido.'), findsOneWidget);
      expect(find.text('Aguarde 45 segundos antes de tentar novamente.'), findsOneWidget);
    });
  });

  group('Navigation & AppRouter - Configurações de Conta', () {
    testWidgets('abrir configurações da conta pelo ícone no AppBar da HomeScreen', (tester) async {
      final repo = TestMockAuthRepo();
      final notifier = AuthNotifier(authRepository: repo);
      await notifier.checkAuthStatus();

      final router = AppRouter(authNotifier: notifier);

      await tester.pumpWidget(MaterialApp(
        theme: AppTheme.lightTheme,
        initialRoute: AppRouter.home,
        onGenerateRoute: router.onGenerateRoute,
      ));
      await tester.pumpAndSettle();

      // Verifica ícone de configurações
      final settingsIcon = find.byIcon(Icons.settings_outlined);
      expect(settingsIcon, findsOneWidget);

      await tester.tap(settingsIcon);
      await tester.pumpAndSettle();

      // Navegou para AccountSettingsScreen
      expect(find.text('Configurações da Conta'), findsOneWidget);
      expect(find.text('Desativar minha conta'), findsOneWidget);
    });

    testWidgets('abrir configurações da conta pelo botão na ProfilePlaceholderScreen', (tester) async {
      final repo = TestMockAuthRepo();
      final notifier = AuthNotifier(authRepository: repo);
      await notifier.checkAuthStatus();

      final router = AppRouter(authNotifier: notifier);

      await tester.pumpWidget(MaterialApp(
        theme: AppTheme.lightTheme,
        initialRoute: AppRouter.home,
        onGenerateRoute: router.onGenerateRoute,
      ));
      await tester.pumpAndSettle();

      // Seleciona aba Perfil
      await tester.tap(find.text('Perfil'));
      await tester.pumpAndSettle();

      expect(find.text('Perfil de Usuário'), findsOneWidget);
      final configButton = find.widgetWithText(OutlinedButton, 'Configurações da Conta');
      expect(configButton, findsOneWidget);

      await tester.tap(configButton);
      await tester.pumpAndSettle();

      expect(find.text('Configurações da Conta'), findsWidgets);
      expect(find.text('Desativar minha conta'), findsOneWidget);
    });
  });

  group('LoginScreen - Reativação de Conta', () {
    Widget buildSubject(AuthNotifier notifier, {VoidCallback? onLoginSuccess}) {
      return MaterialApp(
        theme: AppTheme.lightTheme,
        home: LoginScreen(
          authNotifier: notifier,
          onLoginSuccess: onLoginSuccess,
        ),
      );
    }

    testWidgets('quando credenciais são rejeitadas, exibe sugestão e botão de reativação', (tester) async {
      final repo = TestMockAuthRepo();
      final notifier = AuthNotifier(authRepository: repo);

      await tester.pumpWidget(buildSubject(notifier));

      // Simula erro de credenciais executando login que falha com INVALID_CREDENTIALS
      await notifier.login('usuario@rewit.com', 'senhaIncorreta');
      await tester.pumpAndSettle();

      expect(find.text('Sua conta foi desativada?'), findsOneWidget);
      expect(find.text('Reativar Conta'), findsOneWidget);
    });

    testWidgets('botão Reativar conta desativada abre diálogo de confirmação com e-mail preenchido', (tester) async {
      final repo = TestMockAuthRepo();
      final notifier = AuthNotifier(authRepository: repo);

      await tester.pumpWidget(buildSubject(notifier));

      // Preenche os campos
      await tester.enterText(find.byType(TextFormField).first, 'carla@rewit.com');
      await tester.enterText(find.byType(TextFormField).last, 'Senha@123');

      // Toca no link de reativação
      await tester.tap(find.text('Reativar conta desativada'));
      await tester.pumpAndSettle();

      expect(find.text('Reativar Conta'), findsOneWidget);
      expect(
        find.textContaining('Deseja reativar a conta associada ao e-mail "carla@rewit.com"?'),
        findsOneWidget,
      );
    });

    testWidgets('confirmar reativação no diálogo executa reativação e notifica sucesso', (tester) async {
      final repo = TestMockAuthRepo()..shouldSucceedReactivate = true;
      final notifier = AuthNotifier(authRepository: repo);

      bool successNotified = false;

      await tester.pumpWidget(
        buildSubject(notifier, onLoginSuccess: () {
          successNotified = true;
        }),
      );

      await tester.enterText(find.byType(TextFormField).first, 'carla@rewit.com');
      await tester.enterText(find.byType(TextFormField).last, 'Senha@123');

      await tester.tap(find.text('Reativar conta desativada'));
      await tester.pumpAndSettle();

      // Clica em 'Reativar' no diálogo
      await tester.tap(find.widgetWithText(ElevatedButton, 'Reativar'));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));

      expect(repo.reactivateCallCount, 1);
      expect(repo.lastReactivateEmail, 'carla@rewit.com');
      expect(repo.lastReactivatePassword, 'Senha@123');
      expect(successNotified, isTrue);
      expect(notifier.isAuthenticated, isTrue);
    });

    testWidgets('reativação com falha 401 exibe erro genérico (SUSPENDED ou DELETED indistinguíveis)', (tester) async {
      final repo = TestMockAuthRepo()..shouldSucceedReactivate = false;
      final notifier = AuthNotifier(authRepository: repo);

      await tester.pumpWidget(buildSubject(notifier));

      await tester.enterText(find.byType(TextFormField).first, 'suspensa@rewit.com');
      await tester.enterText(find.byType(TextFormField).last, 'Senha@123');

      await tester.tap(find.text('Reativar conta desativada'));
      await tester.pumpAndSettle();

      await tester.tap(find.widgetWithText(ElevatedButton, 'Reativar'));
      await tester.pumpAndSettle();

      expect(repo.reactivateCallCount, 1);
      // Erro genérico é exibido no banner de erro
      expect(find.text('Credenciais inválidas.'), findsOneWidget);
      expect(notifier.isAuthenticated, isFalse);
    });
  });
}
