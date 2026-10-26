import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/core/network/http_client.dart';
import 'package:rewit_mobile/core/storage/token_storage.dart';
import 'package:rewit_mobile/features/auth/data/repositories/auth_repository_impl.dart';
import 'package:rewit_mobile/features/auth/data/services/platform_social_sign_in_service.dart';
import 'package:rewit_mobile/features/auth/domain/entities/auth_state.dart';
import 'package:rewit_mobile/features/auth/domain/services/social_sign_in_service.dart';
import 'package:rewit_mobile/features/auth/domain/validation/registration_validators.dart';
import 'package:rewit_mobile/features/auth/presentation/screens/login_screen.dart';
import 'package:rewit_mobile/features/auth/presentation/screens/register_screen.dart';
import 'package:rewit_mobile/features/auth/presentation/state/auth_notifier.dart';

const _nonce = 'rewit-nonce-0123456789-abcdefghijklmnopqrstu';

Map<String, dynamic> _authPayload({String access = 'access-1', String refresh = 'refresh-1', String handle = 'maria'}) => {
      'accessToken': access,
      'refreshToken': refresh,
      'tokenType': 'Bearer',
      'expiresIn': 900,
      'user': {
        'id': 'user-1',
        'email': 'maria@rewit.com',
        'handle': handle,
        'displayName': 'Maria',
      },
    };

http.Response _json(Object body, int status) => http.Response(
      jsonEncode(body),
      status,
      headers: {'content-type': status >= 400 ? 'application/problem+json' : 'application/json'},
    );

http.Response _problem(int status, String code, String detail, {Map<String, String>? fieldErrors}) => _json({
      'type': 'about:blank',
      'title': 'Erro',
      'status': status,
      'detail': detail,
      'code': code,
      if (fieldErrors != null) 'fieldErrors': fieldErrors,
    }, status);

/// Backend simulado: registra as requisições e responde pelo [handler].
class _Backend {
  final List<http.Request> requests = [];
  final Future<http.Response> Function(http.Request request) handler;

  _Backend(this.handler);

  MockClient get client => MockClient((request) async {
        requests.add(request);
        return handler(request);
      });

  List<String> get paths => requests.map((r) => r.url.path).toList();

  Map<String, dynamic> bodyOf(String path) =>
      jsonDecode(requests.lastWhere((r) => r.url.path == path).body) as Map<String, dynamic>;
}

/// SDK do provedor simulado: devolve um ID token, cancela ou falha.
class _FakeSocialSignIn implements SocialSignInService {
  final Set<SocialAuthProvider> available;
  Object? outcome;
  final List<String> receivedNonces = [];

  _FakeSocialSignIn({this.available = const {SocialAuthProvider.google, SocialAuthProvider.apple}, this.outcome});

  @override
  bool isAvailable(SocialAuthProvider provider) => available.contains(provider);

  @override
  Future<String> obtainIdToken(SocialAuthProvider provider, {required String providerNonce}) async {
    receivedNonces.add(providerNonce);
    final result = outcome;
    if (result is Exception) throw result;
    return (result as String?) ?? 'provider-id-token';
  }
}

({AuthRepositoryImpl repo, InMemoryTokenStorage storage}) _repository(_Backend backend) {
  final storage = InMemoryTokenStorage();
  final httpClient = RewitHttpClient(baseUrl: 'https://api.rewit.test', client: backend.client, tokenStorage: storage);
  return (repo: AuthRepositoryImpl(httpClient: httpClient, tokenStorage: storage), storage: storage);
}

Future<http.Response> _socialBackend(http.Request request) async {
  switch (request.url.path) {
    case '/api/v1/auth/social/nonce':
      return _json({'nonce': _nonce, 'expiresIn': 600}, 200);
    case '/api/v1/auth/social/google':
    case '/api/v1/auth/social/apple':
      return _json(_authPayload(), 200);
    case '/api/v1/auth/refresh':
      return _json(_authPayload(access: 'access-2', refresh: 'refresh-2'), 200);
    case '/api/v1/auth/logout':
      return http.Response('', 204);
  }
  return _problem(404, 'NOT_FOUND', 'Rota inesperada');
}

