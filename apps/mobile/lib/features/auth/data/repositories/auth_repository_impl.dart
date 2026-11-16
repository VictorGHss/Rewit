import 'dart:convert';
import 'package:http/http.dart' as http;
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/core/network/api_endpoints.dart';
import 'package:rewit_mobile/core/network/http_client.dart';
import 'package:rewit_mobile/core/storage/session_token_coordinator.dart';
import 'package:rewit_mobile/core/storage/token_storage.dart';
import 'package:rewit_mobile/features/auth/data/models/auth_tokens.dart';
import 'package:rewit_mobile/features/auth/data/models/auth_user_dto.dart';
import 'package:rewit_mobile/features/auth/domain/entities/auth_state.dart';
import 'package:rewit_mobile/features/auth/domain/repositories/auth_repository.dart';
import 'package:rewit_mobile/features/auth/domain/services/social_sign_in_service.dart';

/// Implementação de [AuthRepository] consumindo a API REST do Rewit.
class AuthRepositoryImpl implements AttemptScopedAuthRepository {
  final RewitHttpClient _httpClient;
  final TokenStorage _tokenStorage;
  final SessionTokenCoordinator _tokenCoordinator;

  AuthRepositoryImpl({
    required RewitHttpClient httpClient,
    required TokenStorage tokenStorage,
    SessionTokenCoordinator? tokenCoordinator,
  })  : _httpClient = httpClient,
        _tokenStorage = tokenStorage,
        _tokenCoordinator =
            tokenCoordinator ?? SessionTokenCoordinator(storage: tokenStorage);

  @override
  SessionTokenCoordinator get tokenCoordinator => _tokenCoordinator;

  @override
  Future<Authenticated> login({
    required String email,
    required String password,
  }) =>
      loginWithAttempt(email: email, password: password);

  @override
  Future<Authenticated> loginWithAttempt({
    required String email,
    required String password,
    int? authenticationAttemptId,
  }) {
    return _runAttemptScopedAuth(
      () => _httpClient.post(
        ApiEndpoints.authLogin,
        body: {
          'email': email.trim().toLowerCase(),
          'password': password,
        },
        requiresAuth: false,
      ),
      authenticationAttemptId: authenticationAttemptId,
    );
  }

  @override
  Future<Authenticated> register({
    required String displayName,
    required String handle,
    required String email,
    required String password,
  }) =>
      registerWithAttempt(
        displayName: displayName,
        handle: handle,
        email: email,
        password: password,
      );

  @override
  Future<Authenticated> registerWithAttempt({
    required String displayName,
    required String handle,
    required String email,
    required String password,
    int? authenticationAttemptId,
  }) {
    return _runAttemptScopedAuth(
      () => _httpClient.post(
        ApiEndpoints.authRegister,
        body: {
          'displayName': displayName.trim(),
          'handle': handle.trim().toLowerCase(),
          'email': email.trim().toLowerCase(),
          'password': password,
        },
        requiresAuth: false,
      ),
      authenticationAttemptId: authenticationAttemptId,
    );
  }

  @override
  Future<String> requestSocialNonce() async {
    final response = await _httpClient.post(
      ApiEndpoints.authSocialNonce,
      requiresAuth: false,
    );

    final data = jsonDecode(response.body) as Map<String, dynamic>;
    return data['nonce'] as String;
  }

  @override
  Future<Authenticated> signInWithSocial({
    required SocialAuthProvider provider,
    required String idToken,
    required String nonce,
  }) =>
      signInWithSocialAttempt(
        provider: provider,
        idToken: idToken,
        nonce: nonce,
      );

  @override
  Future<Authenticated> signInWithSocialAttempt({
    required SocialAuthProvider provider,
    required String idToken,
    required String nonce,
    int? authenticationAttemptId,
  }) {
    return _runAttemptScopedAuth(
      () => _httpClient.post(
        ApiEndpoints.authSocial(provider.apiPath),
        body: {
          'idToken': idToken,
          'nonce': nonce,
        },
        requiresAuth: false,
      ),
      authenticationAttemptId: authenticationAttemptId,
    );
  }

  @override
  Future<Authenticated> reactivateWithSocial({
    required SocialAuthProvider provider,
    required String idToken,
    required String nonce,
  }) =>
      reactivateWithSocialAttempt(
        provider: provider,
        idToken: idToken,
        nonce: nonce,
      );

  @override
  Future<Authenticated> reactivateWithSocialAttempt({
    required SocialAuthProvider provider,
    required String idToken,
    required String nonce,
    int? authenticationAttemptId,
  }) {
    return _runAttemptScopedAuth(
      () => _httpClient.post(
        ApiEndpoints.authSocialReactivate(provider.apiPath),
        body: {
          'idToken': idToken,
          'nonce': nonce,
        },
        requiresAuth: false,
      ),
      authenticationAttemptId: authenticationAttemptId,
    );
  }

  Future<Authenticated> _runAttemptScopedAuth(
    Future<http.Response> Function() request, {
    int? authenticationAttemptId,
  }) async {
    if (authenticationAttemptId != null &&
        !_tokenCoordinator.isAuthenticationAttemptCurrent(authenticationAttemptId)) {
      throw StaleAuthenticationAttemptException(attemptId: authenticationAttemptId);
    }

    final attemptId =
        authenticationAttemptId ?? _tokenCoordinator.beginAuthenticationAttempt();
    try {
      final response = await request();
      return await _storeSession(response.body, attemptId: attemptId);
    } catch (e) {
      if (e is! StaleAuthenticationAttemptException) {
        _tokenCoordinator.abandonAuthenticationAttempt(attemptId);
      }
      rethrow;
    }
  }

