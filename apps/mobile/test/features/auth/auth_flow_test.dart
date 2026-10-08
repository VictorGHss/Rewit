import 'dart:convert';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/core/network/http_client.dart';
import 'package:rewit_mobile/core/storage/token_storage.dart';
import 'package:rewit_mobile/features/auth/data/models/auth_tokens.dart';
import 'package:rewit_mobile/features/auth/data/models/auth_user_dto.dart';
import 'package:rewit_mobile/features/auth/data/repositories/auth_repository_impl.dart';
import 'package:rewit_mobile/features/auth/domain/entities/auth_state.dart';
import 'package:rewit_mobile/features/auth/domain/repositories/auth_repository.dart';
import 'package:rewit_mobile/features/auth/presentation/state/auth_notifier.dart';

class MockHttpHandler extends http.BaseClient {
  final Future<http.StreamedResponse> Function(http.BaseRequest request) handler;

  MockHttpHandler(this.handler);

  @override
  Future<http.StreamedResponse> send(http.BaseRequest request) => handler(request);
}

class FakeAuthRepository implements AuthRepository {
  bool storedSession = false;
  AuthUserDto? meResponse;
  Authenticated? loginResponse;
  Authenticated? refreshResponse;
  bool shouldFailGetMeWith401 = false;
  bool shouldFailRefresh = false;
  bool shouldThrowNetworkOnLogin = false;
  bool shouldThrowApiErrorOnLogin = false;
  bool logoutCalled = false;

  @override
  Future<bool> hasStoredSession() async => storedSession;

  @override
  Future<AuthUserDto> getMe() async {
    if (shouldFailGetMeWith401) {
      throw const ApiException(ProblemDetail(
        type: 'about:blank',
        title: 'Não autorizado',
        status: 401,
        detail: 'Token inválido',
      ));
    }
    return meResponse ??
        const AuthUserDto(
          id: '123',
          email: 'user@test.com',
          handle: 'testuser',
          displayName: 'Test User',
        );
  }

  @override
  Future<Authenticated> login({required String email, required String password}) async {
    if (shouldThrowNetworkOnLogin) {
      throw const NetworkException('Sem conexão com a internet');
    }
    if (shouldThrowApiErrorOnLogin) {
      throw const ApiException(ProblemDetail(
        type: 'about:blank',
        title: 'Credenciais inválidas',
        status: 401,
        detail: 'E-mail ou senha incorretos',
        code: 'INVALID_CREDENTIALS',
      ));
    }
    return loginResponse ??
        const Authenticated(
          user: AuthUserDto(
            id: '123',
            email: 'user@test.com',
            handle: 'testuser',
            displayName: 'Test User',
          ),
          tokens: AuthTokens(
            accessToken: 'token-123',
            refreshToken: 'refresh-123',
          ),
        );
  }

  @override
  Future<Authenticated> refreshTokens() async {
    if (shouldFailRefresh) {
      throw const ApiException(ProblemDetail(
        type: 'about:blank',
        title: 'Sessão Expirada',
        status: 401,
        detail: 'Refresh token revogado',
      ));
    }
    return refreshResponse ??
        const Authenticated(
          user: AuthUserDto(
            id: '123',
            email: 'user@test.com',
            handle: 'testuser',
            displayName: 'Test User',
          ),
          tokens: AuthTokens(
            accessToken: 'token-refreshed',
            refreshToken: 'refresh-refreshed',
          ),
        );
  }

  @override
  Future<void> logout() async {
    logoutCalled = true;
    storedSession = false;
  }

  bool reactivateCalled = false;
  bool deactivateCalled = false;
  bool shouldFailReactivateWithInvalidCredentials = false;
  bool shouldThrowNetworkOnReactivate = false;
  bool shouldThrowApiErrorOnDeactivate = false;

