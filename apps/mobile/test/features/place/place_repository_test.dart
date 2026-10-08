import 'dart:convert';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/core/network/http_client.dart';
import 'package:rewit_mobile/features/place/data/models/place_dtos.dart';
import 'package:rewit_mobile/features/place/data/repositories/place_repository_impl.dart';

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
  group('Place DTOs & Models Tests', () {
    test('PlaceDto mapeia campos completos e gera formattedAddress corretamente', () {
      final json = {
        'id': 'place-uuid-1',
        'name': 'Café do Bosque',
        'slug': 'cafe-do-bosque',
        'category': 'ALIMENTACAO',
        'description': 'Um café agradável com mesas ao ar livre.',
        'addressText': 'Rua das Flores',
        'streetNumber': '123',
        'neighborhood': 'Centro',
        'city': 'Curitiba',
        'state': 'PR',
        'country': 'BR',
        'latitude': -25.4284,
        'longitude': -49.2733,
        'validationRadiusMeters': 100,
        'origin': 'USER',
        'isVerified': true,
        'status': 'ACTIVE',
      };

      final dto = PlaceDto.fromJson(json);
      final entity = dto.toEntity();

      expect(entity.id, 'place-uuid-1');
      expect(entity.name, 'Café do Bosque');
      expect(entity.slug, 'cafe-do-bosque');
      expect(entity.category, 'ALIMENTACAO');
      expect(entity.description, 'Um café agradável com mesas ao ar livre.');
      expect(entity.latitude, -25.4284);
      expect(entity.longitude, -49.2733);
      expect(entity.validationRadiusMeters, 100);
      expect(entity.isVerified, isTrue);
      expect(entity.status, 'ACTIVE');
      expect(entity.formattedAddress, 'Rua das Flores, 123 - Centro - Curitiba, PR');
    });

    test('TargetStatsDto mapeia estatísticas agregadas e trata lastCalculatedAt nulo', () {
      final jsonWithReviews = {
        'targetId': 'target-1',
        'averageRating': 4.75,
        'reviewsCount': 12,
        'lastCalculatedAt': '2026-10-07T12:00:00Z',
      };

      final dtoWithReviews = TargetStatsDto.fromJson(jsonWithReviews).toEntity();
      expect(dtoWithReviews.targetId, 'target-1');
      expect(dtoWithReviews.averageRating, 4.75);
      expect(dtoWithReviews.formattedRating, '4.8');
      expect(dtoWithReviews.reviewsCount, 12);
      expect(dtoWithReviews.hasReviews, isTrue);
      expect(dtoWithReviews.lastCalculatedAt, isNotNull);

      final jsonEmpty = {
        'targetId': 'target-2',
        'averageRating': 0.0,
        'reviewsCount': 0,
      };

      final dtoEmpty = TargetStatsDto.fromJson(jsonEmpty).toEntity();
      expect(dtoEmpty.averageRating, 0.0);
      expect(dtoEmpty.formattedRating, '0.0');
      expect(dtoEmpty.reviewsCount, 0);
      expect(dtoEmpty.hasReviews, isFalse);
      expect(dtoEmpty.lastCalculatedAt, isNull);
    });

    test('TargetReviewsPageDto mapeia lista de avaliações e metadados de paginação', () {
      final json = {
        'content': [
          {
            'id': 'rev-1',
            'author': {
              'id': 'author-1',
              'displayName': 'Maria Silva',
              'handle': 'mariasilva',
              'isAnonymous': false,
            },
            'experienceText': 'Excelente café espresso!',
            'visibility': 'PUBLIC',
            'status': 'ACTIVE',
            'createdAt': '2026-10-06T15:30:00Z',
            'targets': [
              {
                'id': 'target-item-1',
                'targetId': 'place-uuid-1',
                'rating': 5.0,
                'specificComment': 'Atendimento rápido',
              }
            ],
            'helpfulCount': 3,
            'isHelpfulByMe': false,
          }
        ],
        'pageNumber': 0,
        'pageSize': 10,
        'totalElements': 1,
        'totalPages': 1,
        'isLast': true,
      };

      final dto = TargetReviewsPageDto.fromJson(json);
      final entity = dto.toEntity();

      expect(entity.reviews.length, 1);
      expect(entity.reviews.first.id, 'rev-1');
      expect(entity.reviews.first.author.displayName, 'Maria Silva');
      expect(entity.reviews.first.experienceText, 'Excelente café espresso!');
      expect(entity.reviews.first.helpfulCount, 3);
      expect(entity.pageNumber, 0);
      expect(entity.totalElements, 1);
      expect(entity.isLast, isTrue);
    });
  });

  group('PlaceRepositoryImpl Integration Tests', () {
    late _MockHttpBaseClient innerClient;
    late RewitHttpClient httpClient;
    late PlaceRepositoryImpl repository;

    setUp(() {
      innerClient = _MockHttpBaseClient();
      httpClient = RewitHttpClient(
        baseUrl: 'https://api.rewit.test',
        client: innerClient,
      );
      repository = PlaceRepositoryImpl(httpClient: httpClient);
    });

    test('getPlaceById realiza GET /api/v1/places/{id} e retorna PlaceDetail', () async {
      innerClient.responseToReturn = http.Response(
        jsonEncode({
          'id': 'p-123',
          'name': 'Livraria Central',
          'slug': 'livraria-central',
          'category': 'CULTURA',
          'addressText': 'Av. Paulista',
          'city': 'São Paulo',
          'state': 'SP',
          'latitude': -23.561,
          'longitude': -46.656,
          'validationRadiusMeters': 150,
          'origin': 'USER',
          'isVerified': false,
          'status': 'ACTIVE',
        }),
        200,
        headers: {'content-type': 'application/json'},
      );

      final result = await repository.getPlaceById('p-123');

      expect(innerClient.capturedRequest?.method, 'GET');
      expect(innerClient.capturedRequest?.url.path, '/api/v1/places/p-123');
      expect(result.id, 'p-123');
      expect(result.name, 'Livraria Central');
      expect(result.city, 'São Paulo');
    });

    test('getPlaceById lança ApiException com isNotFound=true quando recebe 404', () async {
      innerClient.responseToReturn = http.Response(
        jsonEncode({
          'type': 'https://api.rewit.com/errors/place-not-found',
          'title': 'Not Found',
          'status': 404,
          'detail': 'Local não encontrado',
          'code': 'PLACE_NOT_FOUND',
        }),
        404,
        headers: {'content-type': 'application/problem+json'},
      );

      expect(
        () => repository.getPlaceById('p-unknown'),
        throwsA(isA<ApiException>().having((e) => e.isNotFound, 'isNotFound', isTrue)),
      );
    });

    test('getTargetStats realiza GET /api/v1/targets/{id}/stats', () async {
      innerClient.responseToReturn = http.Response(
        jsonEncode({
          'targetId': 'p-123',
          'averageRating': 4.2,
          'reviewsCount': 8,
          'lastCalculatedAt': '2026-10-07T10:00:00Z',
        }),
        200,
        headers: {'content-type': 'application/json'},
      );

      final stats = await repository.getTargetStats('p-123');

      expect(innerClient.capturedRequest?.method, 'GET');
      expect(innerClient.capturedRequest?.url.path, '/api/v1/targets/p-123/stats');
      expect(stats.targetId, 'p-123');
      expect(stats.averageRating, 4.2);
      expect(stats.reviewsCount, 8);
    });

    test('getTargetReviews realiza GET /api/v1/targets/{id}/reviews com query params', () async {
      innerClient.responseToReturn = http.Response(
        jsonEncode({
          'content': [],
          'pageNumber': 1,
          'pageSize': 10,
          'totalElements': 15,
          'totalPages': 2,
          'isLast': true,
        }),
        200,
        headers: {'content-type': 'application/json'},
      );

      final page = await repository.getTargetReviews(
        'p-123',
        page: 1,
        size: 10,
        sort: 'newest',
        verifiedOnly: false,
      );

      expect(innerClient.capturedRequest?.method, 'GET');
      expect(innerClient.capturedRequest?.url.path, '/api/v1/targets/p-123/reviews');
      expect(innerClient.capturedRequest?.url.queryParameters, {
        'page': '1',
        'size': '10',
        'sort': 'newest',
        'verifiedOnly': 'false',
      });
      expect(page.pageNumber, 1);
      expect(page.totalElements, 15);
      expect(page.isLast, isTrue);
    });
  });
}
