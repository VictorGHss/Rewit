import 'dart:convert';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/core/network/http_client.dart';
import 'package:rewit_mobile/core/storage/token_storage.dart';
import 'package:rewit_mobile/features/feed/data/repositories/feed_repository_impl.dart';

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
  });
}