  @override
  Future<Authenticated> reactivate({required String email, required String password}) async {
    reactivateCalled = true;
    if (shouldThrowNetworkOnReactivate) {
      throw const NetworkException('Sem conexão com a internet');
    }
    if (shouldFailReactivateWithInvalidCredentials) {
      throw const ApiException(ProblemDetail(
        type: 'about:blank',
        title: 'Credenciais inválidas',
        status: 401,
        detail: 'Credenciais inválidas.',
        code: 'INVALID_CREDENTIALS',
      ));
    }
    return loginResponse ??
        const Authenticated(
          user: AuthUserDto(
            id: '123',
            email: 'user@test.com',
            handle: 'testuser',
            displayName: 'Test User',
          ),
          tokens: AuthTokens(
            accessToken: 'token-reactivated',
            refreshToken: 'refresh-reactivated',
          ),
        );
  }

  @override
  Future<void> deactivateAccount() async {
    deactivateCalled = true;
    if (shouldThrowApiErrorOnDeactivate) {
      throw const ApiException(ProblemDetail(
        type: 'about:blank',
        title: 'Erro ao desativar',
        status: 500,
        detail: 'Falha interna ao desativar conta',
      ));
    }
  }

  bool changePasswordCalled = false;
  String? lastCurrentPassword;
  String? lastNewPassword;
  bool shouldThrowApiErrorOnChangePassword = false;
  bool shouldThrowNetworkOnChangePassword = false;
  int? changePasswordStatusCode;
  String? changePasswordErrorCode;
  String? changePasswordErrorDetail;
  int? changePasswordRetryAfter;

