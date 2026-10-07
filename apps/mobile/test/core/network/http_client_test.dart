import 'dart:async';
import 'dart:convert';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/core/network/http_client.dart';
import 'package:rewit_mobile/core/storage/token_storage.dart';

class MockBaseClient extends http.BaseClient {
  final Future<http.StreamedResponse> Function(http.BaseRequest request) _handler;
  final List<http.BaseRequest> recordedRequests = [];

  MockBaseClient(this._handler);

  @override
  Future<http.StreamedResponse> send(http.BaseRequest request) async {
    recordedRequests.add(request);
    return _handler(request);
  }
}

void main() {
  group('RewitHttpClient', () {
    late InMemoryTokenStorage tokenStorage;

    setUp(() {
      tokenStorage = InMemoryTokenStorage();
    });

    test('deve enviar cabeçalhos padrão Accept e Content-Type', () async {
      final mockClient = MockBaseClient((request) async {
        expect(request.headers['accept'], 'application/json');
        expect(request.headers['content-type'], contains('application/json'));
        return http.StreamedResponse(
          Stream.value(utf8.encode('{"ok": true}')),
          200,
        );
      });

      final client = RewitHttpClient(
        baseUrl: 'https://api.rewit.test',
        client: mockClient,
        tokenStorage: tokenStorage,
      );

      final response = await client.post('/test', body: {'key': 'value'});
      expect(response.statusCode, 200);
      expect(response.body, '{"ok": true}');
    });

    test('deve injetar Authorization Bearer quando token existe e requiresAuth=true', () async {
      await tokenStorage.saveTokens(
        accessToken: 'valid-jwt-token-xyz',
        refreshToken: 'valid-refresh-token',
      );

      final mockClient = MockBaseClient((request) async {
        expect(request.headers['authorization'], 'Bearer valid-jwt-token-xyz');
        return http.StreamedResponse(Stream.value(utf8.encode('{}')), 200);
      });

      final client = RewitHttpClient(
        baseUrl: 'https://api.rewit.test',
        client: mockClient,
        tokenStorage: tokenStorage,
      );

      await client.get('/protected-resource', requiresAuth: true);
    });

    test('não deve enviar Authorization quando requiresAuth=false', () async {
      await tokenStorage.saveTokens(
        accessToken: 'valid-jwt-token-xyz',
        refreshToken: 'valid-refresh-token',
      );

      final mockClient = MockBaseClient((request) async {
        expect(request.headers.containsKey('authorization'), isFalse);
        return http.StreamedResponse(Stream.value(utf8.encode('{}')), 200);
      });

      final client = RewitHttpClient(
        baseUrl: 'https://api.rewit.test',
        client: mockClient,
        tokenStorage: tokenStorage,
      );

      await client.post('/api/v1/auth/login', body: {}, requiresAuth: false);
    });

    test('deve invocar onSessionExpired e lançar ApiException em HTTP 401', () async {
      bool sessionExpiredInvoked = false;

      final mockClient = MockBaseClient((request) async {
        final body = jsonEncode({
          'type': 'about:blank',
          'title': 'Não autorizado',
          'status': 401,
          'detail': 'Token revogado',
          'code': 'UNAUTHORIZED',
        });
        return http.StreamedResponse(Stream.value(utf8.encode(body)), 401);
      });

      final client = RewitHttpClient(
        baseUrl: 'https://api.rewit.test',
        client: mockClient,
        tokenStorage: tokenStorage,
        onSessionExpired: () {
          sessionExpiredInvoked = true;
        },
      );

      await expectLater(
        client.get('/me'),
        throwsA(isA<ApiException>().having((e) => e.isUnauthorized, 'isUnauthorized', isTrue)),
      );

      expect(sessionExpiredInvoked, isTrue);
    });

    test('deve extrair cabeçalho Retry-After em HTTP 429 Too Many Requests', () async {
      final mockClient = MockBaseClient((request) async {
        final body = jsonEncode({
          'type': 'about:blank',
          'title': 'Rate Limit Exceeded',
          'status': 429,
          'detail': 'Muitas requisições.',
          'code': 'RATE_LIMIT_EXCEEDED',
        });
        return http.StreamedResponse(
          Stream.value(utf8.encode(body)),
          429,
          headers: {'retry-after': '45'},
        );
      });

      final client = RewitHttpClient(
        baseUrl: 'https://api.rewit.test',
        client: mockClient,
        tokenStorage: tokenStorage,
      );

      try {
        await client.get('/rate-limited');
        fail('Deveria ter lançado ApiException');
      } on ApiException catch (e) {
        expect(e.isRateLimited, isTrue);
        expect(e.retryAfterSeconds, 45);
        expect(e.errorCode, 'RATE_LIMIT_EXCEEDED');
      }
    });

    test('deve mapear TimeoutException para NetworkException', () async {
      final mockClient = MockBaseClient((request) async {
        await Future.delayed(const Duration(milliseconds: 100));
        return http.StreamedResponse(Stream.value([]), 200);
      });

      final client = RewitHttpClient(
        baseUrl: 'https://api.rewit.test',
        timeout: const Duration(milliseconds: 20),
        client: mockClient,
      );

      await expectLater(
        client.get('/slow-endpoint'),
        throwsA(isA<NetworkException>().having((e) => e.message, 'message', contains('timeout'))),
      );
    });

    test('deve mapear ClientException para NetworkException', () async {
      final mockClient = MockBaseClient((request) async {
        throw http.ClientException('Failed to connect to host');
      });

      final client = RewitHttpClient(
        baseUrl: 'https://api.rewit.test',
        client: mockClient,
      );

      await expectLater(
        client.get('/disconnected'),
        throwsA(isA<NetworkException>()),
      );
    });
  });
}
