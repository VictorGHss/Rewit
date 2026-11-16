/// Metadados estruturados da resposta HTTP 401 que disparou a verificação ou expiração global da sessão.
class SessionExpirationDetails {
  /// Código RFC 7807 original devolvido pelo backend (ex.: `ACCOUNT_DISABLED`, `AUTHENTICATION_REQUIRED`).
  final String? errorCode;

  /// Código de status HTTP da resposta (normalmente `401`).
  final int statusCode;

  const SessionExpirationDetails({
    this.errorCode,
    this.statusCode = 401,
  });

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        other is SessionExpirationDetails &&
            other.errorCode == errorCode &&
            other.statusCode == statusCode;
  }

  @override
  int get hashCode => Object.hash(errorCode, statusCode);

  @override
  String toString() =>
      'SessionExpirationDetails(statusCode: $statusCode, errorCode: $errorCode)';
}

/// Callback tipado invocado quando uma requisição autenticada recebe falha HTTP 401 de sessão/conta.
typedef SessionExpiredCallback = void Function(
  SessionExpirationDetails details,
);