  @override
  Future<void> changePassword({
    required String currentPassword,
    required String newPassword,
  }) async {
    changePasswordCalled = true;
    lastCurrentPassword = currentPassword;
    lastNewPassword = newPassword;
    if (shouldThrowNetworkOnChangePassword) {
      throw const NetworkException('Sem conexão com a internet');
    }
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
  group('AuthRepositoryImpl', () {
    late InMemoryTokenStorage tokenStorage;

    setUp(() {
      tokenStorage = InMemoryTokenStorage();
    });

    test('login com sucesso armazena tokens e retorna Authenticated', () async {
      final mockClient = MockHttpHandler((request) async {
        final responsePayload = {
          'user': {
            'id': 'user-uuid-1',
            'email': 'lucas@rewit.com',
            'handle': 'lucas',
            'displayName': 'Lucas Silva',
            'isVerified': true,
            'isAnonymousDefault': false,
            'reputationScore': 42,
          },
          'accessToken': 'jwt-access-token',
          'refreshToken': 'jwt-refresh-token',
          'tokenType': 'Bearer',
          'expiresIn': 900,
        };
        return http.StreamedResponse(
          Stream.value(utf8.encode(jsonEncode(responsePayload))),
          200,
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

      final result = await repo.login(email: 'Lucas@rewit.com', password: 'Password@123');

      expect(result.user.id, 'user-uuid-1');
      expect(result.user.handle, 'lucas');
      expect(result.user.reputationScore, 42);
      expect(result.tokens.accessToken, 'jwt-access-token');

      // Verifica que tokens foram salvos no armazenamento
      expect(await tokenStorage.getAccessToken(), 'jwt-access-token');
      expect(await tokenStorage.getRefreshToken(), 'jwt-refresh-token');
    });

    test('refreshTokens renova tokens e atualiza armazenamento', () async {
      await tokenStorage.saveTokens(
        accessToken: 'expired-access-token',
        refreshToken: 'current-refresh-token',
      );

      final mockClient = MockHttpHandler((request) async {
        final responsePayload = {
          'user': {
            'id': 'user-uuid-1',
            'email': 'lucas@rewit.com',
            'handle': 'lucas',
            'displayName': 'Lucas Silva',
          },
          'accessToken': 'new-access-token',
          'refreshToken': 'new-refresh-token',
          'tokenType': 'Bearer',
          'expiresIn': 900,
        };
        return http.StreamedResponse(
          Stream.value(utf8.encode(jsonEncode(responsePayload))),
          200,
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

      final result = await repo.refreshTokens();

      expect(result.tokens.accessToken, 'new-access-token');
      expect(await tokenStorage.getAccessToken(), 'new-access-token');
      expect(await tokenStorage.getRefreshToken(), 'new-refresh-token');
    });

    test('refreshTokens sem token no storage lança ApiException 401', () async {
      final httpClient = RewitHttpClient(
        baseUrl: 'https://api.rewit.test',
        tokenStorage: tokenStorage,
      );

      final repo = AuthRepositoryImpl(
        httpClient: httpClient,
        tokenStorage: tokenStorage,
      );

      await expectLater(
        repo.refreshTokens(),
        throwsA(isA<ApiException>().having((e) => e.statusCode, 'statusCode', 401)),
      );
    });

    test('logout limpa armazenamento local mesmo se requisição falhar', () async {
      await tokenStorage.saveTokens(
        accessToken: 'access-token',
        refreshToken: 'refresh-token',
      );

      final mockClient = MockHttpHandler((request) async {
        throw http.ClientException('Falha de conexão');
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

      await repo.logout();

      expect(await tokenStorage.getAccessToken(), isNull);
      expect(await tokenStorage.getRefreshToken(), isNull);
    });

    test('changePassword envia POST para /api/v1/me/password com body correto e limpa tokens no sucesso', () async {
      await tokenStorage.saveTokens(
        accessToken: 'valid-access-token',
        refreshToken: 'valid-refresh-token',
      );

      http.BaseRequest? capturedRequest;
      String? capturedBody;

      final mockClient = MockHttpHandler((request) async {
        capturedRequest = request;
        if (request is http.Request) {
          capturedBody = request.body;
        }
        return http.StreamedResponse(
          Stream.value(utf8.encode('')),
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

      await repo.changePassword(
        currentPassword: 'current-pwd-123',
        newPassword: 'new-pwd-secret-456',
      );

      expect(capturedRequest?.url.path, '/api/v1/me/password');
      expect(capturedRequest?.method, 'POST');
      expect(capturedRequest?.headers['authorization'], 'Bearer valid-access-token');

      final bodyMap = jsonDecode(capturedBody!) as Map<String, dynamic>;
      expect(bodyMap['currentPassword'], 'current-pwd-123');
      expect(bodyMap['newPassword'], 'new-pwd-secret-456');

      expect(await tokenStorage.getAccessToken(), isNull);
      expect(await tokenStorage.getRefreshToken(), isNull);
    });

    test('changePassword com erro 401 INVALID_CREDENTIALS não limpa tokens e propaga ApiException', () async {
      await tokenStorage.saveTokens(
        accessToken: 'valid-access-token',
        refreshToken: 'valid-refresh-token',
      );

      final errorPayload = {
        'type': 'https://api.rewit.app/errors/invalid-credentials',
        'title': 'Credenciais inválidas',
        'status': 401,
        'detail': 'Senha atual incorreta.',
        'code': 'INVALID_CREDENTIALS',
      };

      final mockClient = MockHttpHandler((request) async {
        return http.StreamedResponse(
          Stream.value(utf8.encode(jsonEncode(errorPayload))),
          401,
          headers: {'content-type': 'application/problem+json'},
        );
      });

      bool sessionExpiredCalled = false;
      final httpClient = RewitHttpClient(
        baseUrl: 'https://api.rewit.test',
        client: mockClient,
        tokenStorage: tokenStorage,
        onSessionExpired: () => sessionExpiredCalled = true,
      );

      final repo = AuthRepositoryImpl(
        httpClient: httpClient,
        tokenStorage: tokenStorage,
      );

      await expectLater(
        repo.changePassword(
          currentPassword: 'wrong-password',
          newPassword: 'new-pwd-123',
        ),
        throwsA(
          isA<ApiException>()
              .having((e) => e.statusCode, 'statusCode', 401)
              .having((e) => e.errorCode, 'errorCode', 'INVALID_CREDENTIALS')
              .having((e) => e.detail, 'detail', 'Senha atual incorreta.'),
        ),
      );

      expect(sessionExpiredCalled, isFalse);
      expect(await tokenStorage.getAccessToken(), 'valid-access-token');
      expect(await tokenStorage.getRefreshToken(), 'valid-refresh-token');
    });

    test('changePassword com erro 401 UNAUTHORIZED (token expirado) invoca onSessionExpired', () async {
      await tokenStorage.saveTokens(
        accessToken: 'expired-access-token',
        refreshToken: 'valid-refresh-token',
      );

      final errorPayload = {
        'type': 'https://api.rewit.app/errors/unauthorized',
        'title': 'Não autorizado',
        'status': 401,
        'detail': 'Token expirado ou revogado.',
        'code': 'UNAUTHORIZED',
      };

      final mockClient = MockHttpHandler((request) async {
        return http.StreamedResponse(
          Stream.value(utf8.encode(jsonEncode(errorPayload))),
          401,
          headers: {'content-type': 'application/problem+json'},
        );
      });

      bool sessionExpiredCalled = false;
      final httpClient = RewitHttpClient(
        baseUrl: 'https://api.rewit.test',
        client: mockClient,
        tokenStorage: tokenStorage,
        onSessionExpired: () => sessionExpiredCalled = true,
      );

      final repo = AuthRepositoryImpl(
        httpClient: httpClient,
        tokenStorage: tokenStorage,
      );

      await expectLater(
        repo.changePassword(
          currentPassword: 'any-password',
          newPassword: 'new-pwd-123',
        ),
        throwsA(
          isA<ApiException>()
              .having((e) => e.statusCode, 'statusCode', 401)
              .having((e) => e.errorCode, 'errorCode', 'UNAUTHORIZED'),
        ),
      );

      expect(sessionExpiredCalled, isTrue);
    });

    test('changePassword com erro 400 de validação lança ApiException e mantém tokens intactos', () async {
      await tokenStorage.saveTokens(
        accessToken: 'valid-access-token',
        refreshToken: 'valid-refresh-token',
      );

      final errorPayload = {
        'type': 'https://api.rewit.app/errors/validation-error',
        'title': 'Dados inválidos',
        'status': 400,
        'detail': 'A nova senha deve ter entre 8 e 128 caracteres.',
        'code': 'VALIDATION_ERROR',
      };

      final mockClient = MockHttpHandler((request) async {
        return http.StreamedResponse(
          Stream.value(utf8.encode(jsonEncode(errorPayload))),
          400,
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
        repo.changePassword(
          currentPassword: 'valid-current',
          newPassword: 'short',
        ),
        throwsA(
          isA<ApiException>()
              .having((e) => e.statusCode, 'statusCode', 400)
              .having((e) => e.errorCode, 'errorCode', 'VALIDATION_ERROR'),
        ),
      );

      expect(await tokenStorage.getAccessToken(), 'valid-access-token');
    });

    test('changePassword com erro 429 Too Many Requests lança ApiException com retryAfterSeconds', () async {
      await tokenStorage.saveTokens(
        accessToken: 'valid-access-token',
        refreshToken: 'valid-refresh-token',
      );

      final errorPayload = {
        'type': 'https://api.rewit.app/errors/rate-limit',
        'title': 'Muitas requisições',
        'status': 429,
        'detail': 'Limite de tentativas excedido.',
        'code': 'RATE_LIMIT_EXCEEDED',
      };

      final mockClient = MockHttpHandler((request) async {
        return http.StreamedResponse(
          Stream.value(utf8.encode(jsonEncode(errorPayload))),
          429,
          headers: {
            'content-type': 'application/problem+json',
            'retry-after': '45',
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
        repo.changePassword(
          currentPassword: 'current-pwd',
          newPassword: 'new-valid-pwd',
        ),
        throwsA(
          isA<ApiException>()
              .having((e) => e.statusCode, 'statusCode', 429)
              .having((e) => e.retryAfterSeconds, 'retryAfterSeconds', 45),
        ),
      );
    });

    test('changePassword com falha de rede lança NetworkException', () async {
      await tokenStorage.saveTokens(
        accessToken: 'valid-access-token',
        refreshToken: 'valid-refresh-token',
      );

      final mockClient = MockHttpHandler((request) async {
        throw http.ClientException('Falha de conexão com a internet');
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
        repo.changePassword(
          currentPassword: 'current-pwd',
          newPassword: 'new-valid-pwd',
        ),
        throwsA(isA<NetworkException>()),
      );
    });
  });

  group('AuthNotifier', () {
    test('estado inicial é AuthInitial', () {
      final repo = FakeAuthRepository();
      final notifier = AuthNotifier(authRepository: repo);

      expect(notifier.state, isA<AuthInitial>());
      expect(notifier.isAuthenticated, isFalse);
    });

    test('checkAuthStatus sem sessão armazenada define Unauthenticated', () async {
      final repo = FakeAuthRepository()..storedSession = false;
      final notifier = AuthNotifier(authRepository: repo);

      await notifier.checkAuthStatus();

      expect(notifier.state, isA<Unauthenticated>());
      expect(notifier.isAuthenticated, isFalse);
    });

    test('checkAuthStatus com sessão válida define Authenticated', () async {
      final repo = FakeAuthRepository()
        ..storedSession = true
        ..meResponse = const AuthUserDto(
          id: '99',
          email: 'maria@rewit.com',
          handle: 'maria',
          displayName: 'Maria Oliveira',
        );
      final notifier = AuthNotifier(authRepository: repo);

      await notifier.checkAuthStatus();

      expect(notifier.state, isA<Authenticated>());
      final auth = notifier.state as Authenticated;
      expect(auth.user.handle, 'maria');
      expect(notifier.isAuthenticated, isTrue);
    });

    test('checkAuthStatus tenta refresh quando getMe retorna 401', () async {
      final repo = FakeAuthRepository()
        ..storedSession = true
        ..shouldFailGetMeWith401 = true
        ..refreshResponse = const Authenticated(
          user: AuthUserDto(
            id: '99',
            email: 'maria@rewit.com',
            handle: 'maria',
            displayName: 'Maria Oliveira',
          ),
          tokens: AuthTokens(accessToken: 'new-acc', refreshToken: 'new-ref'),
        );
      final notifier = AuthNotifier(authRepository: repo);

      await notifier.checkAuthStatus();

      expect(notifier.state, isA<Authenticated>());
    });

    test('checkAuthStatus limpa sessão quando getMe e refresh falham', () async {
      final repo = FakeAuthRepository()
        ..storedSession = true
        ..shouldFailGetMeWith401 = true
        ..shouldFailRefresh = true;
      final notifier = AuthNotifier(authRepository: repo);

      await notifier.checkAuthStatus();

      expect(notifier.state, isA<Unauthenticated>());
      expect(repo.logoutCalled, isTrue);
    });

    test('login bem sucedido transiciona para Authenticated e retorna true', () async {
      final repo = FakeAuthRepository();
      final notifier = AuthNotifier(authRepository: repo);

      final success = await notifier.login('user@test.com', 'password123');

      expect(success, isTrue);
      expect(notifier.state, isA<Authenticated>());
    });

    test('login com erro de credenciais transiciona para Unauthenticated com mensagem de erro', () async {
      final repo = FakeAuthRepository()..shouldThrowApiErrorOnLogin = true;
      final notifier = AuthNotifier(authRepository: repo);

      final success = await notifier.login('user@test.com', 'wrongpassword');

      expect(success, isFalse);
      expect(notifier.state, isA<Unauthenticated>());
      final unauth = notifier.state as Unauthenticated;
      expect(unauth.errorMessage, 'E-mail ou senha incorretos');
      expect(unauth.errorCode, 'INVALID_CREDENTIALS');
    });

    test('logout transiciona para Unauthenticated', () async {
      final repo = FakeAuthRepository();
      final notifier = AuthNotifier(authRepository: repo);
      await notifier.login('user@test.com', 'password123');
      expect(notifier.isAuthenticated, isTrue);

      await notifier.logout();

      expect(notifier.isAuthenticated, isFalse);
      expect(notifier.state, isA<Unauthenticated>());
    });

    test('handleSessionExpired transiciona para Unauthenticated com mensagem explicativa', () {
      final repo = FakeAuthRepository();
      final notifier = AuthNotifier(authRepository: repo);

      notifier.handleSessionExpired();

      expect(notifier.state, isA<Unauthenticated>());
      final unauth = notifier.state as Unauthenticated;
      expect(unauth.errorMessage, contains('expirou'));
      expect(unauth.errorCode, 'SESSION_EXPIRED');
    });

    test('changePassword com sucesso transiciona para Unauthenticated com PASSWORD_CHANGED', () async {
      final repo = FakeAuthRepository();
      final notifier = AuthNotifier(authRepository: repo);
      await notifier.login('user@test.com', 'pwd123');
      expect(notifier.isAuthenticated, isTrue);

      final success = await notifier.changePassword(
        currentPassword: 'pwd123',
        newPassword: 'newSecretPassword1',
      );

      expect(success, isTrue);
      expect(repo.changePasswordCalled, isTrue);
      expect(repo.lastCurrentPassword, 'pwd123');
      expect(repo.lastNewPassword, 'newSecretPassword1');
      expect(notifier.isAuthenticated, isFalse);
      expect(notifier.state, isA<Unauthenticated>());
      final unauth = notifier.state as Unauthenticated;
      expect(unauth.errorCode, 'PASSWORD_CHANGED');
      expect(unauth.errorMessage, 'Senha alterada. Entre novamente com sua nova senha.');
    });

    test('changePassword com senha atual incorreta (INVALID_CREDENTIALS) preserva Authenticated e relança exceção', () async {
      final repo = FakeAuthRepository()
        ..shouldThrowApiErrorOnChangePassword = true
        ..changePasswordStatusCode = 401
        ..changePasswordErrorCode = 'INVALID_CREDENTIALS'
        ..changePasswordErrorDetail = 'Senha atual incorreta.';
      final notifier = AuthNotifier(authRepository: repo);
      await notifier.login('user@test.com', 'pwd123');
      expect(notifier.isAuthenticated, isTrue);
      final currentUser = (notifier.state as Authenticated).user;

      await expectLater(
        notifier.changePassword(
          currentPassword: 'wrong-password',
          newPassword: 'newSecretPassword1',
        ),
        throwsA(isA<ApiException>().having((e) => e.errorCode, 'errorCode', 'INVALID_CREDENTIALS')),
      );

      // Usuário continua autenticado!
      expect(notifier.isAuthenticated, isTrue);
      expect(notifier.state, isA<Authenticated>());
      expect((notifier.state as Authenticated).user.id, currentUser.id);
    });

    test('changePassword com sessão expirada (401 sem INVALID_CREDENTIALS) transiciona para Unauthenticated SESSION_EXPIRED', () async {
      final repo = FakeAuthRepository()
        ..shouldThrowApiErrorOnChangePassword = true
        ..changePasswordStatusCode = 401
        ..changePasswordErrorCode = 'UNAUTHORIZED'
        ..changePasswordErrorDetail = 'Token inválido ou expirado.';
      final notifier = AuthNotifier(authRepository: repo);
      await notifier.login('user@test.com', 'pwd123');
      expect(notifier.isAuthenticated, isTrue);

      await expectLater(
        notifier.changePassword(
          currentPassword: 'pwd123',
          newPassword: 'newSecretPassword1',
        ),
        throwsA(isA<ApiException>()),
      );

      expect(notifier.isAuthenticated, isFalse);
      expect(notifier.state, isA<Unauthenticated>());
      final unauth = notifier.state as Unauthenticated;
      expect(unauth.errorCode, 'SESSION_EXPIRED');
    });

    test('changePassword com erro de rede ou 400 preserva Authenticated e relança exceção', () async {
      final repo = FakeAuthRepository()
        ..shouldThrowNetworkOnChangePassword = true;
      final notifier = AuthNotifier(authRepository: repo);
      await notifier.login('user@test.com', 'pwd123');
      expect(notifier.isAuthenticated, isTrue);

      await expectLater(
        notifier.changePassword(
          currentPassword: 'pwd123',
          newPassword: 'newSecretPassword1',
        ),
        throwsA(isA<NetworkException>()),
      );

      expect(notifier.isAuthenticated, isTrue);
    });
  });
}
