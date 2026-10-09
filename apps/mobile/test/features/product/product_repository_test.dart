import 'dart:convert';
import 'dart:typed_data';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/core/network/http_client.dart';
import 'package:rewit_mobile/features/product/data/models/product_dtos.dart';
import 'package:rewit_mobile/features/product/data/repositories/product_repository_impl.dart';

class _MockHttpBaseClient extends http.BaseClient {
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
          jsonEncode({}),
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
  group('Product DTOs & Models Tests', () {
    test('ProductDto mapeia campos completos e gera displayName adequadamente', () {
      final json = {
        'id': 'prod-uuid-1',
        'name': 'Café Especial Torrado',
        'brand': 'Orfeu',
        'model': 'Grãos 250g',
        'description': 'Café arábica de torra média.',
        'category': 'BEBIDA',
        'imageUrl': 'https://rewit.local/images/cafe.png',
        'status': 'ACTIVE',
      };

      final dto = ProductDto.fromJson(json);
      final entity = dto.toEntity();

      expect(entity.id, 'prod-uuid-1');
      expect(entity.name, 'Café Especial Torrado');
      expect(entity.brand, 'Orfeu');
      expect(entity.model, 'Grãos 250g');
      expect(entity.description, 'Café arábica de torra média.');
      expect(entity.category, 'BEBIDA');
      expect(entity.imageUrl, 'https://rewit.local/images/cafe.png');
      expect(entity.status, 'ACTIVE');
      expect(entity.hasImage, isTrue);
      expect(entity.displayName, 'Orfeu Café Especial Torrado');
    });

    test('ProductIdentifierDto e ProductIdentifiersResponseDto filtram tipos não públicos', () {
      final json = {
        'identifiers': [
          {'identifierType': 'EAN', 'identifierValue': '7891234567890'},
          {'identifierType': 'upc', 'identifierValue': '012345678905'},
          {'identifierType': 'INTERNAL_SKU', 'identifierValue': 'SKU-999'},
          {'identifierType': 'ISBN', 'identifierValue': '978-3-16-148410-0'},
          {'identifierType': 'UNKNOWN', 'identifierValue': 'XYZ'},
        ],
      };

      final responseDto = ProductIdentifiersResponseDto.fromJson(json);
      final publicEntities = responseDto.toEntityList();

      expect(publicEntities.length, 3);
      expect(publicEntities.map((e) => e.identifierType), containsAll(['EAN', 'upc', 'ISBN']));
      expect(publicEntities.any((e) => e.identifierType == 'INTERNAL_SKU'), isFalse);
      expect(publicEntities.any((e) => e.identifierType == 'UNKNOWN'), isFalse);
    });

    test('ProductsInPlacePageDto deserializa envelope paginado de produtos', () {
      final json = {
        'content': [
          {
            'id': 'prod-1',
            'name': 'Pão de Queijo',
            'brand': 'Dona Benta',
            'category': 'ALIMENTACAO',
            'status': 'ACTIVE',
          },
          {
            'id': 'prod-2',
            'name': 'Suco de Laranja',
            'brand': 'Natural',
            'category': 'BEBIDA',
            'status': 'ACTIVE',
          },
        ],
        'pageNumber': 0,
        'pageSize': 20,
        'totalElements': 2,
        'totalPages': 1,
        'isLast': true,
      };

      final page = ProductsInPlacePageDto.fromJson(json).toEntity();

      expect(page.products.length, 2);
      expect(page.products.first.name, 'Pão de Queijo');
      expect(page.pageNumber, 0);
      expect(page.totalElements, 2);
      expect(page.isLast, isTrue);
      expect(page.isNotEmpty, isTrue);
    });
  });

