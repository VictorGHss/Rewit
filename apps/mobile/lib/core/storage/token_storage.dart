import 'package:flutter_secure_storage/flutter_secure_storage.dart';

/// Abstração para armazenamento seguro de tokens de autenticação (JWT e Refresh Token).
abstract class TokenStorage {
  /// Recupera o Access Token atualmente armazenado, ou null se inexistente.
  Future<String?> getAccessToken();

  /// Recupera o Refresh Token atualmente armazenado, ou null se inexistente.
  Future<String?> getRefreshToken();

  /// Persiste o par de tokens (Access Token e Refresh Token).
  Future<void> saveTokens({
    required String accessToken,
    required String refreshToken,
  });

  /// Remove todos os tokens armazenados (utilizado em logout ou sessão revogada).
  Future<void> clearTokens();

  /// Retorna se há pelo menos um access token presente.
  Future<bool> hasAccessToken();
}

/// Implementação em memória de [TokenStorage], ideal para testes ou sessões efêmeras.
class InMemoryTokenStorage implements TokenStorage {
  String? _accessToken;
  String? _refreshToken;

  InMemoryTokenStorage({String? initialAccessToken, String? initialRefreshToken})
      : _accessToken = initialAccessToken,
        _refreshToken = initialRefreshToken;

  @override
  Future<String?> getAccessToken() async => _accessToken;

  @override
  Future<String?> getRefreshToken() async => _refreshToken;

  @override
  Future<void> saveTokens({
    required String accessToken,
    required String refreshToken,
  }) async {
    _accessToken = accessToken;
    _refreshToken = refreshToken;
  }

  @override
  Future<void> clearTokens() async {
    _accessToken = null;
    _refreshToken = null;
  }

  @override
  Future<bool> hasAccessToken() async => _accessToken != null && _accessToken!.isNotEmpty;
}

/// Implementação de [TokenStorage] baseada em [FlutterSecureStorage],
/// persistindo tokens de forma criptografada em repouso (KeyStore no Android e Keychain no iOS).
class SecureTokenStorage implements TokenStorage {
  static const String _accessTokenKey = 'rewit_auth_access_token';
  static const String _refreshTokenKey = 'rewit_auth_refresh_token';

  final FlutterSecureStorage _storage;
  final InMemoryTokenStorage _fallbackStorage = InMemoryTokenStorage();
  bool _useFallback = false;

  SecureTokenStorage({FlutterSecureStorage? storage})
      : _storage = storage ??
            const FlutterSecureStorage(
              aOptions: AndroidOptions(resetOnError: true),
              iOptions: IOSOptions(accessibility: KeychainAccessibility.first_unlock),
            );

  @override
  Future<String?> getAccessToken() async {
    if (_useFallback) return _fallbackStorage.getAccessToken();
    try {
      final token = await _storage.read(key: _accessTokenKey);
      return token ?? await _fallbackStorage.getAccessToken();
    } catch (_) {
      _useFallback = true;
      return _fallbackStorage.getAccessToken();
    }
  }

  @override
  Future<String?> getRefreshToken() async {
    if (_useFallback) return _fallbackStorage.getRefreshToken();
    try {
      final token = await _storage.read(key: _refreshTokenKey);
      return token ?? await _fallbackStorage.getRefreshToken();
    } catch (_) {
      _useFallback = true;
      return _fallbackStorage.getRefreshToken();
    }
  }

  @override
  Future<void> saveTokens({
    required String accessToken,
    required String refreshToken,
  }) async {
    await _fallbackStorage.saveTokens(
      accessToken: accessToken,
      refreshToken: refreshToken,
    );

    if (_useFallback) return;

    try {
      await Future.wait([
        _storage.write(key: _accessTokenKey, value: accessToken),
        _storage.write(key: _refreshTokenKey, value: refreshToken),
      ]);
    } catch (_) {
      _useFallback = true;
    }
  }

  @override
  Future<void> clearTokens() async {
    await _fallbackStorage.clearTokens();
    if (_useFallback) return;

    try {
      await Future.wait([
        _storage.delete(key: _accessTokenKey),
        _storage.delete(key: _refreshTokenKey),
      ]);
    } catch (_) {
      _useFallback = true;
    }
  }

  @override
  Future<bool> hasAccessToken() async {
    final token = await getAccessToken();
    return token != null && token.isNotEmpty;
  }
}
