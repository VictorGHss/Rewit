import 'dart:convert';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/core/network/http_client.dart';
import 'package:rewit_mobile/features/review_creation/data/repositories/review_creation_repository_impl.dart';
import 'package:rewit_mobile/features/review_creation/domain/entities/review_creation_input.dart';

class MockHttpClient extends http.BaseClient {
  final Future<http.Response> Function(http.BaseRequest request) handler;

  MockHttpClient(this.handler);

  @override
  Future<http.StreamedResponse> send(http.BaseRequest request) async {
    final response = await handler(request);
    return http.StreamedResponse(
      Stream.value(utf8.encode(response.body)),
      response.statusCode,
      headers: response.headers,
      request: request,
    );
  }
}

void main() {
  group('ReviewCreationRepository Tests', () {
    const validTargetId = '11111111-1111-1111-1111-111111111111';
    const validPlaceId = '22222222-2222-2222-2222-222222222222';

    test('CreateReviewInput serializa JSON conforme contrato da API REST', () {
      const input = CreateReviewInput(
        contextPlaceId: validPlaceId,
        experienceText: 'Excelente atendimento e ambiente.',
        isAnonymous: true,
        visibility: 'PUBLIC',
        userLatitude: -23.55052,
        userLongitude: -46.633308,
        locationAccuracyMeters: 10.5,
        targets: [
          CreateReviewTargetInput(
            rateableTargetId: validTargetId,
            rating: 4.5,
            specificComment: 'Café de excelente qualidade',
          ),
        ],
      );

      final json = input.toJson();

      expect(json['contextPlaceId'], validPlaceId);
      expect(json['experienceText'], 'Excelente atendimento e ambiente.');
      expect(json['isAnonymous'], isTrue);
      expect(json['visibility'], 'PUBLIC');
      expect(json['userLatitude'], -23.55052);
      expect(json['userLongitude'], -46.633308);
      expect(json['locationAccuracyMeters'], 10.5);
      expect(json['targets'], isList);

      final targetsJson = json['targets'] as List;
      expect(targetsJson.length, 1);
      expect(targetsJson.first['rateableTargetId'], validTargetId);
      expect(targetsJson.first['rating'], 4.5);
      expect(targetsJson.first['specificComment'], 'Café de excelente qualidade');
    });

    test('createReview retorna FeedReview quando backend responde 201 Created', () async {
      final mockClient = MockHttpClient((request) async {
        expect(request.url.path, '/api/v1/reviews');
        expect(request.method, 'POST');

        final responsePayload = {
          'id': '99999999-9999-9999-9999-999999999999',
          'author': {
            'id': 'user-1',
            'handle': 'marcos',
            'displayName': 'Marcos Silva',
            'avatarUrl': null,
            'isAnonymous': false,
          },
          'contextPlaceId': validPlaceId,
          'experienceText': 'Muito bom!',
          'isAnonymous': false,
          'isVerifiedOnSite': true,
          'visibility': 'PUBLIC',
          'status': 'VISIBLE',
          'createdAt': '2026-10-07T12:00:00Z',
          'updatedAt': null,
          'targets': [
            {
              'id': 'target-item-1',
              'targetId': validTargetId,
              'rating': 5.0,
              'specificComment': 'Nota 10',
              'createdAt': '2026-10-07T12:00:00Z',
            }
          ],
          'helpfulCount': 0,
          'isHelpfulByMe': false,
        };

        return http.Response(
          jsonEncode(responsePayload),
          201,
          headers: {'content-type': 'application/json'},
        );
      });

      final httpClient = RewitHttpClient(
        baseUrl: 'https://api.rewit.com',
        client: mockClient,
      );
      final repository = ReviewCreationRepositoryImpl(httpClient: httpClient);

      const input = CreateReviewInput(
        contextPlaceId: validPlaceId,
        experienceText: 'Muito bom!',
        targets: [
          CreateReviewTargetInput(
            rateableTargetId: validTargetId,
            rating: 5.0,
            specificComment: 'Nota 10',
          ),
        ],
      );

      final result = await repository.createReview(input);

      expect(result.id, '99999999-9999-9999-9999-999999999999');
      expect(result.author.displayName, 'Marcos Silva');
      expect(result.author.handle, 'marcos');
      expect(result.targets.length, 1);
      expect(result.targets.first.rating, 5.0);
      expect(result.targets.first.specificComment, 'Nota 10');
      expect(result.isVerifiedOnSite, isTrue);
    });

    test('createReview propaga ApiException em caso de erro 422 com RFC 7807', () async {
      final mockClient = MockHttpClient((request) async {
        final problem = {
          'type': 'https://api.rewit.com/errors/duplicate-target',
          'title': 'Alvo Duplicado',
          'status': 422,
          'detail': 'Não é permitido avaliar o mesmo alvo mais de uma vez.',
          'code': 'DUPLICATE_REVIEW_TARGET',
        };

        return http.Response(
          jsonEncode(problem),
          422,
          headers: {'content-type': 'application/problem+json; charset=utf-8'},
        );
      });

      final httpClient = RewitHttpClient(
        baseUrl: 'https://api.rewit.com',
        client: mockClient,
      );
      final repository = ReviewCreationRepositoryImpl(httpClient: httpClient);

      const input = CreateReviewInput(
        targets: [
          CreateReviewTargetInput(rateableTargetId: validTargetId, rating: 5.0),
        ],
      );

      expect(
        () => repository.createReview(input),
        throwsA(
          isA<ApiException>()
              .having((e) => e.statusCode, 'statusCode', 422)
              .having((e) => e.errorCode, 'errorCode', 'DUPLICATE_REVIEW_TARGET')
              .having((e) => e.detail, 'detail', contains('Não é permitido avaliar')),
        ),
      );
    });

    test('createReview propaga ApiException com Retry-After em caso de 429', () async {
      final mockClient = MockHttpClient((request) async {
        final problem = {
          'type': 'https://api.rewit.com/errors/rate-limit',
          'title': 'Limite de Requisições Excedido',
          'status': 429,
          'detail': 'Limite de publicações excedido.',
          'code': 'RATE_LIMIT_EXCEEDED',
        };

        return http.Response(
          jsonEncode(problem),
          429,
          headers: {
            'content-type': 'application/problem+json; charset=utf-8',
            'retry-after': '30',
          },
        );
      });

      final httpClient = RewitHttpClient(
        baseUrl: 'https://api.rewit.com',
        client: mockClient,
      );
      final repository = ReviewCreationRepositoryImpl(httpClient: httpClient);

      const input = CreateReviewInput(
        targets: [
          CreateReviewTargetInput(rateableTargetId: validTargetId, rating: 4.0),
        ],
      );

      expect(
        () => repository.createReview(input),
        throwsA(
          isA<ApiException>()
              .having((e) => e.statusCode, 'statusCode', 429)
              .having((e) => e.retryAfterSeconds, 'retryAfterSeconds', 30),
        ),
      );
    });

    test('createReview propaga ApiException com fieldErrors quando backend responde 400', () async {
      final mockClient = MockHttpClient((request) async {
        final problem = {
          'type': 'https://api.rewit.app/errors/validation-error',
          'title': 'Erro de Validação de Dados',
          'status': 400,
          'detail': 'Parâmetros da requisição inválidos',
          'code': 'VALIDATION_ERROR',
          'fieldErrors': {
            'targets': 'A publicação deve conter pelo menos um alvo avaliado',
          },
        };

        return http.Response(
          jsonEncode(problem),
          400,
          headers: {'content-type': 'application/problem+json; charset=utf-8'},
        );
      });

      final httpClient = RewitHttpClient(
        baseUrl: 'https://api.rewit.com',
        client: mockClient,
      );
      final repository = ReviewCreationRepositoryImpl(httpClient: httpClient);

      const input = CreateReviewInput(
        targets: [
          CreateReviewTargetInput(rateableTargetId: validTargetId, rating: 5.0),
        ],
      );

      expect(
        () => repository.createReview(input),
        throwsA(
          isA<ApiException>()
              .having((e) => e.statusCode, 'statusCode', 400)
              .having((e) => e.errorCode, 'errorCode', 'VALIDATION_ERROR')
              .having((e) => e.hasFieldErrors, 'hasFieldErrors', isTrue)
              .having(
                (e) => e.getFieldError('targets'),
                'getFieldError(targets)',
                'A publicação deve conter pelo menos um alvo avaliado',
              ),
        ),
      );
    });
  });
}