  group('ProductRepositoryImpl Integration Tests', () {
    late _MockHttpBaseClient mockBaseClient;
    late RewitHttpClient httpClient;
    late ProductRepositoryImpl repository;

    setUp(() {
      mockBaseClient = _MockHttpBaseClient();
      httpClient = RewitHttpClient(
        baseUrl: 'https://api.rewit.test',
        client: mockBaseClient,
      );
      repository = ProductRepositoryImpl(httpClient: httpClient);
    });

    test('getProductById requisita endpoint correto e retorna ProductDetail', () async {
      mockBaseClient.responseToReturn = http.Response(
        jsonEncode({
          'id': 'prod-100',
          'name': 'Chocolate Amargo 70%',
          'brand': 'Lindt',
          'category': 'DOCES',
          'status': 'ACTIVE',
        }),
        200,
        headers: {'content-type': 'application/json'},
      );

      final product = await repository.getProductById('prod-100');

      expect(mockBaseClient.capturedRequest?.url.path, '/api/v1/products/prod-100');
      expect(mockBaseClient.capturedRequest?.method, 'GET');
      expect(product.id, 'prod-100');
      expect(product.name, 'Chocolate Amargo 70%');
      expect(product.brand, 'Lindt');
    });

    test('getProductById propaga ApiException quando produto retorna 404', () async {
      mockBaseClient.responseToReturn = http.Response(
        jsonEncode({
          'type': 'https://api.rewit.com/errors/not-found',
          'title': 'Produto não encontrado',
          'status': 404,
          'detail': 'Produto indisponível ou inexistente.',
          'code': 'PRODUCT_NOT_FOUND',
        }),
        404,
        headers: {'content-type': 'application/problem+json'},
      );

      expect(
        () => repository.getProductById('prod-nao-existe'),
        throwsA(isA<ApiException>().having((e) => e.statusCode, 'statusCode', 404)),
      );
    });

    test('getProductIdentifiers requisita /identifiers e filtra tipos públicos', () async {
      mockBaseClient.responseToReturn = http.Response(
        jsonEncode({
          'identifiers': [
            {'identifierType': 'EAN', 'identifierValue': '7890001112223'},
            {'identifierType': 'SECRET_ID', 'identifierValue': 'SEC123'},
          ],
        }),
        200,
        headers: {'content-type': 'application/json'},
      );

      final identifiers = await repository.getProductIdentifiers('prod-100');

      expect(mockBaseClient.capturedRequest?.url.path, '/api/v1/products/prod-100/identifiers');
      expect(identifiers.length, 1);
      expect(identifiers.first.identifierType, 'EAN');
      expect(identifiers.first.identifierValue, '7890001112223');
    });

    test('getProductByIdentifier requisita lookup estruturado com tipo e valor limpos', () async {
      mockBaseClient.responseToReturn = http.Response(
        jsonEncode({
          'id': 'prod-100',
          'name': 'Chocolate Amargo 70%',
          'brand': 'Lindt',
          'category': 'DOCES',
          'status': 'ACTIVE',
        }),
        200,
        headers: {'content-type': 'application/json'},
      );

      final product = await repository.getProductByIdentifier(
        type: 'ean',
        value: ' 7890001112223 ',
      );

      expect(
        mockBaseClient.capturedRequest?.url.path,
        '/api/v1/products/identifiers/ean/7890001112223',
      );
      expect(product.id, 'prod-100');
    });

    test('getProductByIdentifier rejeita tipo fora da whitelist e tentativas de path traversal', () async {
      expect(
        () => repository.getProductByIdentifier(type: 'UNKNOWN_TYPE', value: '12345'),
        throwsA(isA<ArgumentError>()),
      );

      expect(
        () => repository.getProductByIdentifier(type: '..', value: '12345'),
        throwsA(isA<ArgumentError>()),
      );

      expect(
        () => repository.getProductByIdentifier(type: 'EAN', value: '../traversal'),
        throwsA(isA<ArgumentError>()),
      );

      expect(
        () => repository.getProductByIdentifier(type: 'EAN', value: '123/456'),
        throwsA(isA<ArgumentError>()),
      );
    });

    test('getProductByIdentifier aceita tipos validos GTIN, UPC e ISBN', () async {
      mockBaseClient.responseToReturn = http.Response(
        jsonEncode({
          'id': 'prod-200',
          'name': 'Livro Arquitetura Limpa',
          'brand': 'Alta Books',
          'category': 'LIVROS',
          'status': 'ACTIVE',
        }),
        200,
        headers: {'content-type': 'application/json'},
      );

      final productGtin = await repository.getProductByIdentifier(
        type: 'GTIN',
        value: '17891234567897',
      );
      expect(mockBaseClient.capturedRequest?.url.path, '/api/v1/products/identifiers/GTIN/17891234567897');
      expect(productGtin.id, 'prod-200');

      final productIsbn = await repository.getProductByIdentifier(
        type: 'ISBN',
        value: '9788550804606',
      );
      expect(mockBaseClient.capturedRequest?.url.path, '/api/v1/products/identifiers/ISBN/9788550804606');
      expect(productIsbn.id, 'prod-200');
    });

    test('getProductsInPlace requisita produtos do local com parâmetros de paginação', () async {
      mockBaseClient.responseToReturn = http.Response(
        jsonEncode({
          'content': [
            {
              'id': 'p-1',
              'name': 'Pão Francês',
              'brand': '',
              'category': 'PADARIA',
              'status': 'ACTIVE',
            }
          ],
          'pageNumber': 1,
          'pageSize': 10,
          'totalElements': 15,
          'totalPages': 2,
          'isLast': false,
        }),
        200,
        headers: {'content-type': 'application/json'},
      );

      final page = await repository.getProductsInPlace('place-abc', page: 1, size: 10);

      expect(mockBaseClient.capturedRequest?.url.path, '/api/v1/places/place-abc/products');
      expect(mockBaseClient.capturedRequest?.url.queryParameters['page'], '1');
      expect(mockBaseClient.capturedRequest?.url.queryParameters['size'], '10');
      expect(page.products.length, 1);
      expect(page.totalElements, 15);
      expect(page.isLast, isFalse);
    });

    test('getProductStats requisita /targets/{id}/stats e faz parsing', () async {
      mockBaseClient.responseToReturn = http.Response(
        jsonEncode({
          'targetId': 'prod-100',
          'averageRating': 4.9,
          'reviewsCount': 38,
          'lastCalculatedAt': '2026-10-08T01:00:00Z',
        }),
        200,
        headers: {'content-type': 'application/json'},
      );

      final stats = await repository.getProductStats('prod-100');

      expect(mockBaseClient.capturedRequest?.url.path, '/api/v1/targets/prod-100/stats');
      expect(stats.averageRating, 4.9);
      expect(stats.reviewsCount, 38);
    });

    test('getProductReviews requisita /targets/{id}/reviews com filtros', () async {
      mockBaseClient.responseToReturn = http.Response(
        jsonEncode({
          'content': [],
          'pageNumber': 0,
          'pageSize': 10,
          'totalElements': 0,
          'totalPages': 0,
          'isLast': true,
        }),
        200,
        headers: {'content-type': 'application/json'},
      );

      final page = await repository.getProductReviews('prod-100', page: 0, size: 10);

      expect(mockBaseClient.capturedRequest?.url.path, '/api/v1/targets/prod-100/reviews');
      expect(mockBaseClient.capturedRequest?.url.queryParameters['page'], '0');
      expect(mockBaseClient.capturedRequest?.url.queryParameters['sort'], 'newest');
      expect(page.reviews, isEmpty);
    });

    test('getProductImageBytes recupera bytes brutos com cliente autenticado', () async {
      final fakeBytes = Uint8List.fromList([137, 80, 78, 71]); // PNG magic bytes
      mockBaseClient.responseToReturn = http.Response.bytes(
        fakeBytes,
        200,
        headers: {'content-type': 'image/png'},
      );

      final bytes = await repository.getProductImageBytes('/api/v1/products/prod-100/image');

      expect(mockBaseClient.capturedRequest?.url.path, '/api/v1/products/prod-100/image');
      expect(bytes, equals(fakeBytes));
    });
  });
}
