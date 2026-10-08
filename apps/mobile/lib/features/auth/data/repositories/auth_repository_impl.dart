import 'dart:convert';
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/core/network/api_endpoints.dart';
import 'package:rewit_mobile/core/network/http_client.dart';
import 'package:rewit_mobile/core/storage/token_storage.dart';
import 'package:rewit_mobile/features/auth/data/models/auth_tokens.dart';
import 'package:rewit_mobile/features/auth/data/models/auth_user_dto.dart';
import 'package:rewit_mobile/features/auth/domain/entities/auth_state.dart';
import 'package:rewit_mobile/features/auth/domain/repositories/auth_repository.dart';

/// Implementação de [AuthRepository] consumindo a API REST do Rewit.
class AuthRepositoryImpl implements AuthRepository {
  final RewitHttpClient _httpClient;
  final TokenStorage _tokenStorage;

  AuthRepositoryImpl({
    required RewitHttpClient httpClient,
    required TokenStorage tokenStorage,
  })  : _httpClient = httpClient,
        _tokenStorage = tokenStorage;

  @override
  Future<Authenticated> login({
    required String email,
    required String password,
  }) async {
    final response = await _httpClient.post(
      ApiEndpoints.authLogin,
      body: {
        'email': email.trim().toLowerCase(),
        'password': password,
      },
      requiresAuth: false,
    );

    final data = jsonDecode(response.body) as Map<String, dynamic>;
    final user = AuthUserDto.fromJson(data['user'] as Map<String, dynamic>);
    final tokens = AuthTokens.fromJson(data);

    await _tokenStorage.saveTokens(
      accessToken: tokens.accessToken,
      refreshToken: tokens.refreshToken,
    );

    return Authenticated(user: user, tokens: tokens);
  }

  @override
  Future<Authenticated> reactivate({
    required String email,
    required String password,
  }) async {
    final response = await _httpClient.post(
      ApiEndpoints.authReactivate,
      body: {
        'email': email.trim().toLowerCase(),
        'password': password,
      },
      requiresAuth: false,
    );

    final data = jsonDecode(response.body) as Map<String, dynamic>;
    final user = AuthUserDto.fromJson(data['user'] as Map<String, dynamic>);
    final tokens = AuthTokens.fromJson(data);

    await _tokenStorage.saveTokens(
      accessToken: tokens.accessToken,
      refreshToken: tokens.refreshToken,
    );

    return Authenticated(user: user, tokens: tokens);
  }

  @override
  Future<void> deactivateAccount() async {
    await _httpClient.post(
      ApiEndpoints.meDeactivate,
      requiresAuth: true,
    );

    await _tokenStorage.clearTokens();
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

    await _tokenStorage.clearTokens();
  }

  @override
  Future<Authenticated> refreshTokens() async {
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

    await _tokenStorage.saveTokens(
      accessToken: tokens.accessToken,
      refreshToken: tokens.refreshToken,
    );

    return Authenticated(user: user, tokens: tokens);
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

    await _tokenStorage.clearTokens();
  }

  @override
  Future<bool> hasStoredSession() async {
    return _tokenStorage.hasAccessToken();
  }
}
