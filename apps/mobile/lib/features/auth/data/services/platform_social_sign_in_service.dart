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
/// ### Google: nonce por tentativa
///
/// Em `google_sign_in` 7.2.0 o nonce só entra por `InitParameters`. A API de alto nível
/// (`GoogleSignIn.instance.initialize`) deve ser chamada uma única vez: cada chamada assina de novo o stream de
/// eventos de autenticação e o pacote declara o resultado indefinido. Por isso o app não a usa: fala com
/// [GoogleSignInPlatform], cujo contrato (`google_sign_in_platform_interface` 3.1.0) só exige `init` antes das
/// demais chamadas, sem limitar a uma vez. Nas implementações resolvidas e auditadas, `init` apenas grava a
/// configuração e o nonce, que é lido no momento de cada `authenticate`:
///
/// * `google_sign_in_android` 7.2.18: atribui `serverClientId`, `hostedDomain` e `nonce` em campos Dart (o canal
///   nativo só é consultado sem `serverClientId`, que o app sempre envia); `getCredential` monta o pedido do
///   Credential Manager com esses campos;
/// * `google_sign_in_ios` 6.3.8: grava o nonce em Dart e redefine `GIDSignIn.configuration` (propriedade
///   substituível); o nonce vai como argumento de cada `signIn`.
///
/// Cada tentativa executa `init` (mesma configuração, nonce novo), `authenticate` e `signOut` como uma seção
/// crítica: uma segunda tentativa simultânea é recusada sem tocar no SDK, então um `init` nunca troca o nonce de
/// um `authenticate` em andamento. As versões ficam fixadas e um teste falha se o lockfile mudar, para que uma
/// atualização exija nova auditoria. O nonce não é opcional: o backend recusa token sem ele.
class PlatformSocialSignInService implements SocialSignInService {
  final SocialAuthConfig config;
  final TargetPlatform? _platformOverride;
  final GoogleSignInPlatform? _googlePlatformOverride;
  bool _googleAttemptInProgress = false;

  PlatformSocialSignInService({
    required this.config,
    @visibleForTesting TargetPlatform? platform,
    @visibleForTesting GoogleSignInPlatform? googlePlatform,
  })  : _platformOverride = platform,
        _googlePlatformOverride = googlePlatform;

  TargetPlatform get _platform => _platformOverride ?? defaultTargetPlatform;

  GoogleSignInPlatform get _google => _googlePlatformOverride ?? GoogleSignInPlatform.instance;

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
    if (_googleAttemptInProgress) {
      throw const SocialSignInFailureException(provider, 'Já existe um login com o Google em andamento.');
    }
    _googleAttemptInProgress = true;
    final google = _google;
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
      _googleAttemptInProgress = false;
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
