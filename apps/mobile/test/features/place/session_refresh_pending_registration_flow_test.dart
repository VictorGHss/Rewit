import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:rewit_mobile/app/app.dart';
import 'package:rewit_mobile/app/config/app_config.dart';
import 'package:rewit_mobile/app/router/app_router.dart';
import 'package:rewit_mobile/core/network/http_client.dart';
import 'package:rewit_mobile/core/network/session_refresher.dart';
import 'package:rewit_mobile/core/network/token_refreshing_client.dart';
import 'package:rewit_mobile/core/storage/token_storage.dart';
import 'package:rewit_mobile/features/auth/data/models/auth_user_dto.dart';
import 'package:rewit_mobile/features/auth/data/repositories/auth_repository_impl.dart';
import 'package:rewit_mobile/features/auth/domain/entities/auth_state.dart';
import 'package:rewit_mobile/features/auth/presentation/screens/login_screen.dart';
import 'package:rewit_mobile/features/auth/presentation/state/auth_notifier.dart';
import 'package:rewit_mobile/features/place/data/repositories/place_repository_impl.dart';
import 'package:rewit_mobile/features/place/domain/services/pending_place_registration_store.dart';
import 'package:rewit_mobile/features/search/domain/entities/search_entities.dart';
import 'package:rewit_mobile/features/search/domain/repositories/search_repository.dart';

/// HTTP 401 no upload da foto de um local recém-criado, pela cadeia montada em `main.dart`:
/// TokenRefreshingClient + SessionRefresher -> RewitHttpClient -> AuthRepositoryImpl/AuthNotifier ->
/// PlaceRepositoryImpl -> PendingPlaceRegistrationStore -> AppRouter -> RewitApp.
///
/// Só o transporte HTTP (servidor falso determinístico) e a busca são substituídos. O upload é multipart: o
/// TokenRefreshingClient não o repete, o 401 chega ao RewitHttpClient, que aciona onSessionExpired, e o
/// AuthNotifier decide pela renovação: aceita (sessão segue), recusada (sessão encerrada) ou indisponível
/// (sessão preservada). Em todos os casos a pendência gravada antes do upload continua com a conta de origem.

const _baseUrl = 'http://api.rewit.test';
const _userA = AuthUserDto(id: 'user-a', handle: 'ana', email: 'ana@rewit.test', displayName: 'Ana');
const _userB = AuthUserDto(id: 'user-b', handle: 'bruno', email: 'bruno@rewit.test', displayName: 'Bruno');
const _placeId = '0000000d-0000-4000-8000-00000000000d';
final _uuidV4 = RegExp(r'^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$');

const _login = 'POST /api/v1/auth/login';
const _logout = 'POST /api/v1/auth/logout';
const _refresh = 'POST /api/v1/auth/refresh';
const _createPlace = 'POST /api/v1/places';
const _upload = 'POST /api/v1/places/$_placeId/media';

const _renewedMessage =
    'A sessão foi renovada. Toque em "Reenviar Fotografia e Concluir" para enviar a foto novamente.';
const _unverifiedMessage = 'Não foi possível verificar sua sessão agora. Tente novamente.';
const _expiredMessage = 'Sua sessão expirou. Faça login novamente.';

class _ApiCall {
  final String route;
  final String? authorization;
  final String? idempotencyKey;
  final String? refreshToken;

  _ApiCall(this.route, {this.authorization, this.idempotencyKey, this.refreshToken});
}

enum _RefreshBehavior { accept, reject, transportFailure, noResponse }

/// Servidor falso: emite pares de tokens, recusa access tokens vencidos com o código real do backend
/// (AUTHENTICATION_REQUIRED) e rotaciona o refresh token a cada renovação aceita.
class _FakeRewitApi {
  final List<_ApiCall> calls = [];
  _RefreshBehavior refreshBehavior = _RefreshBehavior.accept;

  /// O access token usado no cadastro vence logo em seguida: o upload seguinte recebe 401.
  bool expireTokenAfterCreatePlace = false;

  /// Quando definido, o upload recebe 401 com este código, que não é o de token recusado.
  String? uploadOtherUnauthorizedCode;

