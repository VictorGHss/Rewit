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

    test('deve deserializar targetType (PLACE, PRODUCT, SERVICE) e mapear para FeedTarget com flags', () {
      final json = {
        'items': [
          {
            'id': 'rev-types-1',
            'author': {
              'displayName': 'Avaliador',
              'isAnonymous': false,
            },
            'experienceText': 'Avaliando múltiplos tipos de alvos.',
            'status': 'ACTIVE',
            'visibility': 'PUBLIC',
            'isAnonymous': false,
            'isVerifiedOnSite': false,
            'helpfulCount': 0,
            'isHelpfulByMe': false,
            'targets': [
              {
                'id': 't-place',
                'targetId': 'place-uuid-1',
                'targetType': 'PLACE',
                'rating': 4.5,
              },
              {
                'id': 't-product',
                'targetId': 'prod-uuid-2',
                'type': 'PRODUCT', // chave alternativa comum em DTOs
                'rating': 4.0,
              },
              {
                'id': 't-service',
                'targetId': 'serv-uuid-3',
                'targetType': 'SERVICE',
                'rating': 5.0,
              },
              {
                'id': 't-unknown',
                'targetId': 'unk-uuid-4',
                'rating': 3.5,
              },
            ],
            'createdAt': '2026-10-06T10:00:00Z',
          }
        ],
        'page': 0,
        'size': 10,
        'windowSize': 1,
        'totalPages': 1,
      };

      final entity = FeedPageDto.fromJson(json).toEntity();
      final targets = entity.items.first.targets;

      expect(targets.length, 4);

      // PLACE
      expect(targets[0].targetType, 'PLACE');
      expect(targets[0].isPlace, isTrue);
      expect(targets[0].isProduct, isFalse);
      expect(targets[0].isService, isFalse);

      // PRODUCT
      expect(targets[1].targetType, 'PRODUCT');
      expect(targets[1].isPlace, isFalse);
      expect(targets[1].isProduct, isTrue);
      expect(targets[1].isService, isFalse);

      // SERVICE
      expect(targets[2].targetType, 'SERVICE');
      expect(targets[2].isPlace, isFalse);
      expect(targets[2].isProduct, isFalse);
      expect(targets[2].isService, isTrue);

      // UNKNOWN
      expect(targets[3].targetType, isNull);
      expect(targets[3].isPlace, isFalse);
      expect(targets[3].isProduct, isFalse);
      expect(targets[3].isService, isFalse);
    });

    test('deve deserializar isMine contextual e manter nulo quando ausente', () {
      Map<String, dynamic> reviewJson({Object? isMine, bool includeIsMine = true}) => {
            'id': 'rev-mine',
            'author': {'id': null, 'handle': null, 'displayName': 'Anônimo', 'isAnonymous': true},
            'isAnonymous': true,
            'visibility': 'PUBLIC',
            'status': 'ACTIVE',
            'createdAt': '2026-10-08T10:00:00Z',
            if (includeIsMine) 'isMine': isMine,
          };

      final mine = FeedReviewDto.fromJson(reviewJson(isMine: true)).toEntity();
      expect(mine.isMine, isTrue);
      expect(mine.author.id, isNull);

      expect(FeedReviewDto.fromJson(reviewJson(isMine: false)).toEntity().isMine, isFalse);
      expect(FeedReviewDto.fromJson(reviewJson(includeIsMine: false)).toEntity().isMine, isNull);
    });
  });

  group('Feed V1 (Following) and Envelope Interoperability', () {
    test('deve deserializar envelope completo de Feed V1 (content, pageNumber, pageSize, totalElements, totalPages, isLast)', () {
      final json = {
        'content': [
          {
            'id': 'a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d',
            'author': {
              'id': 'user-v1-uuid',
              'handle': 'carlos',
              'displayName': 'Carlos Silva',
              'avatarUrl': 'https://cdn.rewit.com/avatars/carlos.png',
              'isAnonymous': false,
            },
            'contextPlaceId': 'place-uuid-123',
            'experienceText': 'Ótima experiência no restaurante italiano!',
            'status': 'ACTIVE',
            'visibility': 'PUBLIC',
            'isAnonymous': false,
            'isVerifiedOnSite': true,
            'helpfulCount': 12,
            'isHelpfulByMe': false,
            'isMine': true,
            'targets': [
              {
                'id': 'tgt-v1-1',
                'targetId': 'target-place-uuid',
                'targetType': 'PLACE',
                'targetName': 'Cantina do Nono',
                'rating': 4.8,
                'specificComment': 'Massa artesanal excelente',
                'createdAt': '2026-11-04T01:00:00Z',
              }
            ],
            'createdAt': '2026-11-04T01:00:00Z',
            'updatedAt': '2026-11-04T01:10:00Z',
          }
        ],
        'pageNumber': 1,
        'pageSize': 10,
        'totalElements': 25,
        'totalPages': 3,
        'isLast': false,
      };

      final dto = FeedPageDto.fromJson(json);
      final entity = dto.toEntity();

      expect(dto.page, 1);
      expect(dto.size, 10);
      expect(dto.totalElements, 25);
      expect(dto.totalPages, 3);
      expect(dto.isLast, isFalse);
      expect(dto.windowSize, 25); // derivado de totalElements para V1
      expect(dto.items.length, 1);

      expect(entity.page, 1);
      expect(entity.size, 10);
      expect(entity.totalElements, 25);
      expect(entity.totalPages, 3);
      expect(entity.isLast, isFalse);
      expect(entity.hasMore, isTrue);
      expect(entity.isEmpty, isFalse);
      expect(entity.items.length, 1);

      final review = entity.items.first;
      expect(review.id, 'a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d');
      expect(review.author.displayName, 'Carlos Silva');
      expect(review.author.handle, 'carlos');
      expect(review.author.displayHandle, '@carlos');
      expect(review.isVerifiedOnSite, isTrue);
      expect(review.helpfulCount, 12);
      expect(review.isMine, isTrue);
      expect(review.targets.length, 1);
      expect(review.targets.first.targetName, 'Cantina do Nono');
      expect(review.targets.first.rating, 4.8);
    });

    test('deve deserializar envelope de Feed V1 vazio (content vazio e isLast true)', () {
      final json = {
        'content': [],
        'pageNumber': 0,
        'pageSize': 10,
        'totalElements': 0,
        'totalPages': 0,
        'isLast': true,
      };

      final dto = FeedPageDto.fromJson(json);
      final entity = dto.toEntity();

      expect(dto.items, isEmpty);
      expect(dto.page, 0);
      expect(dto.size, 10);
      expect(dto.totalElements, 0);
      expect(dto.totalPages, 0);
      expect(dto.isLast, isTrue);

      expect(entity.isEmpty, isTrue);
      expect(entity.hasMore, isFalse);
      expect(entity.items, isEmpty);
    });

    test('deve converter com segurança metadados numéricos de paginação de múltiplos tipos (int, double, string)', () {
      // V1 com tipos mistos (double e string numérica)
      final jsonV1 = {
        'content': [],
        'pageNumber': 2.0,
        'pageSize': '15',
        'totalElements': 45.0,
        'totalPages': '3',
        'isLast': false,
      };

      final dtoV1 = FeedPageDto.fromJson(jsonV1);
      expect(dtoV1.page, 2);
      expect(dtoV1.size, 15);
      expect(dtoV1.totalElements, 45);
      expect(dtoV1.totalPages, 3);
      expect(dtoV1.isLast, isFalse);
      expect(dtoV1.toEntity().hasMore, isTrue);

      // V2 com tipos mistos
      final jsonV2 = {
        'items': [],
        'page': 1.0,
        'size': '20',
        'windowSize': 60.0,
        'totalPages': '4',
      };

      final dtoV2 = FeedPageDto.fromJson(jsonV2);
      expect(dtoV2.page, 1);
      expect(dtoV2.size, 20);
      expect(dtoV2.windowSize, 60);
      expect(dtoV2.totalPages, 4);
      expect(dtoV2.toEntity().hasMore, isTrue);
    });

    test('deve preservar campos opcionais e valores nulos do contrato em V1 e V2', () {
      final jsonMinimal = {
        'content': [
          {
            'id': 'rev-minimal',
            'author': {
              'id': null,
              'handle': null,
              'displayName': 'Anônimo',
              'avatarUrl': null,
              'isAnonymous': true,
            },
            'contextPlaceId': null,
            'experienceText': null,
            'status': 'ACTIVE',
            'visibility': 'PUBLIC',
            'isAnonymous': true,
            'isVerifiedOnSite': false,
            'helpfulCount': 0,
            'isHelpfulByMe': false,
            'isMine': null,
            'targets': [],
            'createdAt': '2026-11-04T02:00:00Z',
            'updatedAt': null,
          }
        ],
        'pageNumber': 0,
        'pageSize': 10,
        'totalElements': null,
        'totalPages': 1,
        'isLast': true,
      };

      final entity = FeedPageDto.fromJson(jsonMinimal).toEntity();
      expect(entity.items.length, 1);
      final review = entity.items.first;
      expect(review.contextPlaceId, isNull);
      expect(review.experienceText, isNull);
      expect(review.updatedAt, isNull);
      expect(review.isMine, isNull);
      expect(review.targets, isEmpty);
      expect(review.author.id, isNull);
      expect(entity.totalElements, isNull);
      expect(entity.isLast, isTrue);
      expect(entity.hasMore, isFalse);
    });

    test('deve lançar FormatException ao receber resposta estruturalmente inválida ou sem lista de itens', () {
      // 1. Objeto vazio sem 'items' nem 'content'
      expect(
        () => FeedPageDto.fromJson({}),
        throwsA(isA<FormatException>().having(
          (e) => e.message,
          'message',
          contains('Envelope de feed inválido: esperado "items" (V2) ou "content" (V1)'),
        )),
      );

      // 2. Resposta de erro do servidor
      expect(
        () => FeedPageDto.fromJson({'error': 'Internal server error', 'status': 500}),
        throwsA(isA<FormatException>()),
      );

      // 3. 'items' presente mas não é lista
      expect(
        () => FeedPageDto.fromJson({'items': 'not-a-list', 'page': 0}),
        throwsA(isA<FormatException>().having(
          (e) => e.message,
          'message',
          contains('deve ser uma lista'),
        )),
      );

      // 4. 'content' presente mas não é lista
      expect(
        () => FeedPageDto.fromJson({'content': 12345, 'pageNumber': 0}),
        throwsA(isA<FormatException>().having(
          (e) => e.message,
          'message',
          contains('deve ser uma lista'),
        )),
      );

      // 5. Elemento dentro da lista corrompido (não é Map)
      expect(
        () => FeedPageDto.fromJson({'items': ['item-invalido-string']}),
        throwsA(isA<FormatException>().having(
          (e) => e.message,
          'message',
          contains('Elemento de avaliação inválido'),
        )),
      );
    });
  });
}
