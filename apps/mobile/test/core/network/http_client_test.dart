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

    test('deve invocar onSessionExpired quando requisição autenticada padrão recebe 401 mesmo com INVALID_CREDENTIALS', () async {
      bool sessionExpiredInvoked = false;

      final mockClient = MockBaseClient((request) async {
        final body = jsonEncode({
          'type': 'about:blank',
          'title': 'Não autorizado',
          'status': 401,
          'detail': 'Credenciais inválidas',
          'code': 'INVALID_CREDENTIALS',
        });
        return http.StreamedResponse(Stream.value(utf8.encode(body)), 401);
      });

      final client = RewitHttpClient(
        baseUrl: 'https://api.rewit.test',
        client: mockClient,
        tokenStorage: tokenStorage,
        onSessionExpired: () => sessionExpiredInvoked = true,
      );

      await expectLater(
        client.get('/me'),
        throwsA(isA<ApiException>().having((e) => e.isUnauthorized, 'isUnauthorized', isTrue)),
      );

      // Endpoint autenticado padrão NÃO deve suprimir onSessionExpired
      expect(sessionExpiredInvoked, isTrue);
    });

    test('NÃO deve invocar onSessionExpired quando treatInvalidCredentialsAsBusinessError=true e erro é 401 INVALID_CREDENTIALS', () async {
      bool sessionExpiredInvoked = false;

      final mockClient = MockBaseClient((request) async {
        final body = jsonEncode({
          'type': 'about:blank',
          'title': 'Não autorizado',
          'status': 401,
          'detail': 'Senha atual incorreta',
          'code': 'INVALID_CREDENTIALS',
        });
        return http.StreamedResponse(Stream.value(utf8.encode(body)), 401);
      });

      final client = RewitHttpClient(
        baseUrl: 'https://api.rewit.test',
        client: mockClient,
        tokenStorage: tokenStorage,
        onSessionExpired: () => sessionExpiredInvoked = true,
      );

      await expectLater(
        client.post(
          '/api/v1/me/password',
          body: {},
          requiresAuth: true,
          treatInvalidCredentialsAsBusinessError: true,
        ),
        throwsA(isA<ApiException>().having((e) => e.errorCode, 'errorCode', 'INVALID_CREDENTIALS')),
      );

      // onSessionExpired NÃO deve ser chamado pois é erro de negócio local do formulário
      expect(sessionExpiredInvoked, isFalse);
    });

    test('deve invocar onSessionExpired quando treatInvalidCredentialsAsBusinessError=true mas código não é INVALID_CREDENTIALS', () async {
      bool sessionExpiredInvoked = false;

      final mockClient = MockBaseClient((request) async {
        final body = jsonEncode({
          'type': 'about:blank',
          'title': 'Não autorizado',
          'status': 401,
          'detail': 'Token expirado',
          'code': 'UNAUTHORIZED',
        });
        return http.StreamedResponse(Stream.value(utf8.encode(body)), 401);
      });

      final client = RewitHttpClient(
        baseUrl: 'https://api.rewit.test',
        client: mockClient,
        tokenStorage: tokenStorage,
        onSessionExpired: () => sessionExpiredInvoked = true,
      );

      await expectLater(
        client.post(
          '/api/v1/me/password',
          body: {},
          requiresAuth: true,
          treatInvalidCredentialsAsBusinessError: true,
        ),
        throwsA(isA<ApiException>().having((e) => e.isUnauthorized, 'isUnauthorized', isTrue)),
      );

      // Como o código é UNAUTHORIZED (e não INVALID_CREDENTIALS), a sessão expirou e deve disparar callback
      expect(sessionExpiredInvoked, isTrue);
    });

    test('não deve invocar onSessionExpired em login quando 401 INVALID_CREDENTIALS ocorre (requiresAuth=false)', () async {
      bool sessionExpiredInvoked = false;

      final mockClient = MockBaseClient((request) async {
        final body = jsonEncode({
          'type': 'about:blank',
          'title': 'Credenciais inválidas',
          'status': 401,
          'detail': 'E-mail ou senha incorretos',
          'code': 'INVALID_CREDENTIALS',
        });
        return http.StreamedResponse(Stream.value(utf8.encode(body)), 401);
      });

      final client = RewitHttpClient(
        baseUrl: 'https://api.rewit.test',
        client: mockClient,
        tokenStorage: tokenStorage,
        onSessionExpired: () => sessionExpiredInvoked = true,
      );

      await expectLater(
        client.post('/api/v1/auth/login', body: {}, requiresAuth: false),
        throwsA(isA<ApiException>().having((e) => e.errorCode, 'errorCode', 'INVALID_CREDENTIALS')),
      );

      expect(sessionExpiredInvoked, isFalse);
    });

    test('não deve invocar onSessionExpired em falha 401 no refreshTokens (requiresAuth=false)', () async {
      bool sessionExpiredInvoked = false;

      final mockClient = MockBaseClient((request) async {
        final body = jsonEncode({
          'type': 'about:blank',
          'title': 'Sessão Expirada',
          'status': 401,
          'detail': 'Refresh token expirado ou revogado',
          'code': 'TOKEN_REVOKED',
        });
        return http.StreamedResponse(Stream.value(utf8.encode(body)), 401);
      });

      final client = RewitHttpClient(
        baseUrl: 'https://api.rewit.test',
        client: mockClient,
        tokenStorage: tokenStorage,
        onSessionExpired: () => sessionExpiredInvoked = true,
      );

      await expectLater(
        client.post('/api/v1/auth/refresh', body: {}, requiresAuth: false),
        throwsA(isA<ApiException>().having((e) => e.isUnauthorized, 'isUnauthorized', isTrue)),
      );

      expect(sessionExpiredInvoked, isFalse);
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

    group('Isolamento de autenticação por origem (Security Hotfix S1)', () {
      const testJwt = 'test-only-fake-jwt-token';

      setUp(() async {
        await tokenStorage.saveTokens(
          accessToken: testJwt,
          refreshToken: 'test-only-fake-refresh-token',
        );
      });

      test('1. requisição autenticada para a mesma origem (relativa e absoluta com porta padrão) inclui o token', () async {
        final mockClient = MockBaseClient((request) async {
          expect(request.headers['authorization'], 'Bearer $testJwt');
          return http.StreamedResponse(Stream.value(utf8.encode('{"ok":true}')), 200);
        });

        final client = RewitHttpClient(
          baseUrl: 'https://api.rewit.test',
          client: mockClient,
          tokenStorage: tokenStorage,
        );

        await client.get('/api/v1/me', requiresAuth: true);
        await client.get('https://api.rewit.test/api/v1/me', requiresAuth: true);
        await client.get('https://API.REWIT.TEST:443/api/v1/me', requiresAuth: true);

        expect(mockClient.recordedRequests, hasLength(3));
        for (final req in mockClient.recordedRequests) {
          expect(req.headers['authorization'], 'Bearer $testJwt');
        }
      });

      test('2. requisição para outro host não transmite o token', () async {
        final mockClient = MockBaseClient((request) async {
          expect(request.headers.containsKey('authorization'), isFalse);
          expect(request.headers.containsKey('Authorization'), isFalse);
          return http.StreamedResponse(Stream.value([1, 2, 3, 4]), 200);
        });

        final client = RewitHttpClient(
          baseUrl: 'https://api.rewit.test',
          client: mockClient,
          tokenStorage: tokenStorage,
        );

        final bytes = await client.getBytes('https://cdn.other-host.test/assets/photo.png');
        expect(bytes, equals([1, 2, 3, 4]));

        await client.get('https://other-host.test/public-info', requiresAuth: false);

        expect(mockClient.recordedRequests, hasLength(2));
        for (final req in mockClient.recordedRequests) {
          expect(req.headers.containsKey('authorization'), isFalse);
        }
      });

      test('3. requisição para host com nome semelhante mas origem diferente não transmite o token', () async {
        final mockClient = MockBaseClient((request) async {
          expect(request.headers.containsKey('authorization'), isFalse);
          return http.StreamedResponse(Stream.value([10, 20]), 200);
        });

        final client = RewitHttpClient(
          baseUrl: 'https://api.rewit.test',
          client: mockClient,
          tokenStorage: tokenStorage,
        );

        final lookalikeUrls = [
          'https://api.rewit.test.evil.test/image.png',
          'https://evil-api.rewit.test/image.png',
          'https://sub.api.rewit.test/image.png',
          'https://api.rewit.test@evil.test/image.png',
          '//api.rewit.test.evil.test/image.png',
        ];

        for (final url in lookalikeUrls) {
          expect(client.isSameOriginUrl(url), isFalse, reason: 'URL $url não deve ser considerada mesma origem');
          await expectLater(
            client.get(url, requiresAuth: true),
            throwsA(isA<NetworkException>()),
          );
        }

        // Nenhuma chamada autenticada para host semelhante chegou a ser emitida
        expect(mockClient.recordedRequests, isEmpty);

        // Mesmo em getBytes (fluxo público para origem externa), o token nunca é enviado
        await client.getBytes('https://api.rewit.test.evil.test/image.png');
        await client.getBytes('https://evil-api.rewit.test/image.png');
        expect(mockClient.recordedRequests, hasLength(2));
        for (final req in mockClient.recordedRequests) {
          expect(req.headers.containsKey('authorization'), isFalse);
        }
      });

      test('4. diferença de esquema ou porta aplica a regra de origem corretamente', () async {
        final mockClient = MockBaseClient((request) async {
          return http.StreamedResponse(Stream.value([42]), 200);
        });

        final client = RewitHttpClient(
          baseUrl: 'https://api.rewit.test',
          client: mockClient,
          tokenStorage: tokenStorage,
        );

        // Esquema diferente (http vs https) e porta diferente (8443 vs 443)
        expect(client.isSameOriginUrl('http://api.rewit.test/api/v1/me'), isFalse);
        expect(client.isSameOriginUrl('https://api.rewit.test:8443/api/v1/me'), isFalse);
        expect(client.isSameOriginUrl('https://api.rewit.test:443/api/v1/me'), isTrue);

        // Exigindo autenticação em esquema/porta divergentes: bloqueia antes do envio
        await expectLater(
          client.get('http://api.rewit.test/api/v1/me', requiresAuth: true),
          throwsA(isA<NetworkException>()),
        );
        await expectLater(
          client.get('https://api.rewit.test:8443/api/v1/me', requiresAuth: true),
          throwsA(isA<NetworkException>()),
        );
        expect(mockClient.recordedRequests, isEmpty);

        // Em getBytes sem forçar requiresAuth: baixa como público sem enviar token quando esquema ou porta divergem
        await client.getBytes('http://api.rewit.test/public.png');
        await client.getBytes('https://api.rewit.test:8443/public.png');
        // E envia token quando esquema e porta efetiva coincidem (:443)
        await client.getBytes('https://api.rewit.test:443/protected.png');

        expect(mockClient.recordedRequests, hasLength(3));
        expect(mockClient.recordedRequests[0].headers.containsKey('authorization'), isFalse);
        expect(mockClient.recordedRequests[1].headers.containsKey('authorization'), isFalse);
        expect(mockClient.recordedRequests[2].headers['authorization'], 'Bearer $testJwt');
      });

      test('5. carregamento público de uma imagem externa continua funcionando sem autenticação indevida', () async {
        final pngBytes = [137, 80, 78, 71, 13, 10, 26, 10];
        final mockClient = MockBaseClient((request) async {
          expect(request.url.toString(), 'https://images.external-catalog.test/products/cafe.png');
          expect(request.headers.containsKey('authorization'), isFalse);
          return http.StreamedResponse(
            Stream.value(pngBytes),
            200,
            headers: {'content-type': 'image/png'},
          );
        });

        final client = RewitHttpClient(
          baseUrl: 'https://api.rewit.test',
          client: mockClient,
          tokenStorage: tokenStorage,
        );

        final result = await client.getBytes(
          'https://images.external-catalog.test/products/cafe.png',
        );

        expect(result, equals(pngBytes));
        expect(mockClient.recordedRequests, hasLength(1));
        expect(mockClient.recordedRequests.single.headers.containsKey('authorization'), isFalse);
      });

      test('6. URL externa que exija autenticação falha de forma explícita e segura sem transmitir o token', () async {
        final mockClient = MockBaseClient((request) async {
          fail('Nenhuma requisição de rede deve ser enviada quando origem externa exige autenticação');
        });

        final client = RewitHttpClient(
          baseUrl: 'https://api.rewit.test',
          client: mockClient,
          tokenStorage: tokenStorage,
        );

        await expectLater(
          client.get('https://external.test/secret', requiresAuth: true),
          throwsA(isA<NetworkException>()),
        );
        await expectLater(
          client.post('https://external.test/secret', body: {'a': 1}, requiresAuth: true),
          throwsA(isA<NetworkException>()),
        );
        await expectLater(
          client.put('https://external.test/secret', body: {'a': 1}, requiresAuth: true),
          throwsA(isA<NetworkException>()),
        );
        await expectLater(
          client.patch('https://external.test/secret', body: {'a': 1}, requiresAuth: true),
          throwsA(isA<NetworkException>()),
        );
        await expectLater(
          client.delete('https://external.test/secret', requiresAuth: true),
          throwsA(isA<NetworkException>()),
        );
        await expectLater(
          client.postMultipart(
            'https://external.test/upload',
            fieldName: 'file',
            fileBytes: [1, 2, 3],
            filename: 'a.png',
            requiresAuth: true,
          ),
          throwsA(isA<NetworkException>()),
        );
        await expectLater(
          client.getBytes('https://external.test/private.png', requiresAuth: true),
          throwsA(isA<NetworkException>()),
        );
        await expectLater(
          client.get(
            'https://external.test/custom-header',
            requiresAuth: false,
            headers: {'Authorization': 'Bearer $testJwt'},
          ),
          throwsA(isA<NetworkException>()),
        );

        expect(mockClient.recordedRequests, isEmpty);
      });

      test('7. redirecionamento para origem externa não encaminha o token ao destino externo', () async {
        final imageBytes = [137, 80, 78, 71];
        final mockClient = MockBaseClient((request) async {
          expect(request.followRedirects, isFalse);

          if (request.url.toString() == 'https://api.rewit.test/api/v1/reviews/rev-1/media/med-1') {
            expect(request.headers['authorization'], 'Bearer $testJwt');
            return http.StreamedResponse(
              const Stream.empty(),
              302,
              headers: {'location': 'https://cdn.external-storage.test/bucket/med-1.png'},
            );
          }

          if (request.url.toString() == 'https://cdn.external-storage.test/bucket/med-1.png') {
            expect(request.headers.containsKey('authorization'), isFalse);
            expect(request.headers.containsKey('Authorization'), isFalse);
            return http.StreamedResponse(
              Stream.value(imageBytes),
              200,
              headers: {'content-type': 'image/png'},
            );
          }

          fail('URL inesperada: ${request.url}');
        });

        final client = RewitHttpClient(
          baseUrl: 'https://api.rewit.test',
          client: mockClient,
          tokenStorage: tokenStorage,
        );

        final result = await client.getBytes('/api/v1/reviews/rev-1/media/med-1');

        expect(result, equals(imageBytes));
        expect(mockClient.recordedRequests, hasLength(2));
        expect(
          mockClient.recordedRequests[0].headers['authorization'],
          'Bearer $testJwt',
        );
        expect(
          mockClient.recordedRequests[1].headers.containsKey('authorization'),
          isFalse,
        );
      });

      test('redirecionamento dentro da mesma origem preserva o token, mas bloqueia mutação não-GET redirecionada 307 para origem externa', () async {
        final mockClient = MockBaseClient((request) async {
          if (request.url.toString() == 'https://api.rewit.test/api/v1/old-endpoint') {
            return http.StreamedResponse(
              const Stream.empty(),
              302,
              headers: {'location': '/api/v1/new-endpoint'},
            );
          }
          if (request.url.toString() == 'https://api.rewit.test/api/v1/new-endpoint') {
            expect(request.headers['authorization'], 'Bearer $testJwt');
            return http.StreamedResponse(
              Stream.value(utf8.encode('{"redirected":true}')),
              200,
            );
          }
          if (request.url.toString() == 'https://api.rewit.test/api/v1/mutate') {
            return http.StreamedResponse(
              const Stream.empty(),
              307,
              headers: {'location': 'https://external.test/mutate'},
            );
          }
          fail('URL inesperada: ${request.url}');
        });

        final client = RewitHttpClient(
          baseUrl: 'https://api.rewit.test',
          client: mockClient,
          tokenStorage: tokenStorage,
        );

        final getResponse = await client.get('/api/v1/old-endpoint');
        expect(getResponse.statusCode, 200);
        expect(mockClient.recordedRequests, hasLength(2));
        expect(mockClient.recordedRequests[1].headers['authorization'], 'Bearer $testJwt');

        await expectLater(
          client.post('/api/v1/mutate', body: {'x': 1}),
          throwsA(isA<NetworkException>()),
        );
        // A requisição POST não foi reenviada ao host externo após o 307
        expect(mockClient.recordedRequests, hasLength(3));
      });
    });
  });
}
