/// Provedores de login social aceitos pelo backend do Rewit.
enum SocialAuthProvider {
  google('google', 'Google'),
  apple('apple', 'Apple');

  /// Segmento da rota `POST /api/v1/auth/social/{provider}`.
  final String apiPath;
  final String label;

  const SocialAuthProvider(this.apiPath, this.label);
}

/// O usuário fechou ou cancelou a tela do provedor.
class SocialSignInCancelledException implements Exception {
  final SocialAuthProvider provider;

  const SocialSignInCancelledException(this.provider);

  @override
  String toString() => 'SocialSignInCancelledException(${provider.name})';
}

/// O fluxo do provedor falhou (configuração, rede, conta indisponível) sem devolver credencial.
class SocialSignInFailureException implements Exception {
  final SocialAuthProvider provider;
  final String message;

  const SocialSignInFailureException(this.provider, this.message);

  @override
  String toString() => 'SocialSignInFailureException(${provider.name}): $message';
}

/// Obtém a credencial oficial (ID token OpenID Connect) do SDK do provedor.
///
/// O app nunca decide a identidade: o ID token é enviado ao Rewit, que valida assinatura, emissor, audiência,
/// validade e o nonce antes de abrir a sessão.
abstract class SocialSignInService {
  /// Provedor configurado nesta build e suportado nesta plataforma. Indisponível, o botão não é habilitado.
  bool isAvailable(SocialAuthProvider provider);

  /// Abre o fluxo oficial do provedor e devolve o ID token, que precisa conter [providerNonce] na claim `nonce`.
  ///
  /// Lança [SocialSignInCancelledException] se o usuário cancelar e [SocialSignInFailureException] em falha.
  Future<String> obtainIdToken(SocialAuthProvider provider, {required String providerNonce});
}