  final Set<String> _validAccessTokens = {};
  final Map<String, AuthUserDto> _refreshOwners = {};
  int _issued = 0;

  late final MockClient client = MockClient(_handle);

  List<String> get routes => calls.map((c) => c.route).toList();
  List<_ApiCall> callsTo(String route) => calls.where((c) => c.route == route).toList();

  Future<http.Response> _handle(http.Request request) async {
    final route = '${request.method} ${request.url.path}';
    final authorization = request.headers['authorization'];
    String? refreshToken;
    if (route == _refresh) {
      refreshToken = (jsonDecode(request.body) as Map<String, dynamic>)['refreshToken'] as String?;
    }
    calls.add(_ApiCall(route,
        authorization: authorization, idempotencyKey: request.headers['idempotency-key'], refreshToken: refreshToken));

    switch (route) {
      case _login:
        final email = (jsonDecode(request.body) as Map<String, dynamic>)['email'];
        return _tokens(email == _userA.email ? _userA : _userB);
      case _refresh:
        switch (refreshBehavior) {
          case _RefreshBehavior.accept:
            final owner = _refreshOwners.remove(refreshToken);
            return owner == null ? _problem(401, 'INVALID_REFRESH_TOKEN') : _tokens(owner);
          case _RefreshBehavior.reject:
            _refreshOwners.remove(refreshToken);
            return _problem(401, 'REFRESH_TOKEN_REVOKED');
          case _RefreshBehavior.transportFailure:
            throw http.ClientException('Connection reset by peer', request.url);
          case _RefreshBehavior.noResponse:
            return Completer<http.Response>().future;
        }
      case _logout:
        return http.Response('', 204);
      case _createPlace:
        if (!_isValid(authorization)) return _problem(401, 'AUTHENTICATION_REQUIRED');
        if (expireTokenAfterCreatePlace) _validAccessTokens.remove(authorization!.substring('Bearer '.length));
        return http.Response(
            jsonEncode({'id': _placeId, 'name': 'Café Renovado', 'slug': 'cafe-renovado', 'addressText': 'Rua R, 1', 'status': 'ACTIVE'}),
            201,
            headers: {'content-type': 'application/json'});
      case _upload:
        final otherCode = uploadOtherUnauthorizedCode;
        if (otherCode != null) return _problem(401, otherCode, detail: 'Envio não autorizado para este local.');
        if (!_isValid(authorization)) return _problem(401, 'AUTHENTICATION_REQUIRED');
        return http.Response(jsonEncode({'url': '/api/v1/places/$_placeId/media/ok'}), 201,
            headers: {'content-type': 'application/json'});
    }
    return http.Response('{}', 404);
  }

  bool _isValid(String? authorization) =>
      authorization != null && _validAccessTokens.contains(authorization.substring('Bearer '.length));

  http.Response _tokens(AuthUserDto user) {
    _issued++;
    final access = 'access-${user.id}-$_issued';
    final refresh = 'refresh-${user.id}-$_issued';
    _validAccessTokens.add(access);
    _refreshOwners[refresh] = user;
    return http.Response(
        jsonEncode({'accessToken': access, 'refreshToken': refresh, 'tokenType': 'Bearer', 'expiresIn': 900, 'user': user.toJson()}),
        200,
        headers: {'content-type': 'application/json'});
  }

  static http.Response _problem(int status, String code, {String? detail}) => http.Response.bytes(
      utf8.encode(jsonEncode(
          {'type': 'about:blank', 'title': 'Unauthorized', 'status': status, 'code': code, 'detail': detail ?? code})),
      status,
      headers: {'content-type': 'application/problem+json; charset=utf-8'});
}

class _EmptySearchRepository implements SearchRepository {
  @override
  Future<SearchPage> search({required String query, int page = 0, int size = 20}) async => const SearchPage(
      items: [], pageNumber: 0, pageSize: 20, totalElements: 0, totalPages: 0, isLast: true);
}

/// Uma execução do app com a fiação de `main.dart`; os contadores só observam os callbacks reais.
class _App {
  final AuthNotifier auth;
  final PendingPlaceRegistrationStore store;
  final InMemoryTokenStorage tokens;
  int sessionExpiredCalls = 0;
  int clearLocalSessionCalls = 0;