  /// Interpreta a resposta de autenticação e persiste o par de tokens sob
  /// validação estrita de propriedade da tentativa [attemptId].
  Future<Authenticated> _storeSession(
    String body, {
    required int attemptId,
  }) async {
    final data = jsonDecode(body) as Map<String, dynamic>;
    final user = AuthUserDto.fromJson(data['user'] as Map<String, dynamic>);
    final tokens = AuthTokens.fromJson(data);

    await _tokenCoordinator.saveNewSessionTokens(
      accessToken: tokens.accessToken,
      refreshToken: tokens.refreshToken,
      expectedAttemptId: attemptId,
    );

    return Authenticated(user: user, tokens: tokens);
  }

  @override
  Future<Authenticated> reactivate({
    required String email,
    required String password,
  }) =>
      reactivateWithAttempt(email: email, password: password);

  @override
  Future<Authenticated> reactivateWithAttempt({
    required String email,
    required String password,
    int? authenticationAttemptId,
  }) {
    return _runAttemptScopedAuth(
      () => _httpClient.post(
        ApiEndpoints.authReactivate,
        body: {
          'email': email.trim().toLowerCase(),
          'password': password,
        },
        requiresAuth: false,
      ),
      authenticationAttemptId: authenticationAttemptId,
    );
  }

  @override
  Future<void> deactivateAccount() async {
    await _httpClient.post(
      ApiEndpoints.meDeactivate,
      requiresAuth: true,
    );

    await _tokenCoordinator.invalidateAndClear();
  }

  @override
  Future<void> changePassword({
    required String currentPassword,
    required String newPassword,
  }) async {
    await _httpClient.post(
      ApiEndpoints.mePassword,
      body: {
        'currentPassword': currentPassword,
        'newPassword': newPassword,
      },
      requiresAuth: true,
      treatInvalidCredentialsAsBusinessError: true,
    );

    await _tokenCoordinator.invalidateAndClear();
  }

  /// Executa a chamada HTTP de `/api/v1/auth/refresh` sem gravar no armazenamento local.
  ///
  /// Permite que [SessionRefresher] valide a geração/propriedade da sessão antes de
  /// confirmar a persistência em [SessionTokenCoordinator.saveRefreshedTokens].
  Future<Authenticated> requestTokenRefresh() async {
    final currentRefreshToken = await _tokenStorage.getRefreshToken();
    if (currentRefreshToken == null || currentRefreshToken.isEmpty) {
      throw const ApiException(
        ProblemDetail(
          type: 'about:blank',
          title: 'Sessão Expirada',
          status: 401,
          detail: 'Refresh token ausente ou expirado.',
          code: 'MISSING_REFRESH_TOKEN',
        ),
      );
    }

    final response = await _httpClient.post(
      ApiEndpoints.authRefresh,
      body: {
        'refreshToken': currentRefreshToken,
      },
      requiresAuth: false,
    );

    final data = jsonDecode(response.body) as Map<String, dynamic>;
    final user = AuthUserDto.fromJson(data['user'] as Map<String, dynamic>);
    final tokens = AuthTokens.fromJson(data);

    return Authenticated(user: user, tokens: tokens);
  }

  @override
  Future<Authenticated> refreshTokens() async {
    final generationAtStart = _tokenCoordinator.currentGeneration;
    final refreshed = await requestTokenRefresh();

    final persisted = await _tokenCoordinator.saveRefreshedTokens(
      accessToken: refreshed.tokens.accessToken,
      refreshToken: refreshed.tokens.refreshToken,
      expectedGeneration: generationAtStart,
    );
    if (!persisted) {
      throw const ApiException(
        ProblemDetail(
          type: 'about:blank',
          title: 'Sessão Invalidada',
          status: 401,
          detail: 'A sessão foi encerrada ou substituída durante a renovação.',
          code: 'SESSION_INVALIDATED',
        ),
      );
    }

    return refreshed;
  }

  @override
  Future<AuthUserDto> getMe() async {
    final response = await _httpClient.get(
      ApiEndpoints.authMe,
      requiresAuth: true,
    );

    final data = jsonDecode(response.body) as Map<String, dynamic>;
    return AuthUserDto.fromJson(data);
  }

  @override
  Future<void> logout() async {
    final logoutGeneration = _tokenCoordinator.invalidateSession();
    final refreshToken = await _tokenStorage.getRefreshToken();
    if (refreshToken != null && refreshToken.isNotEmpty) {
      try {
        await _httpClient.post(
          ApiEndpoints.authLogout,
          body: {'refreshToken': refreshToken},
          requiresAuth: true,
        );
      } catch (_) {
        // Falhas remotas na revogação (ex: rede) não impedem a limpeza local da sessão.
      }
    }

    await _tokenCoordinator.clearTokensForGeneration(logoutGeneration);
  }

  @override
  Future<bool> hasStoredSession() async {
    return _tokenStorage.hasAccessToken();
  }
}
