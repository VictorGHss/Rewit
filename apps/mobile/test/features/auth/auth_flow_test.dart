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
  });
}
