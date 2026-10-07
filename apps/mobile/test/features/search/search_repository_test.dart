import 'dart:convert';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/core/network/http_client.dart';
import 'package:rewit_mobile/features/search/data/models/search_dtos.dart';
import 'package:rewit_mobile/features/search/data/repositories/search_repository_impl.dart';
import 'package:rewit_mobile/features/search/domain/entities/search_entities.dart';

class MockHttpClient extends http.BaseClient {
  http.Request? capturedRequest;
  http.Response? responseToReturn;
  Exception? exceptionToThrow;

  @override
  Future<http.StreamedResponse> send(http.BaseRequest request) async {
    capturedRequest = request as http.Request;
    if (exceptionToThrow != null) {
      throw exceptionToThrow!;
    }
    final resp = responseToReturn ??
        http.Response(
          jsonEncode({
            'content': [],
            'pageNumber': 0,
            'pageSize': 20,
            'totalElements': 0,
            'totalPages': 0,
            'isLast': true,
          }),
          200,
          headers: {'content-type': 'application/json; charset=utf-8'},
        );
    return http.StreamedResponse(
      Stream.value(resp.bodyBytes),
      resp.statusCode,
      headers: resp.headers,
    );
  }
}

void main() {
  group('SearchRepositoryImpl & DTO Tests', () {
    late MockHttpClient innerClient;
    late RewitHttpClient httpClient;
    late SearchRepositoryImpl repository;

    setUp(() {
      innerClient = MockHttpClient();
      httpClient = RewitHttpClient(
        baseUrl: 'https://api.rewit.test',
        client: innerClient,
      );
      repository = SearchRepositoryImpl(httpClient: httpClient);
    });

    test('mapeia DTOs de Search V1 corretamente com alvos PLACE e PRODUCT', () {
      final json = {
        'content': [
          {
            'id': '11111111-1111-1111-1111-111111111111',
            'name': 'Cafeteria Central',
            'slug': 'cafeteria-central',
            'category': 'Cafeterias',
            'targetType': 'PLACE',
            'status': 'ACTIVE',
          },
          {
            'id': '22222222-2222-2222-2222-222222222222',
            'name': 'Café Espresso Gourmet',
            'slug': 'cafe-espresso-gourmet',
            'category': 'Bebidas',
            'targetType': 'PRODUCT',
            'status': 'ACTIVE',
          },
        ],
        'pageNumber': 0,
        'pageSize': 20,
        'totalElements': 2,
        'totalPages': 1,
        'isLast': true,
      };

      final dto = SearchPagedResponseDto.fromJson(json);
      final entity = dto.toEntity();

      expect(entity.items.length, 2);
      expect(entity.pageNumber, 0);
      expect(entity.pageSize, 20);
      expect(entity.totalElements, 2);
      expect(entity.totalPages, 1);
      expect(entity.isLast, isTrue);

      final place = entity.items[0];
      expect(place.id, '11111111-1111-1111-1111-111111111111');
      expect(place.name, 'Cafeteria Central');
      expect(place.targetType, TargetType.place);
      expect(place.isPlace, isTrue);
      expect(place.isProduct, isFalse);
      expect(place.category, 'Cafeterias');
      expect(place.targetType.displayName, 'Local');

      final product = entity.items[1];
      expect(product.id, '22222222-2222-2222-2222-222222222222');
      expect(product.name, 'Café Espresso Gourmet');
      expect(product.targetType, TargetType.product);
      expect(product.isPlace, isFalse);
      expect(product.isProduct, isTrue);
      expect(product.category, 'Bebidas');
      expect(product.targetType.displayName, 'Produto');
    });

    test('executa GET /api/v1/search com parâmetros q, page e size', () async {
      innerClient.responseToReturn = http.Response(
        jsonEncode({
          'content': [
            {
              'id': '33333333-3333-3333-3333-333333333333',
              'name': 'Pizzaria Napolitana',
              'slug': 'pizzaria-napolitana',
              'category': 'Restaurantes',
              'targetType': 'PLACE',
              'status': 'ACTIVE',
            }
          ],
          'pageNumber': 1,
          'pageSize': 10,
          'totalElements': 25,
          'totalPages': 3,
          'isLast': false,
        }),
        200,
        headers: {'content-type': 'application/json'},
      );

      final result = await repository.search(query: 'pizza', page: 1, size: 10);

      expect(innerClient.capturedRequest?.url.path, '/api/v1/search');
      expect(innerClient.capturedRequest?.url.queryParameters['q'], 'pizza');
      expect(innerClient.capturedRequest?.url.queryParameters['page'], '1');
      expect(innerClient.capturedRequest?.url.queryParameters['size'], '10');

      expect(result.items.length, 1);
      expect(result.items.first.name, 'Pizzaria Napolitana');
      expect(result.pageNumber, 1);
      expect(result.pageSize, 10);
      expect(result.totalElements, 25);
      expect(result.totalPages, 3);
      expect(result.isLast, isFalse);
    });

    test('lança ApiException ao receber erro 400 RFC 7807 (INVALID_SEARCH_QUERY)', () async {
      innerClient.responseToReturn = http.Response(
        jsonEncode({
          'type': 'https://api.rewit.app/errors/invalid-search-query',
          'title': 'Bad Request',
          'status': 400,
          'detail': 'O termo de busca não pode ser vazio ou conter apenas espaços.',
          'code': 'INVALID_SEARCH_QUERY',
        }),
        400,
        headers: {'content-type': 'application/problem+json'},
      );

      expect(
        () => repository.search(query: '   '),
        throwsA(
          isA<ApiException>()
              .having((e) => e.statusCode, 'statusCode', 400)
              .having((e) => e.errorCode, 'errorCode', 'INVALID_SEARCH_QUERY')
              .having((e) => e.detail, 'detail', contains('não pode ser vazio')),
        ),
      );
    });

    test('lança ApiException ao receber erro 429 RFC 7807 com Retry-After', () async {
      innerClient.responseToReturn = http.Response(
        jsonEncode({
          'type': 'https://api.rewit.app/errors/rate-limit',
          'title': 'Too Many Requests',
          'status': 429,
          'detail': 'Limite de requisições excedido.',
          'code': 'RATE_LIMIT_EXCEEDED',
        }),
        429,
        headers: {
          'content-type': 'application/problem+json',
          'Retry-After': '30',
        },
      );

      expect(
        () => repository.search(query: 'burger'),
        throwsA(
          isA<ApiException>()
              .having((e) => e.statusCode, 'statusCode', 429)
              .having((e) => e.retryAfterSeconds, 'retryAfterSeconds', 30)
              .having((e) => e.isRateLimited, 'isRateLimited', isTrue),
        ),
      );
    });

    test('lança NetworkException em falha de conexão HTTP', () async {
      innerClient.exceptionToThrow = http.ClientException('Falha de DNS');

      expect(
        () => repository.search(query: 'restaurante'),
        throwsA(isA<NetworkException>()),
      );
    });
  });
}
