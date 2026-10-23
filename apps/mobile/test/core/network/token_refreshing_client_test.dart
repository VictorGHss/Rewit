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

  group('TokenRefreshingClient: limite do corpo 401 e repetição única', () {
    http.Request authorized(String path) =>
        http.Request('GET', Uri.parse('https://api.rewit.test$path'))..headers['Authorization'] = 'Bearer token-velho';

    test('corpo 401 dentro de 16 KiB com AUTHENTICATION_REQUIRED ainda renova', () async {
      var refreshCalls = 0;
      final refresher = SessionRefresher(refreshAccessToken: () async {
        refreshCalls++;
        return 'token-novo';
      });
      // ProblemDetail válido acolchoado até perto do limite
      final padded = jsonEncode({
        'status': 401,
        'code': 'AUTHENTICATION_REQUIRED',
        'detail': 'x' * (TokenRefreshingClient.maxUnauthorizedBodyBytes - 200),
      });
      expect(utf8.encode(padded).length, lessThanOrEqualTo(TokenRefreshingClient.maxUnauthorizedBodyBytes));
      final inner = _RecordingClient((request) async => request.headers['authorization'] == 'Bearer token-novo'
          ? _json(200, {'ok': true})
          : http.StreamedResponse(Stream.value(utf8.encode(padded)), 401));
      final client = TokenRefreshingClient(inner: inner, sessionRefresher: refresher);

      final response = await client.send(authorized('/a'));

      expect(response.statusCode, 200);
      expect(refreshCalls, 1);
    });

    test('corpo 401 acima de 16 KiB (sem Content-Length) não é lido inteiro, cancela o stream e não renova', () async {
      var refreshCalls = 0;
      final refresher = SessionRefresher(refreshAccessToken: () async {
        refreshCalls++;
        return 'token-novo';
      });
      var cancelled = false;
      var emittedBytes = 0;
      final controller = StreamController<List<int>>(onCancel: () => cancelled = true);
      final inner = _RecordingClient((_) async {
        // Começa como a recusa de token, mas o corpo não termina: só o limite impede a leitura sem fim
        final start = utf8.encode('{"status":401,"code":"AUTHENTICATION_REQUIRED","detail":"');
        controller.add(start);
        emittedBytes += start.length;
        for (var i = 0; i < 8; i++) {
          final chunk = List<int>.filled(4096, 0x61);
          controller.add(chunk);
          emittedBytes += chunk.length;
        }
        return http.StreamedResponse(controller.stream, 401);
      });
      final client = TokenRefreshingClient(inner: inner, sessionRefresher: refresher);

      final response = await client.send(authorized('/grande'));
      final body = await response.stream.toBytes();

      expect(response.statusCode, 401);
      expect(refreshCalls, 0, reason: 'corpo truncado não comprova a recusa de token');
      expect(body.length, TokenRefreshingClient.maxUnauthorizedBodyBytes);
      expect(emittedBytes, greaterThan(TokenRefreshingClient.maxUnauthorizedBodyBytes));
      expect(cancelled, isTrue, reason: 'a assinatura precisa ser cancelada para liberar a conexão');
      expect(response.headers.containsKey('content-length'), isFalse);
      await controller.close();
    });

    test('corpo 401 malformado não renova e é devolvido como está', () async {
      var refreshCalls = 0;
      final refresher = SessionRefresher(refreshAccessToken: () async {
        refreshCalls++;
        return 'token-novo';
      });
      final inner =
          _RecordingClient((_) async => http.StreamedResponse(Stream.value(utf8.encode('<html>401</html>')), 401));
      final client = TokenRefreshingClient(inner: inner, sessionRefresher: refresher);

      final response = await http.Response.fromStream(await client.send(authorized('/a')));

      expect(response.statusCode, 401);
      expect(response.body, '<html>401</html>');
      expect(refreshCalls, 0);
    });

    test('falha de rede durante a leitura do corpo 401 propaga o erro sem renovar', () async {
      var refreshCalls = 0;
      final refresher = SessionRefresher(refreshAccessToken: () async {
        refreshCalls++;
        return 'token-novo';
      });
      final inner = _RecordingClient((request) async => http.StreamedResponse(
            Stream<List<int>>.error(http.ClientException('conexão perdida', request.url)),
            401,
          ));
      final client = TokenRefreshingClient(inner: inner, sessionRefresher: refresher);

      await expectLater(client.send(authorized('/a')), throwsA(isA<http.ClientException>()));
      expect(refreshCalls, 0);
    });

    test('novo 401 na repetição é devolvido sem segunda renovação nem segunda repetição', () async {
      var refreshCalls = 0;
      final refresher = SessionRefresher(refreshAccessToken: () async {
        refreshCalls++;
        return 'token-novo';
      });
      final inner = _RecordingClient((_) async => _tokenRejected());
      final client = TokenRefreshingClient(inner: inner, sessionRefresher: refresher);

      final response = await client.send(authorized('/a'));

      expect(response.statusCode, 401);
      expect(refreshCalls, 1);
      expect(inner.requests, hasLength(2));
    });

    test('a repetição mantém o abortTrigger da requisição original (cancelamento do hotfix S1.2)', () async {
      final abort = Completer<void>();
      final refresher = SessionRefresher(refreshAccessToken: () async => 'token-novo');
      final inner = _RecordingClient((request) async =>
          request.headers['authorization'] == 'Bearer token-novo' ? _json(200, {'ok': true}) : _tokenRejected());
      final client = TokenRefreshingClient(inner: inner, sessionRefresher: refresher);

      final request = http.AbortableRequest('GET', Uri.parse('https://api.rewit.test/a'), abortTrigger: abort.future)
        ..headers['Authorization'] = 'Bearer token-velho'
        ..followRedirects = false;
      await client.send(request);

      final replay = inner.requests[1];
      expect(replay, isA<http.Abortable>());
      expect((replay as http.Abortable).abortTrigger, same(abort.future));
      expect(replay.followRedirects, isFalse);
    });
  });

  group('TokenRefreshingClient com o cliente endurecido (S1.2)', () {
    test('prazo esgotado durante a renovação vira timeout, sem expirar a sessão', () async {
      final storage = InMemoryTokenStorage();
      await storage.saveTokens(accessToken: 'token-velho', refreshToken: 'refresh-1');
      final refresher = SessionRefresher(refreshAccessToken: () async {
        await Future<void>.delayed(const Duration(milliseconds: 300));
        await storage.saveTokens(accessToken: 'token-novo', refreshToken: 'refresh-2');
        return 'token-novo';
      });
      var sessionExpired = false;
      final client = RewitHttpClient(
        baseUrl: 'https://api.rewit.test',
        timeout: const Duration(milliseconds: 100),
        client: TokenRefreshingClient(
          inner: _RecordingClient((request) async => request.headers['authorization'] == 'Bearer token-novo'
              ? _json(200, {'ok': true})
              : _tokenRejected()),
          sessionRefresher: refresher,
        ),
        tokenStorage: storage,
        onSessionExpired: () => sessionExpired = true,
      );

      await expectLater(client.get('/api/v1/auth/me'), throwsA(isA<NetworkException>()));
      expect(sessionExpired, isFalse);

      // A renovação em curso termina por conta própria e deixa os tokens novos para a próxima chamada
      await Future<void>.delayed(const Duration(milliseconds: 400));
      expect(await storage.getAccessToken(), 'token-novo');
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
