import 'package:flutter_test/flutter_test.dart';
import 'package:rewit_mobile/features/discussions/data/models/discussion_models.dart';
import 'package:rewit_mobile/features/discussions/domain/entities/discussion_entities.dart';

void main() {
  group('Discussion Parsing & Domain Conversion Tests', () {
    test('deserializa página completa com comentário raiz e respostas embutidas', () {
      final json = {
        'content': [
          {
            'id': 'disc-root-1',
            'reviewId': 'rev-100',
            'parentId': null,
            'state': 'VISIBLE',
            'content': 'Comentário público muito bom!',
            'author': {
              'id': 'usr-1',
              'handle': 'marcos',
              'displayName': 'Marcos Lima',
              'avatarUrl': 'https://example.com/avatar.jpg',
            },
            'isFromOwner': true,
            'createdAt': '2026-10-06T18:00:00Z',
            'canReply': true,
            'canDelete': true,
            'replies': [
              {
                'id': 'disc-reply-1',
                'reviewId': 'rev-100',
                'parentId': 'disc-root-1',
                'state': 'VISIBLE',
                'content': 'Concordo plenamente!',
                'author': {
                  'id': 'usr-2',
                  'handle': 'ana',
                  'displayName': 'Ana Costa',
                  'avatarUrl': null,
                },
                'isFromOwner': false,
                'createdAt': '2026-10-06T18:30:00Z',
                'canReply': false,
                'canDelete': false,
              }
            ],
            'replyCount': 1,
            'hasMoreReplies': false,
          }
        ],
        'pageNumber': 0,
        'pageSize': 20,
        'totalElements': 1,
        'totalPages': 1,
        'isLast': true,
      };

      final dto = DiscussionPageDto.fromJson(json);
      final entity = dto.toEntity();

      expect(entity.pageNumber, 0);
      expect(entity.pageSize, 20);
      expect(entity.totalElements, 1);
      expect(entity.isLast, isTrue);
      expect(entity.content.length, 1);

      final thread = entity.content.first;
      expect(thread.id, 'disc-root-1');
      expect(thread.state, DiscussionViewState.visible);
      expect(thread.content, 'Comentário público muito bom!');
      expect(thread.author?.displayName, 'Marcos Lima');
      expect(thread.isFromOwner, isTrue);
      expect(thread.canReply, isTrue);
      expect(thread.canDelete, isTrue);
      expect(thread.replyCount, 1);
      expect(thread.hasMoreReplies, isFalse);

      expect(thread.replies.length, 1);
      final reply = thread.replies.first;
      expect(reply.id, 'disc-reply-1');
      expect(reply.parentId, 'disc-root-1');
      expect(reply.state, DiscussionViewState.visible);
      expect(reply.content, 'Concordo plenamente!');
      expect(reply.author?.handle, 'ana');
      expect(reply.isFromOwner, isFalse);
      expect(reply.canReply, isFalse);
      expect(reply.canDelete, isFalse);
    });

    test('deserializa comentário com estado REMOVED ocultando autor e conteúdo', () {
      final json = {
        'id': 'disc-root-removed',
        'reviewId': 'rev-100',
        'parentId': null,
        'state': 'REMOVED',
        'content': null,
        'author': null,
        'isFromOwner': false,
        'createdAt': '2026-10-06T19:00:00Z',
        'canReply': false,
        'canDelete': false,
        'replies': [],
        'replyCount': 0,
        'hasMoreReplies': false,
      };

      final dto = DiscussionThreadDto.fromJson(json);
      final thread = dto.toEntity();

      expect(thread.state, DiscussionViewState.removed);
      expect(thread.isRemoved, isTrue);
      expect(thread.content, isNull);
      expect(thread.author, isNull);
      expect(thread.canDelete, isFalse);
      expect(thread.canReply, isFalse);
    });

    test('deserializa comentário com estado PENDING_REVIEW visível para o autor', () {
      final json = {
        'id': 'disc-root-pending',
        'reviewId': 'rev-100',
        'parentId': null,
        'state': 'PENDING_REVIEW',
        'content': 'Comentário que entrou em análise',
        'author': {
          'id': 'usr-author',
          'handle': 'autor',
          'displayName': 'Meu Nome',
          'avatarUrl': null,
        },
        'isFromOwner': false,
        'createdAt': '2026-10-06T19:30:00Z',
        'canReply': false,
        'canDelete': false,
        'replies': [],
        'replyCount': 0,
        'hasMoreReplies': false,
      };

      final dto = DiscussionThreadDto.fromJson(json);
      final thread = dto.toEntity();

      expect(thread.state, DiscussionViewState.pendingReview);
      expect(thread.isPendingReview, isTrue);
      expect(thread.content, 'Comentário que entrou em análise');
      expect(thread.author?.displayName, 'Meu Nome');
      expect(thread.canReply, isFalse);
      expect(thread.canDelete, isFalse);
    });

    test('deserializa confirmação genérica de denúncia e DTO de criação', () {
      final reportJson = {
        'status': 'RECEIVED',
        'message': 'Denúncia recebida. Obrigado por ajudar a manter a comunidade segura.',
      };
      final reportDto = DiscussionReportReceiptDto.fromJson(reportJson);
      expect(reportDto.status, 'RECEIVED');
      expect(reportDto.message, 'Denúncia recebida. Obrigado por ajudar a manter a comunidade segura.');

      final createJson = {
        'id': 'disc-created-1',
        'reviewId': 'rev-200',
        'parentId': null,
        'content': 'Novo comentário publicado',
        'isFromOwner': false,
        'status': 'ACTIVE',
        'createdAt': '2026-10-06T20:00:00Z',
      };
      final createdDto = CreatedDiscussionDto.fromJson(createJson);
      final createdItem = createdDto.toEntity();
      expect(createdItem.id, 'disc-created-1');
      expect(createdItem.content, 'Novo comentário publicado');
      expect(createdItem.state, DiscussionViewState.visible);
      expect(createdItem.canReply, isTrue);
      expect(createdItem.canDelete, isTrue);
    });
  });
}
