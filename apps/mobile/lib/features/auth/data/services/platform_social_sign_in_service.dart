import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:google_sign_in_platform_interface/google_sign_in_platform_interface.dart';
import 'package:rewit_mobile/features/auth/domain/services/social_sign_in_service.dart';
import 'package:sign_in_with_apple/sign_in_with_apple.dart';

/// Identificadores públicos do login social, definidos por build (`--dart-define`). Nenhum segredo fica no app.
///
/// * `GOOGLE_SERVER_CLIENT_ID`: client ID "Web application" do Google Cloud, audiência do ID token (Android e iOS);
/// * `GOOGLE_IOS_CLIENT_ID`: client ID iOS (exige também o URL scheme invertido no Info.plist);
/// * `APPLE_SIGNIN_ENABLED`: `true` quando o app iOS tem a capability Sign in with Apple;
/// * `APPLE_SERVICE_ID` e `APPLE_REDIRECT_URI`: Services ID e retorno do fluxo web da Apple, usados no Android.
///
/// Sem esses valores o provedor fica indisponível e o botão aparece desabilitado.
class SocialAuthConfig {
  final String googleServerClientId;
  final String googleIosClientId;
  final bool appleNativeEnabled;
  final String appleServiceId;
  final String appleRedirectUri;

  const SocialAuthConfig({
    this.googleServerClientId = '',
    this.googleIosClientId = '',
    this.appleNativeEnabled = false,
    this.appleServiceId = '',
    this.appleRedirectUri = '',
  });

  factory SocialAuthConfig.fromEnvironment() {
    return const SocialAuthConfig(
      googleServerClientId: String.fromEnvironment('GOOGLE_SERVER_CLIENT_ID'),
      googleIosClientId: String.fromEnvironment('GOOGLE_IOS_CLIENT_ID'),
      appleNativeEnabled: bool.fromEnvironment('APPLE_SIGNIN_ENABLED'),
      appleServiceId: String.fromEnvironment('APPLE_SERVICE_ID'),
      appleRedirectUri: String.fromEnvironment('APPLE_REDIRECT_URI'),
    );
  }

  bool get hasAppleWebFlow {
    final uri = Uri.tryParse(appleRedirectUri);
    return appleServiceId.isNotEmpty && uri != null && uri.scheme == 'https' && uri.host.isNotEmpty;
  }
}

/// [SocialSignInService] com os SDKs oficiais: Google Sign-In (Credential Manager no Android, GoogleSignIn no iOS)
/// e Sign in with Apple (nativo no iOS, fluxo web com retorno pelo backend no Android).
///
/// O Google é chamado pela interface de plataforma do `google_sign_in`, que aceita um nonce diferente a cada
/// tentativa (a API de alto nível fixa o nonce numa inicialização única).
class PlatformSocialSignInService implements SocialSignInService {
  final SocialAuthConfig config;
  final TargetPlatform? _platformOverride;

  PlatformSocialSignInService({required this.config, @visibleForTesting TargetPlatform? platform})
      : _platformOverride = platform;

  TargetPlatform get _platform => _platformOverride ?? defaultTargetPlatform;

  @override
  bool isAvailable(SocialAuthProvider provider) {
    if (kIsWeb) return false;
    switch (provider) {
      case SocialAuthProvider.google:
        if (config.googleServerClientId.isEmpty) return false;
        return _platform == TargetPlatform.android ||
            (_platform == TargetPlatform.iOS && config.googleIosClientId.isNotEmpty);
      case SocialAuthProvider.apple:
        if (_platform == TargetPlatform.iOS) return config.appleNativeEnabled;
        return _platform == TargetPlatform.android && config.hasAppleWebFlow;
    }
  }

  @override
  Future<String> obtainIdToken(SocialAuthProvider provider, {required String providerNonce}) {
    if (!isAvailable(provider)) {
      throw SocialSignInFailureException(provider, 'Login com ${provider.label} indisponível nesta versão do app.');
    }
    switch (provider) {
      case SocialAuthProvider.google:
        return _googleIdToken(providerNonce);
      case SocialAuthProvider.apple:
        return _appleIdToken(providerNonce);
    }
  }

  Future<String> _googleIdToken(String providerNonce) async {
    const provider = SocialAuthProvider.google;
    final google = GoogleSignInPlatform.instance;
    try {
      await google.init(InitParameters(
        clientId: _platform == TargetPlatform.iOS ? config.googleIosClientId : null,
        serverClientId: config.googleServerClientId,
        nonce: providerNonce,
      ));
      final result = await google.authenticate(const AuthenticateParameters());
      final idToken = result.authenticationTokens.idToken;
      if (idToken == null || idToken.isEmpty) {
        throw const SocialSignInFailureException(provider, 'O Google não devolveu a credencial de identidade.');
      }
      return idToken;
    } on GoogleSignInException catch (e) {
      if (e.code == GoogleSignInExceptionCode.canceled) {
        throw const SocialSignInCancelledException(provider);
      }
      throw const SocialSignInFailureException(provider, 'Não foi possível entrar com o Google.');
    } on PlatformException {
      throw const SocialSignInFailureException(provider, 'Não foi possível entrar com o Google.');
    } finally {
      // A sessão do Google não é mantida: o Rewit emite a própria sessão; o próximo login volta a escolher a conta
      try {
        await google.signOut(const SignOutParams());
      } catch (_) {
        // Sem conta conectada não há o que encerrar
      }
    }
  }

  Future<String> _appleIdToken(String providerNonce) async {
    const provider = SocialAuthProvider.apple;
    try {
      final credential = await SignInWithApple.getAppleIDCredential(
        // Só o e-mail: necessário para criar a conta; o nome não é usado pelo backend
        scopes: const [AppleIDAuthorizationScopes.email],
        nonce: providerNonce,
        webAuthenticationOptions: _platform == TargetPlatform.android
            ? WebAuthenticationOptions(
                clientId: config.appleServiceId,
                redirectUri: Uri.parse(config.appleRedirectUri),
              )
            : null,
      );
      final idToken = credential.identityToken;
      if (idToken == null || idToken.isEmpty) {
        throw const SocialSignInFailureException(provider, 'A Apple não devolveu a credencial de identidade.');
      }
      return idToken;
    } on SignInWithAppleAuthorizationException catch (e) {
      if (e.code == AuthorizationErrorCode.canceled) {
        throw const SocialSignInCancelledException(provider);
      }
      throw const SocialSignInFailureException(provider, 'Não foi possível entrar com a Apple.');
    } on SignInWithAppleException {
      throw const SocialSignInFailureException(provider, 'Não foi possível entrar com a Apple.');
    } on PlatformException {
      throw const SocialSignInFailureException(provider, 'Não foi possível entrar com a Apple.');
    }
  }
}
