import 'dart:convert';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/core/network/http_client.dart';
import 'package:rewit_mobile/features/business/data/models/business_response_dtos.dart';
import 'package:rewit_mobile/features/business/data/repositories/business_response_repository_impl.dart';

class MockHttpClient extends http.BaseClient {
  http.Response Function(http.BaseRequest request)? handler;

  @override
  Future<http.StreamedResponse> send(http.BaseRequest request) async {
    final response = handler != null
        ? handler!(request)
        : http.Response('{"detail": "Not found"}', 404);
    return http.StreamedResponse(
      Stream.value(utf8.encode(response.body)),
      response.statusCode,
      headers: response.headers,
    );
  }
}

void main() {
  group('BusinessResponse DTOs & Contracts Tests (C11)', () {
    test('BusinessResponseDto deserializa payload canônico da API', () {
      final json = {
        'id': 'resp-123',
        'reviewId': 'rev-456',
        'discussionId': null,
        'businessAccountId': 'acc-789',
        'corporateName': 'Padaria Pão Dourado Ltda',
        'placeId': 'plc-101',
        'placeName': 'Pão Dourado Centro',
        'content': 'Agradecemos o feedback e lamentamos a experiência.',
        'createdAt': '2026-10-11T14:30:00Z',
      };

      final dto = BusinessResponseDto.fromJson(json);
      final entity = dto.toEntity();

      expect(entity.id, 'resp-123');
      expect(entity.reviewId, 'rev-456');
      expect(entity.discussionId, isNull);
      expect(entity.businessAccountId, 'acc-789');
      expect(entity.corporateName, 'Padaria Pão Dourado Ltda');
      expect(entity.placeId, 'plc-101');
      expect(entity.placeName, 'Pão Dourado Centro');
      expect(entity.content, 'Agradecemos o feedback e lamentamos a experiência.');
      expect(entity.isReviewResponse, isTrue);
      expect(entity.isDiscussionResponse, isFalse);
    });

    test('BusinessResponsePageDto deserializa PagedResponse paginado', () {
      final json = {
        'content': [
          {
            'id': 'resp-1',
            'reviewId': 'rev-1',
            'discussionId': 'disc-1',
            'businessAccountId': 'acc-1',
            'corporateName': 'Café Expresso',
            'placeId': 'plc-1',
            'placeName': 'Café Expresso Matriz',
            'content': 'Resposta ao comentário.',
            'createdAt': '2026-10-11T12:00:00Z',
          }
        ],
        'pageNumber': 0,
        'pageSize': 20,
        'totalElements': 1,
        'totalPages': 1,
        'isLast': true,
      };

      final dto = BusinessResponsePageDto.fromJson(json);
      final entity = dto.toEntity();

      expect(entity.content.length, 1);
      expect(entity.content.first.isDiscussionResponse, isTrue);
      expect(entity.pageNumber, 0);
      expect(entity.pageSize, 20);
      expect(entity.totalElements, 1);
      expect(entity.totalPages, 1);
      expect(entity.isLast, isTrue);
    });

    test('BusinessResponseEligibilityDto deserializa múltiplas contas e locais', () {
      final json = {
        'reviewId': 'rev-999',
        'respondable': true,
        'accounts': [
          {
            'businessAccountId': 'acc-10',
            'corporateName': 'Rede Supermercados XYZ',
            'places': [
              {'placeId': 'plc-11', 'placeName': 'Unidade Norte'},
              {'placeId': 'plc-12', 'placeName': 'Unidade Sul'},
            ],
            'activeResponseId': null,
            'canPublish': true,
          },
          {
            'businessAccountId': 'acc-20',
            'corporateName': 'Farmácia Popular ABC',
            'places': [
              {'placeId': 'plc-21', 'placeName': 'Filial Centro'},
            ],
            'activeResponseId': 'resp-old',
            'canPublish': false,
          }
        ]
      };

      final dto = BusinessResponseEligibilityDto.fromJson(json);
      final entity = dto.toEntity();

      expect(entity.reviewId, 'rev-999');
      expect(entity.respondable, isTrue);
      expect(entity.hasEligibleAccounts, isTrue);
      expect(entity.accounts.length, 2);

      final acc1 = entity.accounts[0];
      expect(acc1.businessAccountId, 'acc-10');
      expect(acc1.canPublish, isTrue);
      expect(acc1.places.length, 2);
      expect(acc1.places[0].placeName, 'Unidade Norte');
      expect(acc1.places[1].placeName, 'Unidade Sul');

      final acc2 = entity.accounts[1];
      expect(acc2.canPublish, isFalse);
      expect(acc2.activeResponseId, 'resp-old');
    });
  });

  group('BusinessResponseRepositoryImpl Tests (C11)', () {
    late MockHttpClient mockClient;
    late RewitHttpClient httpClient;
    late BusinessResponseRepositoryImpl repository;

    setUp(() {
      mockClient = MockHttpClient();
      httpClient = RewitHttpClient(baseUrl: 'https://api.rewit.com', client: mockClient);
      repository = BusinessResponseRepositoryImpl(httpClient: httpClient);
    });

    test('getReviewBusinessResponses chama endpoint correto e retorna página', () async {
      mockClient.handler = (req) {
        expect(req.method, 'GET');
        expect(req.url.path, '/api/v1/reviews/rev-1/business-responses');
        expect(req.url.queryParameters['page'], '0');
        expect(req.url.queryParameters['size'], '20');

        return http.Response(
          jsonEncode({
            'content': [
              {
                'id': 'resp-1',
                'reviewId': 'rev-1',
                'discussionId': null,
                'businessAccountId': 'acc-1',
                'corporateName': 'Empresa Teste',
                'placeId': 'plc-1',
                'placeName': 'Local Teste',
                'content': 'Conteúdo da resposta oficial',
                'createdAt': '2026-10-11T10:00:00Z',
              }
            ],
            'pageNumber': 0,
            'pageSize': 20,
            'totalElements': 1,
            'totalPages': 1,
            'isLast': true,
          }),
          200,
        );
      };

      final result = await repository.getReviewBusinessResponses('rev-1');
      expect(result.content.length, 1);
      expect(result.content.first.content, 'Conteúdo da resposta oficial');
    });

    test('getReviewEligibility consulta elegibilidade com sucesso', () async {
      mockClient.handler = (req) {
        expect(req.method, 'GET');
        expect(req.url.path, '/api/v1/reviews/rev-1/business-responses/eligibility');

        return http.Response(
          jsonEncode({
            'reviewId': 'rev-1',
            'respondable': true,
            'accounts': [
              {
                'businessAccountId': 'acc-1',
                'corporateName': 'Empresa Aprovada',
                'places': [{'placeId': 'plc-1', 'placeName': 'Loja Central'}],
                'activeResponseId': null,
                'canPublish': true,
              }
            ]
          }),
          200,
        );
      };

      final result = await repository.getReviewEligibility('rev-1');
      expect(result.respondable, isTrue);
      expect(result.hasEligibleAccounts, isTrue);
    });

    test('publishReviewBusinessResponse envia payload correto e retorna criado', () async {
      mockClient.handler = (req) {
        expect(req.method, 'POST');
        expect(req.url.path, '/api/v1/reviews/rev-1/business-responses');

        final body = jsonDecode((req as http.Request).body);
        expect(body['businessAccountId'], 'acc-1');
        expect(body['placeId'], 'plc-1');
        expect(body['content'], 'Nova resposta oficial');

        return http.Response(
          jsonEncode({
            'id': 'resp-new',
            'reviewId': 'rev-1',
            'discussionId': null,
            'businessAccountId': 'acc-1',
            'corporateName': 'Empresa Aprovada',
            'placeId': 'plc-1',
            'placeName': 'Loja Central',
            'content': 'Nova resposta oficial',
            'createdAt': '2026-10-11T12:00:00Z',
          }),
          201,
        );
      };

      final result = await repository.publishReviewBusinessResponse(
        reviewId: 'rev-1',
        businessAccountId: 'acc-1',
        placeId: 'plc-1',
        content: 'Nova resposta oficial',
      );

      expect(result.id, 'resp-new');
      expect(result.content, 'Nova resposta oficial');
    });

    test('publishDiscussionBusinessResponse envia payload correto para comentário raiz', () async {
      mockClient.handler = (req) {
        expect(req.method, 'POST');
        expect(req.url.path, '/api/v1/discussions/disc-1/business-responses');

        final body = jsonDecode((req as http.Request).body);
        expect(body['businessAccountId'], 'acc-1');
        expect(body['placeId'], 'plc-1');
        expect(body['content'], 'Resposta ao comentário raiz');

        return http.Response(
          jsonEncode({
            'id': 'resp-disc-new',
            'reviewId': 'rev-1',
            'discussionId': 'disc-1',
            'businessAccountId': 'acc-1',
            'corporateName': 'Empresa Aprovada',
            'placeId': 'plc-1',
            'placeName': 'Loja Central',
            'content': 'Resposta ao comentário raiz',
            'createdAt': '2026-10-11T12:05:00Z',
          }),
          201,
        );
      };

      final result = await repository.publishDiscussionBusinessResponse(
        discussionId: 'disc-1',
        businessAccountId: 'acc-1',
        placeId: 'plc-1',
        content: 'Resposta ao comentário raiz',
      );

      expect(result.id, 'resp-disc-new');
      expect(result.discussionId, 'disc-1');
    });

    test('deleteBusinessResponse remove com 204 No Content', () async {
      mockClient.handler = (req) {
        expect(req.method, 'DELETE');
        expect(req.url.path, '/api/v1/business-responses/resp-123');
        return http.Response('', 204);
      };

      await repository.deleteBusinessResponse('resp-123');
    });

    test('Tratamento de erro 429 RATE_LIMIT_EXCEEDED com Retry-After', () async {
      mockClient.handler = (req) {
        return http.Response(
          jsonEncode({
            'type': 'https://api.rewit.com/errors/rate-limit-exceeded',
            'title': 'Limite de Requisições Excedido',
            'status': 429,
            'detail': 'Você atingiu o limite de publicações. Tente novamente em 60 segundos.',
            'code': 'RATE_LIMIT_EXCEEDED',
          }),
          429,
          headers: {'Retry-After': '60'},
        );
      };

      expect(
        () => repository.publishReviewBusinessResponse(
          reviewId: 'rev-1',
          businessAccountId: 'acc-1',
          placeId: 'plc-1',
          content: 'Excedendo taxa',
        ),
        throwsA(
          isA<ApiException>()
              .having((e) => e.statusCode, 'statusCode', 429)
              .having((e) => e.errorCode, 'errorCode', 'RATE_LIMIT_EXCEEDED')
              .having((e) => e.retryAfterSeconds, 'retryAfterSeconds', 60),
        ),
      );
    });

    test('Tratamento de erro 409 BUSINESS_ACCOUNT_NOT_APPROVED', () async {
      mockClient.handler = (req) {
        return http.Response(
          jsonEncode({
            'type': 'https://api.rewit.com/errors/conflict',
            'title': 'Conflito de Dados',
            'status': 409,
            'detail': 'Conta comercial não aprovada.',
            'code': 'BUSINESS_ACCOUNT_NOT_APPROVED',
          }),
          409,
        );
      };

      expect(
        () => repository.publishReviewBusinessResponse(
          reviewId: 'rev-1',
          businessAccountId: 'acc-pending',
          placeId: 'plc-1',
          content: 'Tentando responder',
        ),
        throwsA(
          isA<ApiException>()
              .having((e) => e.statusCode, 'statusCode', 409)
              .having((e) => e.errorCode, 'errorCode', 'BUSINESS_ACCOUNT_NOT_APPROVED'),
        ),
      );
    });
  });
}
