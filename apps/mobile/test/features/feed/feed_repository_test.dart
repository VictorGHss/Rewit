import 'dart:convert';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/core/network/http_client.dart';
import 'package:rewit_mobile/core/storage/token_storage.dart';
import 'package:rewit_mobile/features/feed/data/repositories/feed_repository_impl.dart';
import 'package:rewit_mobile/features/review_detail/domain/entities/update_review_input.dart';

class MockHttpHandler extends http.BaseClient {
  final Future<http.StreamedResponse> Function(http.BaseRequest request) handler;

  MockHttpHandler(this.handler);

  @override
  Future<http.StreamedResponse> send(http.BaseRequest request) => handler(request);
}

void main() {
  group('FeedRepositoryImpl', () {
    late InMemoryTokenStorage tokenStorage;

    setUp(() async {
      tokenStorage = InMemoryTokenStorage();
      await tokenStorage.saveTokens(
        accessToken: 'feed-valid-jwt',
        refreshToken: 'refresh-jwt',
      );
    });

    test('getFeed consome /api/v2/feed com paginação e token Bearer', () async {
      final mockClient = MockHttpHandler((request) async {
        expect(request.url.path, '/api/v2/feed');
        expect(request.url.queryParameters['page'], '1');
        expect(request.url.queryParameters['size'], '15');
        expect(request.headers['authorization'], 'Bearer feed-valid-jwt');

        final responsePayload = {
          'items': [
            {
              'id': 'rev-page1-item1',
              'author': {
                'id': 'user-1',
                'handle': 'marcos',
                'displayName': 'Marcos Lima',
              },
              'experienceText': 'Muito bom!',
              'status': 'ACTIVE',
              'visibility': 'PUBLIC',
              'isAnonymous': false,
              'isVerifiedOnSite': true,
              'createdAt': '2026-10-06T15:00:00Z',
              'targets': [],
            }
          ],
          'page': 1,
          'size': 15,
          'windowSize': 50,
          'totalPages': 4,
        };

        return http.StreamedResponse(
          Stream.value(utf8.encode(jsonEncode(responsePayload))),
          200,
        );
      });

      final httpClient = RewitHttpClient(
        baseUrl: 'https://api.rewit.test',
        client: mockClient,
        tokenStorage: tokenStorage,
      );

      final repo = FeedRepositoryImpl(httpClient: httpClient);
      final feedPage = await repo.getFeed(page: 1, size: 15);

      expect(feedPage.page, 1);
      expect(feedPage.size, 15);
      expect(feedPage.totalPages, 4);
      expect(feedPage.items.length, 1);
      expect(feedPage.items.first.author.displayName, 'Marcos Lima');
    });

    test('getReviewById consome /api/v1/reviews/{id} e retorna detalhes da avaliação', () async {
      final mockClient = MockHttpHandler((request) async {
        expect(request.url.path, '/api/v1/reviews/review-xyz');
        expect(request.headers['authorization'], 'Bearer feed-valid-jwt');

        final responsePayload = {
          'id': 'review-xyz',
          'author': {
            'id': 'author-uuid',
            'handle': 'carla',
            'displayName': 'Carla Dias',
          },
          'experienceText': 'Ótimo atendimento no restaurante.',
          'status': 'ACTIVE',
          'visibility': 'PUBLIC',
          'isAnonymous': false,
          'isVerifiedOnSite': true,
          'helpfulCount': 5,
          'isHelpfulByMe': true,
          'targets': [
            {
              'id': 't1',
              'targetId': 'place-target-1',
              'rating': 4.8,
              'specificComment': 'Prato principal excelente',
            }
          ],
          'createdAt': '2026-10-06T12:00:00Z',
        };

        return http.StreamedResponse(
          Stream.value(utf8.encode(jsonEncode(responsePayload))),
          200,
        );
      });

      final httpClient = RewitHttpClient(
        baseUrl: 'https://api.rewit.test',
        client: mockClient,
        tokenStorage: tokenStorage,
      );

      final repo = FeedRepositoryImpl(httpClient: httpClient);
      final review = await repo.getReviewById('review-xyz');

      expect(review.id, 'review-xyz');
      expect(review.author.displayName, 'Carla Dias');
      expect(review.targets.first.rating, 4.8);
      expect(review.helpfulCount, 5);
      expect(review.isHelpfulByMe, isTrue);
    });

    test('getFeed repassa ApiException com erro estruturado RFC 7807', () async {
      final mockClient = MockHttpHandler((request) async {
        final errorPayload = {
          'type': 'about:blank',
          'title': 'Requisição Inválida',
          'status': 400,
          'detail': 'O tamanho da página não pode exceder 50 itens.',
          'code': 'PAGE_SIZE_EXCEEDED',
        };
        return http.StreamedResponse(
          Stream.value(utf8.encode(jsonEncode(errorPayload))),
          400,
        );
      });

      final httpClient = RewitHttpClient(
        baseUrl: 'https://api.rewit.test',
        client: mockClient,
        tokenStorage: tokenStorage,
      );

      final repo = FeedRepositoryImpl(httpClient: httpClient);

      await expectLater(
        repo.getFeed(page: 0, size: 100),
        throwsA(isA<ApiException>().having((e) => e.errorCode, 'code', 'PAGE_SIZE_EXCEEDED')),
      );
    });

    test('updateReview consome PATCH /api/v1/reviews/{id} com payload serializado e token Bearer', () async {
      final mockClient = MockHttpHandler((request) async {
        expect(request.method, 'PATCH');
        expect(request.url.path, '/api/v1/reviews/rev-edit-1');
        expect(request.headers['authorization'], 'Bearer feed-valid-jwt');

        final body = jsonDecode(await request.finalize().bytesToString()) as Map<String, dynamic>;
        expect(body['experienceText'], 'Texto editado');
        expect(body['targetRatings'], {'tgt-1': 4.5});
        expect(body['isAnonymous'], true);
        expect(body['visibility'], 'PRIVATE');

        final responsePayload = {
          'id': 'rev-edit-1',
          'author': {
            'id': null,
            'displayName': 'Anônimo',
            'isAnonymous': true,
          },
          'experienceText': 'Texto editado',
          'status': 'ACTIVE',
          'visibility': 'PRIVATE',
          'isAnonymous': true,
          'isVerifiedOnSite': false,
          'helpfulCount': 0,
          'isHelpfulByMe': false,
          'targets': [
            {
              'id': 't1',
              'targetId': 'tgt-1',
              'rating': 4.5,
            }
          ],
          'createdAt': '2026-10-06T12:00:00Z',
          'updatedAt': '2026-10-06T13:00:00Z',
        };

        return http.StreamedResponse(
          Stream.value(utf8.encode(jsonEncode(responsePayload))),
          200,
        );
      });

      final httpClient = RewitHttpClient(
        baseUrl: 'https://api.rewit.test',
        client: mockClient,
        tokenStorage: tokenStorage,
      );

      final repo = FeedRepositoryImpl(httpClient: httpClient);
      final updated = await repo.updateReview(
        'rev-edit-1',
        const UpdateReviewInput(
          experienceText: 'Texto editado',
          targetRatings: {'tgt-1': 4.5},
          isAnonymous: true,
          visibility: 'PRIVATE',
        ),
      );

      expect(updated.id, 'rev-edit-1');
      expect(updated.experienceText, 'Texto editado');
      expect(updated.isAnonymous, isTrue);
      expect(updated.visibility, 'PRIVATE');
      expect(updated.targets.first.rating, 4.5);
    });

    test('updateReview repassa ApiException em 409 quando janela de 24h expira ou ratings bloqueados', () async {
      final mockClient = MockHttpHandler((request) async {
        final errorPayload = {
          'type': 'about:blank',
          'title': 'Conflito de Estado',
          'status': 409,
          'detail': 'A janela permitida de 24 horas para edição expirou',
          'code': 'REVIEW_EDIT_WINDOW_EXPIRED',
        };
        return http.StreamedResponse(
          Stream.value(utf8.encode(jsonEncode(errorPayload))),
          409,
        );
      });

      final httpClient = RewitHttpClient(
        baseUrl: 'https://api.rewit.test',
        client: mockClient,
        tokenStorage: tokenStorage,
      );

      final repo = FeedRepositoryImpl(httpClient: httpClient);

      await expectLater(
        repo.updateReview(
          'rev-edit-expired',
          const UpdateReviewInput(experienceText: 'Novo texto'),
        ),
        throwsA(isA<ApiException>().having((e) => e.errorCode, 'code', 'REVIEW_EDIT_WINDOW_EXPIRED')),
      );
    });

    test('deleteReview consome DELETE /api/v1/reviews/{id} e completa em 204 No Content', () async {
      final mockClient = MockHttpHandler((request) async {
        expect(request.method, 'DELETE');
        expect(request.url.path, '/api/v1/reviews/rev-del-1');
        expect(request.headers['authorization'], 'Bearer feed-valid-jwt');

        return http.StreamedResponse(
          const Stream.empty(),
          204,
        );
      });

      final httpClient = RewitHttpClient(
        baseUrl: 'https://api.rewit.test',
        client: mockClient,
        tokenStorage: tokenStorage,
      );

      final repo = FeedRepositoryImpl(httpClient: httpClient);
      await expectLater(repo.deleteReview('rev-del-1'), completes);
    });

    test('deleteReview repassa ApiException em 403 quando o solicitante não é o autor', () async {
      final mockClient = MockHttpHandler((request) async {
        final errorPayload = {
          'type': 'about:blank',
          'title': 'Acesso Proibido',
          'status': 403,
          'detail': 'Apenas o autor pode excluir a avaliação',
          'code': 'REVIEW_NOT_OWNED',
        };
        return http.StreamedResponse(
          Stream.value(utf8.encode(jsonEncode(errorPayload))),
          403,
        );
      });

      final httpClient = RewitHttpClient(
        baseUrl: 'https://api.rewit.test',
        client: mockClient,
        tokenStorage: tokenStorage,
      );

      final repo = FeedRepositoryImpl(httpClient: httpClient);

      await expectLater(
        repo.deleteReview('rev-del-forbidden'),
        throwsA(isA<ApiException>().having((e) => e.errorCode, 'code', 'REVIEW_NOT_OWNED')),
      );
    });
    test('getFollowingFeed consome /api/v1/feed com envelope V1, paginação e token Bearer', () async {
      final mockClient = MockHttpHandler((request) async {
        expect(request.url.path, '/api/v1/feed');
        expect(request.url.queryParameters['page'], '2');
        expect(request.url.queryParameters['size'], '20');
        expect(request.headers['authorization'], 'Bearer feed-valid-jwt');

        final responsePayload = {
          'content': [
            {
              'id': 'rev-following-1',
              'author': {
                'id': 'user-followed-1',
                'handle': 'beatriz',
                'displayName': 'Beatriz Souza',
              },
              'experienceText': 'Excelente cafeteria com cafés especiais!',
              'status': 'ACTIVE',
              'visibility': 'PUBLIC',
              'isAnonymous': false,
              'isVerifiedOnSite': true,
              'createdAt': '2026-11-04T02:15:00Z',
              'targets': [],
              'helpfulCount': 8,
              'isHelpfulByMe': false,
              'isMine': false,
            }
          ],
          'pageNumber': 2,
          'pageSize': 20,
          'totalElements': 45,
          'totalPages': 3,
          'isLast': false,
        };

        return http.StreamedResponse(
          Stream.value(utf8.encode(jsonEncode(responsePayload))),
          200,
        );
      });

      final httpClient = RewitHttpClient(
        baseUrl: 'https://api.rewit.test',
        client: mockClient,
        tokenStorage: tokenStorage,
      );

      final repo = FeedRepositoryImpl(httpClient: httpClient);
      final feedPage = await repo.getFollowingFeed(page: 2, size: 20);

      expect(feedPage.page, 2);
      expect(feedPage.size, 20);
      expect(feedPage.totalElements, 45);
      expect(feedPage.totalPages, 3);
      expect(feedPage.isLast, isFalse);
      expect(feedPage.hasMore, isTrue);
      expect(feedPage.items.length, 1);
      expect(feedPage.items.first.author.displayName, 'Beatriz Souza');
      expect(feedPage.items.first.author.handle, 'beatriz');
    });

    test('getFollowingFeed e getFeed diferenciam corretamente seus respectivos endpoints', () async {
      final requestedPaths = <String>[];

      final mockClient = MockHttpHandler((request) async {
        requestedPaths.add(request.url.path);

        if (request.url.path == '/api/v1/feed') {
          final payloadV1 = {
            'content': [],
            'pageNumber': 0,
            'pageSize': 10,
            'totalElements': 0,
            'totalPages': 0,
            'isLast': true,
          };
          return http.StreamedResponse(
            Stream.value(utf8.encode(jsonEncode(payloadV1))),
            200,
          );
        } else if (request.url.path == '/api/v2/feed') {
          final payloadV2 = {
            'items': [],
            'page': 0,
            'size': 10,
            'windowSize': 0,
            'totalPages': 0,
          };
          return http.StreamedResponse(
            Stream.value(utf8.encode(jsonEncode(payloadV2))),
            200,
          );
        }

        return http.StreamedResponse(Stream.value([]), 404);
      });

      final httpClient = RewitHttpClient(
        baseUrl: 'https://api.rewit.test',
        client: mockClient,
        tokenStorage: tokenStorage,
      );

      final repo = FeedRepositoryImpl(httpClient: httpClient);

      final v2Page = await repo.getFeed(page: 0, size: 10);
      final v1Page = await repo.getFollowingFeed(page: 0, size: 10);

      expect(v2Page.isEmpty, isTrue);
      expect(v1Page.isEmpty, isTrue);
      expect(requestedPaths, ['/api/v2/feed', '/api/v1/feed']);
    });
  });
}
