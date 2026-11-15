import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:google_sign_in_platform_interface/google_sign_in_platform_interface.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:rewit_mobile/core/network/http_client.dart';
import 'package:rewit_mobile/core/storage/token_storage.dart';
import 'package:rewit_mobile/features/auth/data/repositories/auth_repository_impl.dart';
import 'package:rewit_mobile/features/auth/data/services/platform_social_sign_in_service.dart';
import 'package:rewit_mobile/features/auth/domain/entities/auth_state.dart';
import 'package:rewit_mobile/features/auth/domain/services/social_sign_in_service.dart';
import 'package:rewit_mobile/features/auth/presentation/state/auth_notifier.dart';

/// Testes mockados: a interface de plataforma do Google é substituída por um fake que reproduz o contrato das
/// implementações auditadas (init grava configuração e nonce; authenticate usa o nonce vigente). Nenhum
/// provedor real é chamado aqui.
class _FakeGooglePlatform extends GoogleSignInPlatform {
  /// Resultado de cada authenticate, em ordem: um ID token ou uma exceção.
  final List<Object> outcomes;
  final List<String> calls = [];
  final List<InitParameters> inits = [];
  Completer<void>? authenticateGate;
  String? _currentNonce;

  _FakeGooglePlatform(this.outcomes);

  @override
  Future<void> init(InitParameters params) async {
    inits.add(params);
    _currentNonce = params.nonce;
    calls.add('init:${params.nonce}');
  }

  @override
  Future<AuthenticationResults> authenticate(AuthenticateParameters params) async {
    // O nonce é lido no momento do authenticate, como no Android (getCredential) e no iOS (signIn)
    final nonceAtCall = _currentNonce;
    calls.add('authenticate:$nonceAtCall');
    final gate = authenticateGate;
    if (gate != null) await gate.future;
    final outcome = outcomes.removeAt(0);
    if (outcome is Exception) throw outcome;
    return AuthenticationResults(
      user: const GoogleSignInUserData(email: 'pessoa@gmail.com', id: 'google-sub'),
      authenticationTokens: AuthenticationTokenData(idToken: outcome == '' ? null : '$outcome|nonce=$nonceAtCall'),
    );
  }

  @override
  Future<void> signOut(SignOutParams params) async => calls.add('signOut');

  @override
  bool supportsAuthenticate() => true;

  @override
  bool authorizationRequiresUserInteraction() => false;

  @override
  Future<AuthenticationResults?>? attemptLightweightAuthentication(AttemptLightweightAuthenticationParameters params) =>
      null;

  @override
  Future<ClientAuthorizationTokenData?> clientAuthorizationTokensForScopes(
          ClientAuthorizationTokensForScopesParameters params) async =>
      null;

  @override
  Future<ServerAuthorizationTokenData?> serverAuthorizationTokensForScopes(
          ServerAuthorizationTokensForScopesParameters params) async =>
      null;

  @override
  Future<void> disconnect(DisconnectParams params) async {}
}

const _cancelled = GoogleSignInException(code: GoogleSignInExceptionCode.canceled);

const _androidConfig = SocialAuthConfig(googleServerClientId: 'web-client.apps.googleusercontent.com');

PlatformSocialSignInService _service(_FakeGooglePlatform google, {TargetPlatform platform = TargetPlatform.android,
    SocialAuthConfig config = _androidConfig}) {
  return PlatformSocialSignInService(config: config, platform: platform, googlePlatform: google);
}

