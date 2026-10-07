import 'package:flutter_test/flutter_test.dart';
import 'package:rewit_mobile/features/feed/data/models/feed_models.dart';

void main() {
  group('Feed V2 JSON Parsing', () {
    test('deve deserializar envelope completo de FeedPageDto e converter para FeedPage', () {
      final json = {
        'items': [
          {
            'id': '7b0d2358-1f6e-4ab4-802b-3e5e7da09101',
            'author': {
              'id': '01944883-9366-71d3-a5c8-c672b1234567',
              'handle': 'alice',
              'displayName': 'Alice Cooper',
              'avatarUrl': 'https://cdn.rewit.com/avatars/alice.png',
              'isAnonymous': false,
            },
            'contextPlaceId': 'c9287311-2e63-4414-87a3-e380e5b7c7b1',
            'experienceText': 'Lugar fantástico com excelente café!',
            'status': 'ACTIVE',
            'visibility': 'PUBLIC',
            'isAnonymous': false,
            'isVerifiedOnSite': true,
            'helpfulCount': 3,
            'isHelpfulByMe': true,
            'targets': [
              {
                'id': 't1',
                'reviewId': '7b0d2358-1f6e-4ab4-802b-3e5e7da09101',
                'targetId': 'target-place-1',
                'rating': 5.0,
                'specificComment': 'Café excelente',
                'createdAt': '2026-09-28T21:15:00Z',
              },
              {
                'id': 't2',
                'reviewId': '7b0d2358-1f6e-4ab4-802b-3e5e7da09101',
                'targetId': 'target-product-2',
                'rating': 4.0,
                'createdAt': '2026-09-28T21:15:00Z',
              }
            ],
            'createdAt': '2026-09-28T21:15:00Z',
            'updatedAt': '2026-09-28T21:15:00Z',
          }
        ],
        'page': 0,
        'size': 10,
        'windowSize': 50,
        'totalPages': 3,
      };

      final dto = FeedPageDto.fromJson(json);
      final entity = dto.toEntity();

      expect(entity.page, 0);
      expect(entity.size, 10);
      expect(entity.windowSize, 50);
      expect(entity.totalPages, 3);
      expect(entity.hasMore, isTrue);
      expect(entity.isEmpty, isFalse);
      expect(entity.items.length, 1);

      final review = entity.items.first;
      expect(review.id, '7b0d2358-1f6e-4ab4-802b-3e5e7da09101');
      expect(review.author.displayName, 'Alice Cooper');
      expect(review.author.handle, 'alice');
      expect(review.author.displayHandle, '@alice');
      expect(review.experienceText, 'Lugar fantástico com excelente café!');
      expect(review.isVerifiedOnSite, isTrue);
      expect(review.helpfulCount, 3);
      expect(review.isHelpfulByMe, isTrue);
      expect(review.targets.length, 2);
      expect(review.averageRating, 4.5); // (5.0 + 4.0) / 2
    });

    test('deve deserializar autor anônimo com segurança e ocultar identificadores', () {
      final json = {
        'items': [
          {
            'id': 'anon-rev-1',
            'author': {
              'id': null,
              'handle': null,
              'displayName': 'Anônimo',
              'avatarUrl': null,
              'isAnonymous': true,
            },
            'experienceText': 'Avaliação anônima do produto.',
            'status': 'ACTIVE',
            'visibility': 'FOLLOWERS',
            'isAnonymous': true,
            'isVerifiedOnSite': false,
            'helpfulCount': 0,
            'isHelpfulByMe': false,
            'targets': [],
            'createdAt': '2026-09-28T20:00:00Z',
          }
        ],
        'page': 2,
        'size': 10,
        'windowSize': 30,
        'totalPages': 3,
      };

      final entity = FeedPageDto.fromJson(json).toEntity();
      final review = entity.items.first;

      expect(review.isAnonymous, isTrue);
      expect(review.author.isAnonymous, isTrue);
      expect(review.author.id, isNull);
      expect(review.author.handle, isNull);
      expect(review.author.displayName, 'Anônimo');
      expect(review.author.displayHandle, 'Anônimo');
      expect(review.isVerifiedOnSite, isFalse);
      expect(review.averageRating, isNull);
      expect(entity.hasMore, isFalse); // page 2 de totalPages 3 (0, 1, 2)
    });

    test('deve lidar com lista vazia de items', () {
      final json = {
        'items': [],
        'page': 0,
        'size': 10,
        'windowSize': 0,
        'totalPages': 0,
      };

      final entity = FeedPageDto.fromJson(json).toEntity();

      expect(entity.isEmpty, isTrue);
      expect(entity.hasMore, isFalse);
      expect(entity.items, isEmpty);
    });
  });
}
