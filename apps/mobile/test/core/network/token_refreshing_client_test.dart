import 'dart:async';
import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/core/network/http_client.dart';
import 'package:rewit_mobile/core/network/session_refresher.dart';
import 'package:rewit_mobile/core/network/token_refreshing_client.dart';
import 'package:rewit_mobile/core/storage/token_storage.dart';

class _RecordingClient extends http.BaseClient {
  final Future<http.StreamedResponse> Function(http.BaseRequest request) handler;
  final List<http.BaseRequest> requests = [];
  final List<String> bodies = [];

  _RecordingClient(this.handler);

  @override
  Future<http.StreamedResponse> send(http.BaseRequest request) async {
    requests.add(request);
    bodies.add(request is http.Request ? request.body : '');
    return handler(request);
  }
}

http.StreamedResponse _json(int status, Map<String, dynamic> body) =>
    http.StreamedResponse(Stream.value(utf8.encode(jsonEncode(body))), status);

http.StreamedResponse _tokenRejected() =>
    _json(401, {'status': 401, 'code': 'AUTHENTICATION_REQUIRED', 'detail': 'Autenticação necessária'});

ApiException _apiError(int status, String code) => ApiException(ProblemDetail(
      type: 'about:blank',
      title: 'Erro',
      status: status,
      detail: 'detalhe',
      code: code,
    ));