  _App(this.auth, this.store, this.tokens);
}

void main() {
  late Directory tempDir;
  late File photo;
  late _FakeRewitApi api;
  late InMemoryPendingPlaceRegistrationPersistence persistence;

  setUp(() {
    tempDir = Directory.systemTemp.createTempSync('rewit_refresh_flow_');
    photo = File('${tempDir.path}/photo.jpg')..writeAsBytesSync(Uint8List.fromList([0xFF, 0xD8, 0xFF, 0xE0, 7]));
    api = _FakeRewitApi();
    persistence = InMemoryPendingPlaceRegistrationPersistence();
  });

  tearDown(() {
    try {
      tempDir.deleteSync(recursive: true);
    } catch (_) {}
  });

  Future<_App> startApp(WidgetTester tester, {Duration timeout = const Duration(seconds: 15)}) async {
    tester.view.physicalSize = const Size(1200, 3200);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    final tokens = InMemoryTokenStorage();
    late final _App app;
    late final AuthNotifier authNotifier;
    late final AuthRepositoryImpl authRepository;
    final sessionRefresher = SessionRefresher(
      refreshAccessToken: () async => (await authRepository.refreshTokens()).tokens.accessToken,
    );
    final httpClient = RewitHttpClient(
      baseUrl: _baseUrl,
      timeout: timeout,
      client: TokenRefreshingClient(inner: api.client, sessionRefresher: sessionRefresher),
      tokenStorage: tokens,
      onSessionExpired: () {
        app.sessionExpiredCalls++;
        unawaited(authNotifier.handleSessionExpired());
      },
    );
    authRepository = AuthRepositoryImpl(httpClient: httpClient, tokenStorage: tokens);
    authNotifier = AuthNotifier(
      authRepository: authRepository,
      sessionRefresher: sessionRefresher,
      clearLocalSession: () {
        app.clearLocalSessionCalls++;
        return tokens.clearTokens();
      },
    );
    final store = PendingPlaceRegistrationStore(persistence: persistence);
    app = _App(authNotifier, store, tokens);

    final router = AppRouter(
      authNotifier: authNotifier,
      searchRepository: _EmptySearchRepository(),
      placeRepository: PlaceRepositoryImpl(httpClient: httpClient),
      pendingRegistrationStore: store,
      onPickManualPlacePhoto: (_) async => photo,
    );
    await authNotifier.checkAuthStatus();
    await tester.pumpWidget(RewitApp(
      key: UniqueKey(),
      config: AppConfig(apiBaseUrl: _baseUrl, timeout: timeout),
      router: router,
    ));
    await tester.pumpAndSettle();
    return app;
  }

  Future<void> login(WidgetTester tester, _App app, AuthUserDto user) async {
    await app.auth.login(user.email, 'senha');
    await tester.pumpAndSettle();
    expect(find.text('Avaliar'), findsOneWidget, reason: 'Home autenticada de ${user.id}');
  }

  Future<void> openReviewTab(WidgetTester tester) async {
    await tester.tap(find.text('Avaliar'));
    await tester.pumpAndSettle();
  }

  Future<void> logoutThroughUi(WidgetTester tester) async {
    final moreOptions = find.byKey(const Key('home_more_options_button'));
    if (moreOptions.evaluate().isNotEmpty) {
      await tester.tap(moreOptions);
      await tester.pumpAndSettle();
    }
    await tester.tap(find.byIcon(Icons.logout));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(ElevatedButton, 'Sair'));
    await tester.pumpAndSettle();
    expect(find.text('Avaliar'), findsNothing, reason: 'Home desmontada após logout');
  }

  /// Cadastra o local e tenta enviar a foto; o sheet fica aberto com o resultado do upload.
  Future<void> submitNewRegistration(WidgetTester tester) async {
    await tester.enterText(find.widgetWithText(TextFormField, 'O que você quer avaliar? *'), 'Café Renovado');
    await tester.pump(const Duration(milliseconds: 400));
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.byKey(const Key('register_manual_place_button')));
    await tester.tap(find.byKey(const Key('register_manual_place_button')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('manual_place_address_field')), 'Rua R, 1');
    await tester.tap(find.text('Câmera'));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('manual_place_submit_button')));
    await tester.pumpAndSettle();
  }

  Future<void> resumeFromBanner(WidgetTester tester) async {
    expect(find.byKey(const Key('target_pending_registration_banner')), findsOneWidget);
    await tester.tap(find.byKey(const Key('target_resume_pending_registration_button')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('manual_place_partial_created_banner')), findsOneWidget);
    await tester.tap(find.byKey(const Key('manual_place_submit_button')));
    await tester.pumpAndSettle();
  }

  Finder pendingBanner() => find.byKey(const Key('target_pending_registration_banner'));
  Finder sheetSubmit() => find.byKey(const Key('manual_place_submit_button'));
  Finder sheetError() => find.byKey(const Key('manual_place_error_banner'));

  /// Texto do banner de erro do sheet (vazio se não houver banner).
  String sheetErrorText(WidgetTester tester) => tester
      .widgetList<Text>(find.descendant(of: sheetError(), matching: find.byType(Text)))
      .map((t) => t.data ?? '')
      .join(' ');

  testWidgets(
      'A: 401 no upload com refresh aceito — upload multipart não é repetido, sessão renovada continua, pendência da '
      'conta de origem é retomada com a mesma chave e sem novo createPlace', (tester) async {
    final app = await startApp(tester);
    await login(tester, app, _userA);
    final firstAccess = await app.tokens.getAccessToken();
    final firstRefresh = await app.tokens.getRefreshToken();
    await openReviewTab(tester);

    api.expireTokenAfterCreatePlace = true;
    await submitNewRegistration(tester);

    // Ordem real: cadastro com o token original, upload recusado, uma única renovação; nada é reenviado sozinho
    expect(api.routes, [_login, _createPlace, _upload, _refresh]);
    expect(api.callsTo(_createPlace).single.authorization, 'Bearer $firstAccess');
    expect(api.callsTo(_upload).single.authorization, 'Bearer $firstAccess');
    expect(api.callsTo(_refresh).single.refreshToken, firstRefresh);
    final key = api.callsTo(_upload).single.idempotencyKey;
    expect(key, matches(_uuidV4));

    // Sessão renovada e mantida: novos tokens salvos, nada apagado, Home e sheet continuam montados
    expect(app.sessionExpiredCalls, 1);
    expect(app.clearLocalSessionCalls, 0);
    expect(app.auth.state, isA<Authenticated>());
    expect((app.auth.state as Authenticated).user.id, _userA.id);
    final renewedAccess = await app.tokens.getAccessToken();
    expect(renewedAccess, isNot(firstAccess));
    expect(await app.tokens.getRefreshToken(), isNot(firstRefresh));
    expect(find.byType(LoginScreen), findsNothing);
    expect(sheetSubmit(), findsOneWidget);
    // A mensagem reflete a renovação confirmada, não a expiração
    expect(sheetErrorText(tester), _renewedMessage);

    // A pendência gravada antes do upload continua da conta de origem, com a chave do envio que falhou
    final pending = await app.store.loadLatest(_userA.id);
    expect(pending, isNotNull);
    expect(pending!.place.id, _placeId);
    expect(pending.uploadIdempotencyKey, key);
    expect(await app.store.loadLatest(_userB.id), isNull);

    // Retomada legítima pela conta de origem: o pedido parte do banner, com o token renovado
    await tester.tap(find.byKey(const Key('manual_place_close_button')));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(OutlinedButton, 'Trocar'));
    await tester.pumpAndSettle();
    await resumeFromBanner(tester);

    expect(api.callsTo(_createPlace), hasLength(1), reason: 'A retomada não cria outro local');
    expect(api.callsTo(_upload).map((c) => c.idempotencyKey), [key, key]);
    expect(api.callsTo(_upload).last.authorization, 'Bearer $renewedAccess');
    expect(api.callsTo(_refresh), hasLength(1));
    expect(await app.store.loadLatest(_userA.id), isNull, reason: 'Envio concluído: nada a retomar');
    expect(pendingBanner(), findsNothing);
  });

  testWidgets(
      'B: 401 no upload com refresh recusado — sessão encerrada com tokens apagados, Home e sheet desmontados, '
      'pendência só da conta de origem, retomada após novo login sem novo createPlace', (tester) async {
    var app = await startApp(tester);
    await login(tester, app, _userA);
    final firstAccess = await app.tokens.getAccessToken();
    final firstRefresh = await app.tokens.getRefreshToken();
    await openReviewTab(tester);

    api.expireTokenAfterCreatePlace = true;
    api.refreshBehavior = _RefreshBehavior.reject;
    await submitNewRegistration(tester);

    expect(api.routes, [_login, _createPlace, _upload, _refresh]);
    expect(api.callsTo(_upload).single.authorization, 'Bearer $firstAccess');
    expect(api.callsTo(_refresh).single.refreshToken, firstRefresh);
    final key = api.callsTo(_upload).single.idempotencyKey;
    expect(key, matches(_uuidV4));

    // Recusa explícita: onSessionExpired -> renovação recusada -> clearLocalSession -> SESSION_EXPIRED
    expect(app.sessionExpiredCalls, 1);
    expect(app.clearLocalSessionCalls, 1);
    expect(await app.tokens.getAccessToken(), isNull);
    expect(await app.tokens.getRefreshToken(), isNull);
    final state = app.auth.state;
    expect(state, isA<Unauthenticated>());
    expect((state as Unauthenticated).errorCode, 'SESSION_EXPIRED');
    // RewitApp volta a pilha à raiz: login visível, Home e sheet desmontados
    expect(find.byType(LoginScreen), findsOneWidget);
    expect(find.text('Avaliar'), findsNothing);
    expect(sheetSubmit(), findsNothing);
    // Nenhuma mensagem concorrente sobrevive à expiração
    expect(sheetError(), findsNothing);
    expect(find.textContaining('A sessão foi renovada'), findsNothing);

    // A pendência sobrevive à desmontagem e pertence só à conta de origem
    final pending = await app.store.loadLatest(_userA.id);
    expect(pending, isNotNull);
    expect(pending!.place.id, _placeId);
    expect(pending.uploadIdempotencyKey, key);

    // Outra conta entra: não vê nem retoma a operação de A
    api.expireTokenAfterCreatePlace = false;
    api.refreshBehavior = _RefreshBehavior.accept;
    await login(tester, app, _userB);
    await openReviewTab(tester);
    expect(pendingBanner(), findsNothing);
    expect(find.textContaining('Café Renovado'), findsNothing);
    expect(await app.store.loadLatest(_userB.id), isNull);
    await logoutThroughUi(tester);

    // A conta original volta (inclusive após reinício do app) e retoma: mesmo local, mesma chave
    app = await startApp(tester);
    await login(tester, app, _userA);
    final newAccess = await app.tokens.getAccessToken();
    await openReviewTab(tester);
    expect(find.textContaining('Café Renovado'), findsWidgets);
    await resumeFromBanner(tester);

    expect(api.callsTo(_createPlace), hasLength(1), reason: 'A retomada não cria outro local');
    expect(api.callsTo(_upload).map((c) => c.idempotencyKey), [key, key]);
    expect(api.callsTo(_upload).last.authorization, 'Bearer $newAccess');
    expect(api.callsTo(_refresh), hasLength(1), reason: 'Só a renovação recusada; o novo login não renova');
    expect(await app.store.loadLatest(_userA.id), isNull);
    expect(app.auth.state, isA<Authenticated>());
  });

  testWidgets(
      'C: 401 no upload com falha de transporte no refresh — sessão preservada (não é recusa), tokens intactos, '
      'pendência retomada só pela conta de origem, com a mesma chave', (tester) async {
    var app = await startApp(tester);
    await login(tester, app, _userA);
    final firstAccess = await app.tokens.getAccessToken();
    final firstRefresh = await app.tokens.getRefreshToken();
    await openReviewTab(tester);

    api.expireTokenAfterCreatePlace = true;
    api.refreshBehavior = _RefreshBehavior.transportFailure;
    await submitNewRegistration(tester);

    expect(api.routes, [_login, _createPlace, _upload, _refresh], reason: 'Uma única tentativa de renovação');
    expect(api.callsTo(_refresh).single.refreshToken, firstRefresh);
    final key = api.callsTo(_upload).single.idempotencyKey;
    expect(key, matches(_uuidV4));

    // Indisponível ≠ recusado: nada é apagado e a sessão continua; o app volta ao controle do usuário
    expect(app.sessionExpiredCalls, 1);
    expect(app.clearLocalSessionCalls, 0);
    expect(app.auth.state, isA<Authenticated>());
    expect(await app.tokens.getAccessToken(), firstAccess);
    expect(await app.tokens.getRefreshToken(), firstRefresh);
    expect(find.byType(LoginScreen), findsNothing);
    expect(sheetSubmit(), findsOneWidget);
    // Nem renovada nem expirada: a verificação não foi possível
    expect(sheetErrorText(tester), _unverifiedMessage);

    final pending = await app.store.loadLatest(_userA.id);
    expect(pending, isNotNull);
    expect(pending!.place.id, _placeId);
    expect(pending.uploadIdempotencyKey, key);

    await tester.tap(find.byKey(const Key('manual_place_close_button')));
    await tester.pumpAndSettle();
    expect(find.text('Avaliar'), findsOneWidget, reason: 'Home continua montada');

    // Troca de conta: B não vê a operação de A
    await logoutThroughUi(tester);
    await login(tester, app, _userB);
    await openReviewTab(tester);
    expect(pendingBanner(), findsNothing);
    expect(await app.store.loadLatest(_userB.id), isNull);
    await logoutThroughUi(tester);

    // A volta, após reinício, e retoma com a mesma chave e sem novo cadastro
    api.expireTokenAfterCreatePlace = false;
    api.refreshBehavior = _RefreshBehavior.accept;
    app = await startApp(tester);
    await login(tester, app, _userA);
    await openReviewTab(tester);
    await resumeFromBanner(tester);

    expect(api.callsTo(_createPlace), hasLength(1));
    expect(api.callsTo(_upload).map((c) => c.idempotencyKey), [key, key]);
    expect(api.callsTo(_refresh), hasLength(1));
    expect(await app.store.loadLatest(_userA.id), isNull);
  });

  testWidgets(
      'C: 401 no upload com refresh sem resposta — termina no prazo do cliente sem encerrar a sessão; a renovação '
      'seguinte não fica presa e o envio conclui com a chave original', (tester) async {
    const timeout = Duration(seconds: 5);
    final app = await startApp(tester, timeout: timeout);
    await login(tester, app, _userA);
    final firstAccess = await app.tokens.getAccessToken();
    final firstRefresh = await app.tokens.getRefreshToken();
    await openReviewTab(tester);

    api.expireTokenAfterCreatePlace = true;
    api.refreshBehavior = _RefreshBehavior.noResponse;
    await submitNewRegistration(tester);
    expect(api.routes, [_login, _createPlace, _upload, _refresh]);
    final key = api.callsTo(_upload).single.idempotencyKey;

    // O prazo do cliente esgota: a renovação vira "indisponível", sem travar o fluxo
    await tester.pump(timeout + const Duration(seconds: 1));
    await tester.pumpAndSettle();
    expect(app.sessionExpiredCalls, 1);
    expect(app.clearLocalSessionCalls, 0);
    expect(app.auth.state, isA<Authenticated>());
    expect(await app.tokens.getAccessToken(), firstAccess);
    expect(await app.tokens.getRefreshToken(), firstRefresh);
    expect(find.byType(LoginScreen), findsNothing);
    expect(sheetErrorText(tester), _unverifiedMessage);
    expect((await app.store.loadLatest(_userA.id))!.uploadIdempotencyKey, key);
    expect(await app.store.loadLatest(_userB.id), isNull);

    // Nova tentativa no mesmo sheet: o 401 dispara uma renovação NOVA (a anterior não ficou presa) e aceita
    api.refreshBehavior = _RefreshBehavior.accept;
    await tester.tap(sheetSubmit());
    await tester.pumpAndSettle();
    expect(api.callsTo(_refresh), hasLength(2));
    expect(app.sessionExpiredCalls, 2);
    final renewedAccess = await app.tokens.getAccessToken();
    expect(renewedAccess, isNot(firstAccess));
    expect(sheetErrorText(tester), _renewedMessage, reason: 'A mensagem anterior não fica presa');

    // Envio seguinte com o token renovado conclui a operação original
    await tester.tap(sheetSubmit());
    await tester.pump();
    expect(sheetError(), findsNothing, reason: 'O reenvio limpa a mensagem anterior');
    await tester.pumpAndSettle();
    expect(api.callsTo(_createPlace), hasLength(1));
    expect(api.callsTo(_upload).map((c) => c.idempotencyKey), [key, key, key]);
    expect(api.callsTo(_upload).last.authorization, 'Bearer $renewedAccess');
    expect(sheetSubmit(), findsNothing, reason: 'Sheet fechado pelo sucesso');
    expect(await app.store.loadLatest(_userA.id), isNull);
    expect(app.clearLocalSessionCalls, 0);
  });

  testWidgets(
      'A: refresh aceito — reenvio manual pelo mesmo sheet usa a chave original e o token renovado, sem novo '
      'cadastro, e a mensagem anterior não fica presa', (tester) async {
    final app = await startApp(tester);
    await login(tester, app, _userA);
    await openReviewTab(tester);

    api.expireTokenAfterCreatePlace = true;
    await submitNewRegistration(tester);
    expect(app.auth.state, isA<Authenticated>());
    expect(api.callsTo(_refresh), hasLength(1));
    expect(sheetErrorText(tester), _renewedMessage);
    final renewedAccess = await app.tokens.getAccessToken();
    final key = api.callsTo(_upload).single.idempotencyKey;
    expect((await app.store.loadLatest(_userA.id))!.uploadIdempotencyKey, key);

    await tester.tap(sheetSubmit());
    await tester.pump();
    expect(sheetError(), findsNothing, reason: 'O reenvio limpa a mensagem anterior');
    await tester.pumpAndSettle();

    expect(api.routes, [_login, _createPlace, _upload, _refresh, _upload]);
    expect(api.callsTo(_upload).map((c) => c.idempotencyKey), [key, key]);
    expect(api.callsTo(_upload).last.authorization, 'Bearer $renewedAccess');
    expect(sheetSubmit(), findsNothing, reason: 'Sheet fechado pelo sucesso');
    expect(await app.store.loadLatest(_userA.id), isNull);
    expect(app.sessionExpiredCalls, 1);
    expect(app.clearLocalSessionCalls, 0);
  });

  testWidgets(
      'D: 401 do upload com outra causa e refresh aceito — mostra o motivo do servidor, sem afirmar renovação nem '
      'expiração', (tester) async {
    final app = await startApp(tester);
    await login(tester, app, _userA);
    final firstAccess = await app.tokens.getAccessToken();
    await openReviewTab(tester);

    api.uploadOtherUnauthorizedCode = 'UNAUTHORIZED';
    await submitNewRegistration(tester);

    // O cliente ainda verifica a sessão (qualquer 401 autenticado aciona onSessionExpired) e ela é renovada
    expect(api.routes, [_login, _createPlace, _upload, _refresh]);
    expect(api.callsTo(_upload).single.authorization, 'Bearer $firstAccess');
    expect(app.sessionExpiredCalls, 1);
    expect(app.clearLocalSessionCalls, 0);
    expect(app.auth.state, isA<Authenticated>());
    // ...mas a recusa não era do token: a mensagem não atribui o erro à sessão
    final message = sheetErrorText(tester);
    expect(message, 'Envio não autorizado para este local.');
    expect(message, isNot(contains('renovada')));
    expect(message, isNot(_expiredMessage));
    final key = api.callsTo(_upload).single.idempotencyKey;
    expect((await app.store.loadLatest(_userA.id))!.uploadIdempotencyKey, key);
  });
}