void main() {
  group('Validação do cadastro no cliente', () {
    test('nome de exibição entre 2 e 100 caracteres', () {
      expect(RegistrationValidators.displayName(''), isNotNull);
      expect(RegistrationValidators.displayName(' a '), isNotNull);
      expect(RegistrationValidators.displayName('Al'), isNull);
      expect(RegistrationValidators.displayName('a' * 100), isNull);
      expect(RegistrationValidators.displayName('a' * 101), isNotNull);
    });

    test('identificador: 3 a 30 de [a-z0-9_], normalizado sem @ e em minúsculas', () {
      expect(RegistrationValidators.normalizeHandle('  @Maria_01 '), 'maria_01');
      expect(RegistrationValidators.handle('@Maria_01'), isNull);
      expect(RegistrationValidators.handle('ab'), isNotNull);
      expect(RegistrationValidators.handle('a' * 31), isNotNull);
      expect(RegistrationValidators.handle('maria.silva'), isNotNull, reason: 'o backend não aceita ponto');
      expect(RegistrationValidators.handle('joão'), isNotNull);
      expect(RegistrationValidators.handle('maria silva'), isNotNull);
    });

    test('e-mail, senha de 8 a 128 caracteres e confirmação igual', () {
      expect(RegistrationValidators.email('maria@rewit.com'), isNull);
      expect(RegistrationValidators.email('maria@'), isNotNull);
      expect(RegistrationValidators.email('maria rewit.com'), isNotNull);
      expect(RegistrationValidators.password('1234567'), isNotNull);
      expect(RegistrationValidators.password('12345678'), isNull);
      expect(RegistrationValidators.password('a' * 129), isNotNull);
      expect(RegistrationValidators.passwordConfirmation('12345678', '12345678'), isNull);
      expect(RegistrationValidators.passwordConfirmation('12345679', '12345678'), isNotNull);
      expect(RegistrationValidators.passwordConfirmation('', '12345678'), isNotNull);
    });
  });

  group('AuthRepositoryImpl: cadastro e login social', () {
    test('register envia o contrato existente e guarda a sessão emitida', () async {
      final backend = _Backend((_) async => _json(_authPayload(), 201));
      final (:repo, :storage) = _repository(backend);

      final auth = await repo.register(
        displayName: ' Maria ',
        handle: ' maria_01 ',
        email: ' Maria@Rewit.com ',
        password: 'SenhaSegura1',
      );

      expect(backend.paths, ['/api/v1/auth/register']);
      expect(backend.bodyOf('/api/v1/auth/register'), {
        'displayName': 'Maria',
        'handle': 'maria_01',
        'email': 'maria@rewit.com',
        'password': 'SenhaSegura1',
      });
      expect(backend.requests.single.headers['Authorization'], isNull);
      expect(auth.user.handle, 'maria');
      expect(await storage.getAccessToken(), 'access-1');
      expect(await storage.getRefreshToken(), 'refresh-1');
    });

    test('e-mail ou identificador duplicado propaga o código RFC 7807 sem guardar tokens', () async {
      for (final code in ['EMAIL_ALREADY_EXISTS', 'HANDLE_ALREADY_EXISTS']) {
        final backend = _Backend((_) async => _problem(422, code, 'Já em uso'));
        final (:repo, :storage) = _repository(backend);

        final error = await repo
            .register(displayName: 'Maria', handle: 'maria', email: 'm@rewit.com', password: '12345678')
            .then<ApiException?>((_) => null, onError: (Object e) => e as ApiException);

        expect(error?.statusCode, 422);
        expect(error?.errorCode, code);
        expect(await storage.hasAccessToken(), isFalse);
      }
    });

    test('login social envia somente o ID token e o nonce e guarda a sessão do Rewit', () async {
      final backend = _Backend(_socialBackend);
      final (:repo, :storage) = _repository(backend);

      expect(await repo.requestSocialNonce(), _nonce);
      await repo.signInWithSocial(provider: SocialAuthProvider.apple, idToken: 'id-token', nonce: _nonce);

      expect(backend.paths, ['/api/v1/auth/social/nonce', '/api/v1/auth/social/apple']);
      expect(backend.bodyOf('/api/v1/auth/social/apple'), {'idToken': 'id-token', 'nonce': _nonce});
      expect(await storage.getAccessToken(), 'access-1');
    });

    test('sessão social usa o mesmo refresh token e logout do login local', () async {
      final backend = _Backend(_socialBackend);
      final (:repo, :storage) = _repository(backend);
      await repo.signInWithSocial(provider: SocialAuthProvider.google, idToken: 'id-token', nonce: _nonce);

      final refreshed = await repo.refreshTokens();
      expect(backend.bodyOf('/api/v1/auth/refresh'), {'refreshToken': 'refresh-1'});
      expect(refreshed.tokens.accessToken, 'access-2');
      expect(await storage.getRefreshToken(), 'refresh-2');

      await repo.logout();
      expect(backend.bodyOf('/api/v1/auth/logout'), {'refreshToken': 'refresh-2'});
      expect(await storage.hasAccessToken(), isFalse);
      expect(await storage.getRefreshToken(), isNull);
    });
  });

  group('AuthNotifier: cadastro', () {
    test('cadastro bem-sucedido autentica sem segundo login', () async {
      final backend = _Backend((_) async => _json(_authPayload(), 201));
      final notifier = AuthNotifier(authRepository: _repository(backend).repo);

      final ok = await notifier.register(displayName: 'Maria', handle: 'maria', email: 'm@rewit.com', password: '12345678');

      expect(ok, isTrue);
      expect(notifier.state, isA<Authenticated>());
      expect(backend.paths, ['/api/v1/auth/register']);
    });

    test('erro de validação do backend expõe mensagem, código e erros por campo', () async {
      final backend = _Backend((_) async =>
          _problem(400, 'VALIDATION_ERROR', 'Parâmetros inválidos', fieldErrors: {'handle': 'Handle inválido'}));
      final notifier = AuthNotifier(authRepository: _repository(backend).repo);

      expect(await notifier.register(displayName: 'Maria', handle: 'x__', email: 'm@rewit.com', password: '12345678'),
          isFalse);

      final state = notifier.state as Unauthenticated;
      expect(state.errorCode, 'VALIDATION_ERROR');
      expect(state.fieldErrors, {'handle': 'Handle inválido'});
    });

    test('falha de rede mantém o usuário desautenticado e a nova tentativa funciona', () async {
      var attempts = 0;
      final backend = _Backend((_) async {
        if (attempts++ == 0) throw http.ClientException('sem rede');
        return _json(_authPayload(), 201);
      });
      final notifier = AuthNotifier(authRepository: _repository(backend).repo);

      expect(await notifier.register(displayName: 'Maria', handle: 'maria', email: 'm@rewit.com', password: '12345678'),
          isFalse);
      expect(notifier.state, isA<Unauthenticated>());
      expect((notifier.state as Unauthenticated).errorMessage, isNotNull);

      expect(await notifier.register(displayName: 'Maria', handle: 'maria', email: 'm@rewit.com', password: '12345678'),
          isTrue);
      expect(notifier.state, isA<Authenticated>());
    });
  });

  group('AuthNotifier: login social', () {
    test('entrega ao provedor o SHA-256 do nonce e ao Rewit o nonce em claro', () async {
      final backend = _Backend(_socialBackend);
      final (:repo, :storage) = _repository(backend);
      final social = _FakeSocialSignIn();
      final notifier = AuthNotifier(authRepository: repo, socialSignInService: social);

      expect(await notifier.signInWithSocial(SocialAuthProvider.google), isTrue);

      expect(AuthNotifier.hashSocialNonce('abc'), 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad');
      expect(social.receivedNonces, [AuthNotifier.hashSocialNonce(_nonce)]);
      expect(backend.bodyOf('/api/v1/auth/social/google'), {'idToken': 'provider-id-token', 'nonce': _nonce});
      expect(notifier.state, isA<Authenticated>());
      expect(await storage.getAccessToken(), 'access-1');
    });

    test('cancelamento no provedor não cria sessão e mostra aviso de cancelamento', () async {
      final backend = _Backend(_socialBackend);
      final (:repo, :storage) = _repository(backend);
      final notifier = AuthNotifier(
        authRepository: repo,
        socialSignInService: _FakeSocialSignIn(outcome: const SocialSignInCancelledException(SocialAuthProvider.apple)),
      );

      expect(await notifier.signInWithSocial(SocialAuthProvider.apple), isFalse);

      final state = notifier.state as Unauthenticated;
      expect(state.errorCode, AuthNotifier.socialSignInCancelledCode);
      expect(backend.paths, ['/api/v1/auth/social/nonce']);
      expect(await storage.hasAccessToken(), isFalse);
    });

    test('falha do SDK do provedor é exibida como erro, sem sessão', () async {
      final backend = _Backend(_socialBackend);
      final notifier = AuthNotifier(
        authRepository: _repository(backend).repo,
        socialSignInService: _FakeSocialSignIn(
            outcome: const SocialSignInFailureException(SocialAuthProvider.google, 'Não foi possível entrar com o Google.')),
      );

      expect(await notifier.signInWithSocial(SocialAuthProvider.google), isFalse);
      expect((notifier.state as Unauthenticated).errorMessage, 'Não foi possível entrar com o Google.');
      expect((notifier.state as Unauthenticated).errorCode, 'SOCIAL_SIGN_IN_FAILED');
    });

    test('token recusado pelo backend e conflito de e-mail aparecem com o código do servidor', () async {
      for (final (status, code) in [(401, 'INVALID_SOCIAL_TOKEN'), (401, 'INVALID_SOCIAL_NONCE'), (409, 'SOCIAL_ACCOUNT_EMAIL_CONFLICT')]) {
        final backend = _Backend((request) async => request.url.path.endsWith('/nonce')
            ? _json({'nonce': _nonce, 'expiresIn': 600}, 200)
            : _problem(status, code, 'Recusado'));
        final (:repo, :storage) = _repository(backend);
        final notifier = AuthNotifier(authRepository: repo, socialSignInService: _FakeSocialSignIn());

        expect(await notifier.signInWithSocial(SocialAuthProvider.google), isFalse);
        expect((notifier.state as Unauthenticated).errorCode, code);
        expect(await storage.hasAccessToken(), isFalse);
      }
    });

    test('falha de rede ao pedir o nonce não abre o provedor; a nova tentativa recupera', () async {
      var nonceRequests = 0;
      final backend = _Backend((request) async {
        if (request.url.path.endsWith('/nonce') && nonceRequests++ == 0) throw http.ClientException('sem rede');
        return _socialBackend(request);
      });
      final social = _FakeSocialSignIn();
      final notifier = AuthNotifier(authRepository: _repository(backend).repo, socialSignInService: social);

      expect(await notifier.signInWithSocial(SocialAuthProvider.google), isFalse);
      expect(social.receivedNonces, isEmpty);
      expect((notifier.state as Unauthenticated).errorMessage, isNotNull);

      expect(await notifier.signInWithSocial(SocialAuthProvider.google), isTrue);
      expect(notifier.state, isA<Authenticated>());
    });

    test('provedor não configurado não chama o backend nem o SDK', () async {
      final backend = _Backend(_socialBackend);
      final social = _FakeSocialSignIn(available: const {});
      final notifier = AuthNotifier(authRepository: _repository(backend).repo, socialSignInService: social);

      expect(notifier.isSocialProviderAvailable(SocialAuthProvider.google), isFalse);
      expect(await notifier.signInWithSocial(SocialAuthProvider.google), isFalse);
      expect((notifier.state as Unauthenticated).errorCode, 'SOCIAL_PROVIDER_UNAVAILABLE');
      expect(backend.requests, isEmpty);
      expect(social.receivedNonces, isEmpty);
    });
  });

  group('Disponibilidade dos provedores por build e plataforma', () {
    test('sem identificadores públicos nenhum provedor fica disponível', () {
      final service = PlatformSocialSignInService(config: const SocialAuthConfig(), platform: TargetPlatform.android);
      expect(service.isAvailable(SocialAuthProvider.google), isFalse);
      expect(service.isAvailable(SocialAuthProvider.apple), isFalse);
    });

    test('Android: Google com serverClientId; Apple só com Services ID e retorno https', () {
      const config = SocialAuthConfig(
        googleServerClientId: 'web.apps.googleusercontent.com',
        appleServiceId: 'app.rewit.signin',
        appleRedirectUri: 'https://api.rewit.test/api/v1/auth/apple/callback',
      );
      final android = PlatformSocialSignInService(config: config, platform: TargetPlatform.android);
      expect(android.isAvailable(SocialAuthProvider.google), isTrue);
      expect(android.isAvailable(SocialAuthProvider.apple), isTrue);

      final insecure = PlatformSocialSignInService(
        config: const SocialAuthConfig(appleServiceId: 'app.rewit.signin', appleRedirectUri: 'http://api.rewit.test/cb'),
        platform: TargetPlatform.android,
      );
      expect(insecure.isAvailable(SocialAuthProvider.apple), isFalse);
    });

    test('iOS: Google exige também o client ID iOS; Apple exige a capability habilitada', () {
      final partial = PlatformSocialSignInService(
        config: const SocialAuthConfig(googleServerClientId: 'web.apps.googleusercontent.com'),
        platform: TargetPlatform.iOS,
      );
      expect(partial.isAvailable(SocialAuthProvider.google), isFalse);
      expect(partial.isAvailable(SocialAuthProvider.apple), isFalse);

      final full = PlatformSocialSignInService(
        config: const SocialAuthConfig(
          googleServerClientId: 'web.apps.googleusercontent.com',
          googleIosClientId: 'ios.apps.googleusercontent.com',
          appleNativeEnabled: true,
        ),
        platform: TargetPlatform.iOS,
      );
      expect(full.isAvailable(SocialAuthProvider.google), isTrue);
      expect(full.isAvailable(SocialAuthProvider.apple), isTrue);
    });

    test('obtainIdToken de provedor indisponível falha sem abrir o SDK', () {
      final service = PlatformSocialSignInService(config: const SocialAuthConfig(), platform: TargetPlatform.android);
      expect(() => service.obtainIdToken(SocialAuthProvider.google, providerNonce: 'x'),
          throwsA(isA<SocialSignInFailureException>()));
    });
  });

  group('Telas de entrada e cadastro', () {
    Future<void> pumpLogin(WidgetTester tester, AuthNotifier notifier) async {
      tester.view.physicalSize = const Size(1080, 2400);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.reset);
      await tester.pumpWidget(MaterialApp(home: LoginScreen(authNotifier: notifier)));
    }

    testWidgets('login apresenta e-mail/senha, Google, Apple e Criar conta', (tester) async {
      final notifier = AuthNotifier(
        authRepository: _repository(_Backend(_socialBackend)).repo,
        socialSignInService: _FakeSocialSignIn(),
      )..clearMessage();
      await pumpLogin(tester, notifier);

      expect(find.text('Entrar'), findsOneWidget);
      expect(find.text('Continuar com Google'), findsOneWidget);
      expect(find.text('Continuar com Apple'), findsOneWidget);
      expect(find.text('Não tem conta? Criar conta'), findsOneWidget);
      expect(find.text('Reativar conta desativada'), findsOneWidget);
      expect(find.byKey(const Key('social_sign_in_unavailable_hint')), findsNothing);
    });

    testWidgets('provedores sem configuração aparecem desabilitados e com o motivo', (tester) async {
      final notifier = AuthNotifier(authRepository: _repository(_Backend(_socialBackend)).repo);
      await pumpLogin(tester, notifier);

      for (final provider in SocialAuthProvider.values) {
        final button = tester.widget<OutlinedButton>(find.descendant(
          of: find.byKey(Key('social_sign_in_${provider.name}')),
          matching: find.byWidgetPredicate((w) => w is OutlinedButton),
        ));
        expect(button.onPressed, isNull);
      }
      expect(find.textContaining('ainda não está disponível'), findsOneWidget);
    });

    testWidgets('toque em Continuar com Google executa o fluxo e autentica', (tester) async {
      final notifier = AuthNotifier(
        authRepository: _repository(_Backend(_socialBackend)).repo,
        socialSignInService: _FakeSocialSignIn(),
      );
      await pumpLogin(tester, notifier);

      await tester.tap(find.text('Continuar com Google'));
      await tester.pumpAndSettle();

      expect(notifier.state, isA<Authenticated>());
    });

    testWidgets('cadastro valida no cliente antes de chamar o backend', (tester) async {
      final backend = _Backend((_) async => _json(_authPayload(), 201));
      final notifier = AuthNotifier(authRepository: _repository(backend).repo);
      await pumpLogin(tester, notifier);

      await tester.tap(find.text('Não tem conta? Criar conta'));
      await tester.pumpAndSettle();
      expect(find.byType(RegisterScreen), findsOneWidget);

      await tester.enterText(find.byKey(const Key('register_display_name')), 'M');
      await tester.enterText(find.byKey(const Key('register_handle')), 'maria.silva');
      await tester.enterText(find.byKey(const Key('register_email')), 'maria@');
      await tester.enterText(find.byKey(const Key('register_password')), '1234567');
      await tester.enterText(find.byKey(const Key('register_password_confirmation')), '7654321');
      await tester.ensureVisible(find.byKey(const Key('register_submit')));
      await tester.tap(find.byKey(const Key('register_submit')));
      await tester.pumpAndSettle();

      expect(find.text('O nome deve ter entre 2 e 100 caracteres'), findsOneWidget);
      expect(find.text('Use apenas letras sem acento, números e sublinhado (_)'), findsOneWidget);
      expect(find.text('Informe um e-mail válido'), findsOneWidget);
      expect(find.text('A senha deve ter no mínimo 8 caracteres'), findsOneWidget);
      expect(find.text('As senhas não coincidem'), findsOneWidget);
      expect(backend.requests, isEmpty);
    });

    Future<void> fillValidRegistration(WidgetTester tester) async {
      await tester.enterText(find.byKey(const Key('register_display_name')), 'Maria');
      await tester.enterText(find.byKey(const Key('register_handle')), '@Maria_01');
      await tester.enterText(find.byKey(const Key('register_email')), 'maria@rewit.com');
      await tester.enterText(find.byKey(const Key('register_password')), 'SenhaSegura1');
      await tester.enterText(find.byKey(const Key('register_password_confirmation')), 'SenhaSegura1');
      await tester.ensureVisible(find.byKey(const Key('register_submit')));
      await tester.tap(find.byKey(const Key('register_submit')));
      await tester.pumpAndSettle();
    }

    testWidgets('identificador duplicado aparece no campo e a mensagem some ao voltar ao login', (tester) async {
      final backend = _Backend((_) async => _problem(422, 'HANDLE_ALREADY_EXISTS', 'Nome de usuário (@handle) já está em uso'));
      final notifier = AuthNotifier(authRepository: _repository(backend).repo);
      await pumpLogin(tester, notifier);
      await tester.tap(find.text('Não tem conta? Criar conta'));
      await tester.pumpAndSettle();

      await fillValidRegistration(tester);

      expect(backend.bodyOf('/api/v1/auth/register')['handle'], 'maria_01');
      // Banner (RFC 7807) e erro no próprio campo
      expect(find.text('Nome de usuário (@handle) já está em uso'), findsNWidgets(2));
      expect(notifier.state, isA<Unauthenticated>());

      await tester.tap(find.byKey(const Key('register_back_to_login')));
      await tester.pumpAndSettle();
      expect(find.byType(RegisterScreen), findsNothing);
      expect(find.text('Nome de usuário (@handle) já está em uso'), findsNothing);
    });

    testWidgets('cadastro bem-sucedido fecha a tela e deixa a sessão autenticada', (tester) async {
      final backend = _Backend((_) async => _json(_authPayload(), 201));
      final notifier = AuthNotifier(authRepository: _repository(backend).repo);
      await pumpLogin(tester, notifier);
      await tester.tap(find.text('Não tem conta? Criar conta'));
      await tester.pumpAndSettle();

      await fillValidRegistration(tester);

      expect(notifier.state, isA<Authenticated>());
      expect(find.byType(RegisterScreen), findsNothing);
    });
  });
}
