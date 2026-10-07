import 'dart:convert';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/core/network/http_client.dart';
import 'package:rewit_mobile/features/discussions/data/repositories/discussion_repository_impl.dart';
import 'package:rewit_mobile/features/discussions/domain/entities/discussion_entities.dart';

void main() {
  group('DiscussionRepositoryImpl Integration Tests', () {
    test('getDiscussions consome endpoint paginado de discussões com sucesso', () async {
      final mockClient = MockClient((request) async {
        expect(request.method, 'GET');
        expect(request.url.path, '/api/v1/reviews/rev-1/discussions');
        expect(request.url.queryParameters['page'], '0');
        expect(request.url.queryParameters['size'], '20');

        final body = jsonEncode({
          'content': [
            {
              'id': 'd-1',
              'reviewId': 'rev-1',
              'parentId': null,
              'state': 'VISIBLE',
              'content': 'Excelente café da manhã.',
              'author': {
                'id': 'u-1',
                'handle': 'cafezeiro',
                'displayName': 'Café Lover',
                'avatarUrl': null,
              },
              'isFromOwner': false,
              'createdAt': '2026-10-06T15:00:00Z',
              'canReply': true,
              'canDelete': true,
              'replies': [],
              'replyCount': 0,
              'hasMoreReplies': false,
            }
          ],
          'pageNumber': 0,
          'pageSize': 20,
          'totalElements': 1,
          'totalPages': 1,
          'isLast': true,
        });

        return http.Response(body, 200, headers: {'content-type': 'application/json'});
      });

      final httpClient = RewitHttpClient(baseUrl: 'https://api.rewit.test', client: mockClient);
      final repo = DiscussionRepositoryImpl(httpClient: httpClient);

      final result = await repo.getDiscussions('rev-1', page: 0, size: 20);
      expect(result.content.length, 1);
      expect(result.content.first.id, 'd-1');
      expect(result.content.first.content, 'Excelente café da manhã.');
      expect(result.content.first.canReply, isTrue);
    });

    test('getReplies consome endpoint paginado de respostas', () async {
      final mockClient = MockClient((request) async {
        expect(request.method, 'GET');
        expect(request.url.path, '/api/v1/discussions/root-1/replies');
        expect(request.url.queryParameters['page'], '1');

        final body = jsonEncode({
          'content': [
            {
              'id': 'reply-2',
              'reviewId': 'rev-1',
              'parentId': 'root-1',
              'state': 'VISIBLE',
              'content': 'Resposta página 2',
              'author': null,
              'isFromOwner': true,
              'createdAt': '2026-10-06T16:00:00Z',
              'canReply': false,
              'canDelete': false,
            }
          ],
          'pageNumber': 1,
          'pageSize': 20,
          'totalElements': 2,
          'totalPages': 2,
          'isLast': true,
        });

        return http.Response(body, 200, headers: {'content-type': 'application/json'});
      });

      final httpClient = RewitHttpClient(baseUrl: 'https://api.rewit.test', client: mockClient);
      final repo = DiscussionRepositoryImpl(httpClient: httpClient);

      final result = await repo.getReplies('root-1', page: 1, size: 20);
      expect(result.content.length, 1);
      expect(result.content.first.id, 'reply-2');
      expect(result.content.first.isFromOwner, isTrue);
    });

    test('createDiscussion envia POST com payload e retorna comentário criado', () async {
      final mockClient = MockClient((request) async {
        expect(request.method, 'POST');
        expect(request.url.path, '/api/v1/reviews/rev-1/discussions');

        final map = jsonDecode(request.body) as Map<String, dynamic>;
        expect(map['content'], 'Comentário teste');
        expect(map['parentId'], 'root-1');

        final responseBody = jsonEncode({
          'id': 'new-reply-id',
          'reviewId': 'rev-1',
          'parentId': 'root-1',
          'content': 'Comentário teste',
          'isFromOwner': false,
          'status': 'ACTIVE',
          'createdAt': '2026-10-06T16:30:00Z',
        });

        return http.Response(responseBody, 201, headers: {'content-type': 'application/json'});
      });

      final httpClient = RewitHttpClient(baseUrl: 'https://api.rewit.test', client: mockClient);
      final repo = DiscussionRepositoryImpl(httpClient: httpClient);

      final item = await repo.createDiscussion(
        reviewId: 'rev-1',
        content: 'Comentário teste',
        parentId: 'root-1',
      );

      expect(item.id, 'new-reply-id');
      expect(item.parentId, 'root-1');
      expect(item.content, 'Comentário teste');
    });

    test('deleteDiscussion envia DELETE para /api/v1/discussions/{id}', () async {
      final mockClient = MockClient((request) async {
        expect(request.method, 'DELETE');
        expect(request.url.path, '/api/v1/discussions/disc-del-1');
        return http.Response('', 204);
      });

      final httpClient = RewitHttpClient(baseUrl: 'https://api.rewit.test', client: mockClient);
      final repo = DiscussionRepositoryImpl(httpClient: httpClient);

      await expectLater(repo.deleteDiscussion('disc-del-1'), completes);
    });

    test('reportDiscussion envia denúncia padronizada e retorna mensagem genérica', () async {
      final mockClient = MockClient((request) async {
        expect(request.method, 'POST');
        expect(request.url.path, '/api/v1/discussions/disc-rep-1/reports');

        final map = jsonDecode(request.body) as Map<String, dynamic>;
        expect(map['reason'], 'SPAM');
        expect(map['detail'], 'Propaganda de produto não relacionado');

        final responseBody = jsonEncode({
          'status': 'RECEIVED',
          'message': 'Denúncia recebida. Obrigado por ajudar a manter a comunidade segura.',
        });

        return http.Response(responseBody, 202, headers: {'content-type': 'application/json'});
      });

      final httpClient = RewitHttpClient(baseUrl: 'https://api.rewit.test', client: mockClient);
      final repo = DiscussionRepositoryImpl(httpClient: httpClient);

      final msg = await repo.reportDiscussion(
        discussionId: 'disc-rep-1',
        reason: ReportReason.spam,
        detail: 'Propaganda de produto não relacionado',
      );

      expect(msg, 'Denúncia recebida. Obrigado por ajudar a manter a comunidade segura.');
    });

    test('repassa erro RFC 7807 em caso de falha de exclusão (409 CONFLICT)', () async {
      final mockClient = MockClient((request) async {
        final errorBody = jsonEncode({
          'type': 'about:blank',
          'title': 'Conflito de Estado',
          'status': 409,
          'code': 'DISCUSSION_UNDER_REVIEW_MUTATION_DENIED',
          'detail': 'Não é permitido modificar comentário em quarentena.',
        });
        return http.Response(errorBody, 409, headers: {'content-type': 'application/json'});
      });

      final httpClient = RewitHttpClient(baseUrl: 'https://api.rewit.test', client: mockClient);
      final repo = DiscussionRepositoryImpl(httpClient: httpClient);

      expect(
        () => repo.deleteDiscussion('under-review-1'),
        throwsA(isA<ApiException>().having(
          (e) => e.errorCode,
          'errorCode',
          'DISCUSSION_UNDER_REVIEW_MUTATION_DENIED',
        )),
      );
    });
  });
}