void main() {
  group('TokenRefreshingClient', () {
    test('renova o token após 401 AUTHENTICATION_REQUIRED e repete a mesma requisição com o token novo', () async {
      var refreshCalls = 0;
      final refresher = SessionRefresher(refreshAccessToken: () async {
        refreshCalls++;
        return 'token-novo';
      });
      final inner = _RecordingClient((request) async {
        if (request.headers['authorization'] == 'Bearer token-velho') return _tokenRejected();
        return _json(200, {'ok': true});
      });
      final client = TokenRefreshingClient(inner: inner, sessionRefresher: refresher);

      final request = http.Request('PATCH', Uri.parse('https://api.rewit.test/api/v1/reviews/r1'))
        ..headers['Authorization'] = 'Bearer token-velho'
        ..headers['Content-Type'] = 'application/json; charset=UTF-8'
        ..followRedirects = false
        ..body = '{"experienceText":"Texto"}';

      final response = await http.Response.fromStream(await client.send(request));

      expect(response.statusCode, 200);
      expect(refreshCalls, 1);
      expect(inner.requests, hasLength(2));
      final replay = inner.requests[1] as http.Request;
      expect(replay.method, 'PATCH');
      expect(replay.url, request.url);
      expect(replay.headers['authorization'], 'Bearer token-novo');
      expect(replay.followRedirects, isFalse);
      expect(inner.bodies[1], '{"experienceText":"Texto"}');
    });

    test('vários 401 simultâneos compartilham uma única renovação do refresh token', () async {
      var refreshCalls = 0;
      final gate = Completer<void>();
      final refresher = SessionRefresher(refreshAccessToken: () async {
        refreshCalls++;
        await gate.future;
        return 'token-novo';
      });
      final inner = _RecordingClient((request) async =>
          request.headers['authorization'] == 'Bearer token-novo' ? _json(200, {'ok': true}) : _tokenRejected());
      final client = TokenRefreshingClient(inner: inner, sessionRefresher: refresher);

      http.Request build(String path) => http.Request('GET', Uri.parse('https://api.rewit.test$path'))
        ..headers['Authorization'] = 'Bearer token-velho';

      final pending = [client.send(build('/a')), client.send(build('/b')), client.send(build('/c'))];
      await Future<void>.delayed(Duration.zero);
      gate.complete();
      final responses = await Future.wait(pending);

      expect(responses.map((r) => r.statusCode), everyElement(200));
      expect(refreshCalls, 1, reason: 'reusar o mesmo refresh token em paralelo dispara a detecção de reuso do backend');
    });

    test('401 de negócio (INVALID_CREDENTIALS) não renova e devolve a resposta original', () async {
      var refreshCalls = 0;
      final refresher = SessionRefresher(refreshAccessToken: () async {
        refreshCalls++;
        return 'token-novo';
      });
      final inner = _RecordingClient((_) async => _json(401, {'status': 401, 'code': 'INVALID_CREDENTIALS'}));
      final client = TokenRefreshingClient(inner: inner, sessionRefresher: refresher);

      final request = http.Request('POST', Uri.parse('https://api.rewit.test/api/v1/me/password'))
        ..headers['Authorization'] = 'Bearer token';
      final response = await http.Response.fromStream(await client.send(request));

      expect(response.statusCode, 401);
      expect(jsonDecode(response.body)['code'], 'INVALID_CREDENTIALS');
      expect(refreshCalls, 0);
      expect(inner.requests, hasLength(1));
    });

    test('requisição sem Authorization (login, refresh, destino externo) nunca é renovada', () async {
      var refreshCalls = 0;
      final refresher = SessionRefresher(refreshAccessToken: () async {
        refreshCalls++;
        return 'token-novo';
      });
      final inner = _RecordingClient((_) async => _tokenRejected());
      final client = TokenRefreshingClient(inner: inner, sessionRefresher: refresher);

      final response = await client.send(http.Request('GET', Uri.parse('https://cdn.externo.test/img.png')));

      expect(response.statusCode, 401);
      expect(refreshCalls, 0);
      expect(inner.requests.single.headers.containsKey('authorization'), isFalse);
    });

    test('refresh recusado devolve o 401 original para o app encerrar a sessão', () async {
      final refresher = SessionRefresher(refreshAccessToken: () async => throw _apiError(401, 'INVALID_REFRESH_TOKEN'));
      final inner = _RecordingClient((_) async => _tokenRejected());
      final client = TokenRefreshingClient(inner: inner, sessionRefresher: refresher);

      final request = http.Request('GET', Uri.parse('https://api.rewit.test/api/v1/me'))
        ..headers['Authorization'] = 'Bearer token-velho';
      final response = await http.Response.fromStream(await client.send(request));

      expect(response.statusCode, 401);
      expect(jsonDecode(response.body)['code'], 'AUTHENTICATION_REQUIRED');
      expect(inner.requests, hasLength(1));
    });

    test('servidor de refresh indisponível vira erro de conexão, sem expirar a sessão', () async {
      final refresher = SessionRefresher(refreshAccessToken: () async => throw const NetworkException('sem rede'));
      final inner = _RecordingClient((_) async => _tokenRejected());
      final client = TokenRefreshingClient(inner: inner, sessionRefresher: refresher);

      final request = http.Request('GET', Uri.parse('https://api.rewit.test/api/v1/me'))
        ..headers['Authorization'] = 'Bearer token-velho';

      await expectLater(client.send(request), throwsA(isA<http.ClientException>()));
    });

    test('upload multipart não é repetido (corpo não reaproveitável)', () async {
      var refreshCalls = 0;
      final refresher = SessionRefresher(refreshAccessToken: () async {
        refreshCalls++;
        return 'token-novo';
      });
      final inner = _RecordingClient((_) async => _tokenRejected());
      final client = TokenRefreshingClient(inner: inner, sessionRefresher: refresher);

      final request = http.MultipartRequest('POST', Uri.parse('https://api.rewit.test/api/v1/reviews/r1/media'))
        ..headers['Authorization'] = 'Bearer token-velho';
      final response = await client.send(request);

      expect(response.statusCode, 401);
      expect(refreshCalls, 0);
      expect(inner.requests, hasLength(1));
    });
  });

  group('SessionRefresher', () {
    test('classifica recusa do servidor, indisponibilidade e sucesso', () async {
      expect((await SessionRefresher(refreshAccessToken: () async => 't').refresh()).outcome,
          SessionRefreshOutcome.refreshed);
      expect((await SessionRefresher(refreshAccessToken: () async => throw _apiError(401, 'REFRESH_TOKEN_EXPIRED'))
              .refresh())
          .outcome, SessionRefreshOutcome.rejected);
      expect((await SessionRefresher(refreshAccessToken: () async => throw _apiError(503, 'UNAVAILABLE')).refresh())
          .outcome, SessionRefreshOutcome.unavailable);
      expect((await SessionRefresher(refreshAccessToken: () async => throw _apiError(429, 'RATE_LIMIT_EXCEEDED'))
              .refresh())
          .outcome, SessionRefreshOutcome.unavailable);
      expect((await SessionRefresher(refreshAccessToken: () async => throw const NetworkException('x')).refresh())
          .outcome, SessionRefreshOutcome.unavailable);
    });
  });

  group('RewitHttpClient com TokenRefreshingClient', () {
    test('access token expirado é renovado sem disparar onSessionExpired', () async {
      final storage = InMemoryTokenStorage();
      await storage.saveTokens(accessToken: 'token-velho', refreshToken: 'refresh-1');
      final refresher = SessionRefresher(refreshAccessToken: () async {
        await storage.saveTokens(accessToken: 'token-novo', refreshToken: 'refresh-2');
        return 'token-novo';
      });
      final inner = _RecordingClient((request) async => request.headers['authorization'] == 'Bearer token-novo'
          ? _json(200, {'id': 'u1'})
          : _tokenRejected());
      var sessionExpired = false;
      final client = RewitHttpClient(
        baseUrl: 'https://api.rewit.test',
        client: TokenRefreshingClient(inner: inner, sessionRefresher: refresher),
        tokenStorage: storage,
        onSessionExpired: () => sessionExpired = true,
      );

      final response = await client.get('/api/v1/auth/me');

      expect(response.statusCode, 200);
      expect(sessionExpired, isFalse);
      expect(await storage.getAccessToken(), 'token-novo');
    });

    test('refresh recusado mantém o fluxo de sessão expirada (onSessionExpired + 401)', () async {
      final storage = InMemoryTokenStorage();
      await storage.saveTokens(accessToken: 'token-velho', refreshToken: 'refresh-1');
      final refresher = SessionRefresher(refreshAccessToken: () async => throw _apiError(401, 'REFRESH_TOKEN_REVOKED'));
      var sessionExpired = false;
      final client = RewitHttpClient(
        baseUrl: 'https://api.rewit.test',
        client: TokenRefreshingClient(inner: _RecordingClient((_) async => _tokenRejected()), sessionRefresher: refresher),
        tokenStorage: storage,
        onSessionExpired: () => sessionExpired = true,
      );

      await expectLater(client.get('/api/v1/auth/me'), throwsA(isA<ApiException>()));
      expect(sessionExpired, isTrue);
    });
  });
}
