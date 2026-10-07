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