void main() {
  group('Google: tentativas repetidas com nonce exclusivo (interface de plataforma mockada)', () {
    test('cancelamento, nova tentativa e terceira tentativa: cada authenticate usa o nonce da própria tentativa', () async {
      final google = _FakeGooglePlatform([_cancelled, 'token-2', 'token-3']);
      final service = _service(google);

      await expectLater(service.obtainIdToken(SocialAuthProvider.google, providerNonce: 'n1'),
          throwsA(isA<SocialSignInCancelledException>()));
      expect(await service.obtainIdToken(SocialAuthProvider.google, providerNonce: 'n2'), 'token-2|nonce=n2');
      expect(await service.obtainIdToken(SocialAuthProvider.google, providerNonce: 'n3'), 'token-3|nonce=n3');

      expect(google.calls, [
        'init:n1', 'authenticate:n1', 'signOut',
        'init:n2', 'authenticate:n2', 'signOut',
        'init:n3', 'authenticate:n3', 'signOut',
      ]);
      // A configuração é a mesma em todas as tentativas; só o nonce muda
      expect(google.inits.map((p) => p.serverClientId).toSet(), {'web-client.apps.googleusercontent.com'});
      expect(google.inits.map((p) => p.clientId).toSet(), {null});
    });

    test('segunda tentativa simultânea é recusada sem init: o nonce em uso não é trocado', () async {
      final google = _FakeGooglePlatform(['token-1', 'token-3'])..authenticateGate = Completer<void>();
      final service = _service(google);

      final first = service.obtainIdToken(SocialAuthProvider.google, providerNonce: 'n1');
      await Future<void>.delayed(Duration.zero);
      await expectLater(service.obtainIdToken(SocialAuthProvider.google, providerNonce: 'n2'),
          throwsA(isA<SocialSignInFailureException>()));
      expect(google.calls, ['init:n1', 'authenticate:n1']);

      google.authenticateGate!.complete();
      google.authenticateGate = null;
      expect(await first, 'token-1|nonce=n1');

      // Terminada a tentativa, uma nova volta a ser aceita
      expect(await service.obtainIdToken(SocialAuthProvider.google, providerNonce: 'n3'), 'token-3|nonce=n3');
      expect(google.calls.where((c) => c.startsWith('init')), ['init:n1', 'init:n3']);
    });

    test('falha de configuração e token ausente encerram a tentativa e liberam a seguinte', () async {
      final google = _FakeGooglePlatform([
        const GoogleSignInException(code: GoogleSignInExceptionCode.clientConfigurationError),
        '',
        'token-3',
      ]);
      final service = _service(google);

      await expectLater(service.obtainIdToken(SocialAuthProvider.google, providerNonce: 'n1'),
          throwsA(isA<SocialSignInFailureException>()));
      await expectLater(service.obtainIdToken(SocialAuthProvider.google, providerNonce: 'n2'),
          throwsA(isA<SocialSignInFailureException>()));
      expect(await service.obtainIdToken(SocialAuthProvider.google, providerNonce: 'n3'), 'token-3|nonce=n3');
      expect(google.calls.where((c) => c == 'signOut').length, 3);
    });

    test('iOS envia o client ID iOS e o serverClientId em todas as tentativas', () async {
      final google = _FakeGooglePlatform([_cancelled, 'token-2']);
      final service = _service(google,
          platform: TargetPlatform.iOS,
          config: const SocialAuthConfig(
            googleServerClientId: 'web-client.apps.googleusercontent.com',
            googleIosClientId: 'ios-client.apps.googleusercontent.com',
          ));

      await expectLater(service.obtainIdToken(SocialAuthProvider.google, providerNonce: 'n1'),
          throwsA(isA<SocialSignInCancelledException>()));
      expect(await service.obtainIdToken(SocialAuthProvider.google, providerNonce: 'n2'), 'token-2|nonce=n2');
      expect(google.inits.map((p) => p.clientId).toSet(), {'ios-client.apps.googleusercontent.com'});
    });

    test('AuthNotifier: cancelamento e nova tentativa pedem nonces novos ao Rewit e só o válido chega ao backend', () async {
      final requests = <http.Request>[];
      var nonceCount = 0;
      final client = MockClient((request) async {
        requests.add(request);
        if (request.url.path == '/api/v1/auth/social/nonce') {
          nonceCount++;
          return http.Response(jsonEncode({'nonce': 'rewit-nonce-$nonceCount', 'expiresIn': 600}), 200);
        }
        return http.Response(
            jsonEncode({
              'accessToken': 'a',
              'refreshToken': 'r',
              'user': {'id': 'u', 'email': 'pessoa@gmail.com', 'handle': 'usuario_x', 'displayName': 'Pessoa'},
            }),
            200);
      });
      final storage = InMemoryTokenStorage();
      final repo = AuthRepositoryImpl(
        httpClient: RewitHttpClient(baseUrl: 'https://api.rewit.test', client: client, tokenStorage: storage),
        tokenStorage: storage,
      );
      final google = _FakeGooglePlatform([_cancelled, 'token-2']);
      final notifier = AuthNotifier(authRepository: repo, socialSignInService: _service(google));

      expect(await notifier.signInWithSocial(SocialAuthProvider.google), isFalse);
      expect((notifier.state as Unauthenticated).errorCode, AuthNotifier.socialSignInCancelledCode);
      expect(await notifier.signInWithSocial(SocialAuthProvider.google), isTrue);
      expect(notifier.state, isA<Authenticated>());

      final hash1 = AuthNotifier.hashSocialNonce('rewit-nonce-1');
      final hash2 = AuthNotifier.hashSocialNonce('rewit-nonce-2');
      expect(google.calls.where((c) => c.startsWith('init')), ['init:$hash1', 'init:$hash2']);
      final login = requests.singleWhere((r) => r.url.path == '/api/v1/auth/social/google');
      expect(jsonDecode(login.body), {'idToken': 'token-2|nonce=$hash2', 'nonce': 'rewit-nonce-2'});
    });
  });

  test('versões auditadas do Google Sign-In continuam fixadas no lockfile', () {
    // Uma atualização destes pacotes exige reauditar o init por tentativa (ver PlatformSocialSignInService)
    const audited = {
      'google_sign_in': '7.2.0',
      'google_sign_in_platform_interface': '3.1.0',
      'google_sign_in_android': '7.2.18',
      'google_sign_in_ios': '6.3.8',
    };
    final lock = File('pubspec.lock').readAsStringSync().replaceAll('\r\n', '\n');
    for (final entry in audited.entries) {
      final match = RegExp('\\n  ${entry.key}:\\n(?:    .*\\n)*?    version: "([^"]+)"').firstMatch(lock);
      expect(match?.group(1), entry.value, reason: '${entry.key} mudou de versão: reaudite o nonce por tentativa');
    }
  });
}
