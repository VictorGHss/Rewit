import 'package:flutter_test/flutter_test.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/features/discussions/domain/entities/discussion_entities.dart';
import 'package:rewit_mobile/features/discussions/domain/repositories/discussion_repository.dart';
import 'package:rewit_mobile/features/discussions/presentation/state/discussion_notifier.dart';

class FakeDiscussionRepository implements DiscussionRepository {
  List<DiscussionThread> mockThreads = [];
  List<DiscussionItem> mockReplies = [];
  bool shouldThrowOnLoad = false;
  bool shouldThrowConflictOnDelete = false;
  bool deleteCalled = false;
  String? lastReportedId;
  ReportReason? lastReportReason;

  @override
  Future<DiscussionPage> getDiscussions(String reviewId, {int page = 0, int size = 20}) async {
    if (shouldThrowOnLoad) {
      throw const ApiException(
        ProblemDetail(
          type: 'about:blank',
          title: 'Erro de Servidor',
          status: 500,
          detail: 'Falha interna ao buscar discussões',
        ),
      );
    }
    return DiscussionPage(
      content: mockThreads,
      pageNumber: page,
      pageSize: size,
      totalElements: mockThreads.length,
      totalPages: 1,
      isLast: true,
    );
  }

  @override
  Future<DiscussionRepliesPage> getReplies(String discussionId, {int page = 0, int size = 20}) async {
    return DiscussionRepliesPage(
      content: mockReplies,
      pageNumber: page,
      pageSize: size,
      totalElements: mockReplies.length,
      totalPages: 1,
      isLast: true,
    );
  }

  @override
  Future<DiscussionItem> createDiscussion({
    required String reviewId,
    required String content,
    String? parentId,
  }) async {
    final newItem = DiscussionItem(
      id: 'created-id-1',
      reviewId: reviewId,
      parentId: parentId,
      state: DiscussionViewState.visible,
      content: content,
      isFromOwner: false,
      createdAt: DateTime.now(),
      canReply: parentId == null,
      canDelete: true,
    );

    if (parentId == null) {
      mockThreads.add(
        DiscussionThread(
          id: newItem.id,
          reviewId: reviewId,
          state: DiscussionViewState.visible,
          content: content,
          isFromOwner: false,
          createdAt: DateTime.now(),
          canReply: true,
          canDelete: true,
          replies: [],
          replyCount: 0,
          hasMoreReplies: false,
        ),
      );
    }
    return newItem;
  }

  @override
  Future<void> deleteDiscussion(String discussionId) async {
    deleteCalled = true;
    if (shouldThrowConflictOnDelete) {
      throw const ApiException(
        ProblemDetail(
          type: 'about:blank',
          title: 'Conflito',
          status: 409,
          code: 'DISCUSSION_UNDER_REVIEW_MUTATION_DENIED',
          detail: 'Comentário em quarentena não pode sofrer mutação.',
        ),
      );
    }
    mockThreads.removeWhere((t) => t.id == discussionId);
  }

  @override
  Future<String> reportDiscussion({
    required String discussionId,
    required ReportReason reason,
    String? detail,
  }) async {
    lastReportedId = discussionId;
    lastReportReason = reason;
    return 'Denúncia recebida. Obrigado por ajudar a manter a comunidade segura.';
  }
}

void main() {
  group('DiscussionNotifier Unit Tests', () {
    late FakeDiscussionRepository repo;
    late DiscussionNotifier notifier;

    setUp(() {
      repo = FakeDiscussionRepository();
      notifier = DiscussionNotifier(repository: repo);
    });

    test('estado inicial é DiscussionInitial', () {
      expect(notifier.state, isA<DiscussionInitial>());
    });

    test('loadDiscussions com lista não vazia transiciona para DiscussionLoaded', () async {
      repo.mockThreads = [
        DiscussionThread(
          id: 't-1',
          reviewId: 'r-1',
          state: DiscussionViewState.visible,
          content: 'Conteúdo teste',
          isFromOwner: true,
          createdAt: DateTime.now(),
          canReply: true,
          canDelete: true,
          replies: [],
          replyCount: 0,
          hasMoreReplies: false,
        ),
      ];

      await notifier.loadDiscussions('r-1');
      expect(notifier.state, isA<DiscussionLoaded>());
      final loaded = notifier.state as DiscussionLoaded;
      expect(loaded.threads.length, 1);
      expect(loaded.threads.first.content, 'Conteúdo teste');
    });

    test('loadDiscussions vazio transiciona para DiscussionEmpty', () async {
      repo.mockThreads = [];
      await notifier.loadDiscussions('r-1');
      expect(notifier.state, isA<DiscussionEmpty>());
    });

    test('loadDiscussions com erro transiciona para DiscussionError', () async {
      repo.shouldThrowOnLoad = true;
      await notifier.loadDiscussions('r-1');
      expect(notifier.state, isA<DiscussionError>());
      final error = notifier.state as DiscussionError;
      expect(error.statusCode, 500);
      expect(error.message, 'Falha interna ao buscar discussões');
    });

    test('loadMoreReplies anexa respostas à thread correta', () async {
      repo.mockThreads = [
        DiscussionThread(
          id: 't-root',
          reviewId: 'r-1',
          state: DiscussionViewState.visible,
          content: 'Raiz',
          isFromOwner: false,
          createdAt: DateTime.now(),
          canReply: true,
          canDelete: false,
          replies: [],
          replyCount: 1,
          hasMoreReplies: true,
        ),
      ];
      repo.mockReplies = [
        DiscussionItem(
          id: 'rep-1',
          reviewId: 'r-1',
          parentId: 't-root',
          state: DiscussionViewState.visible,
          content: 'Nova resposta carregada',
          isFromOwner: false,
          createdAt: DateTime.now(),
          canReply: false,
          canDelete: false,
        ),
      ];

      await notifier.loadDiscussions('r-1');
      await notifier.loadMoreReplies('t-root');

      final loaded = notifier.state as DiscussionLoaded;
      final thread = loaded.threads.first;
      expect(thread.replies.length, 1);
      expect(thread.replies.first.id, 'rep-1');
    });

    test('createComment valida limite de conteúdo e rejeita texto vazio ou > 2000 chars', () async {
      expect(
        () => notifier.createComment(reviewId: 'r-1', content: '   '),
        throwsA(isA<ApiException>().having((e) => e.statusCode, 'statusCode', 400)),
      );

      final hugeText = 'a' * 2001;
      expect(
        () => notifier.createComment(reviewId: 'r-1', content: hugeText),
        throwsA(isA<ApiException>().having((e) => e.statusCode, 'statusCode', 400)),
      );
    });

    test('createComment válido adiciona comentário e atualiza estado', () async {
      await notifier.loadDiscussions('r-1');
      await notifier.createComment(reviewId: 'r-1', content: 'Comentário válido');

      expect(notifier.state, isA<DiscussionLoaded>());
      final loaded = notifier.state as DiscussionLoaded;
      expect(loaded.threads.any((t) => t.content == 'Comentário válido'), isTrue);
    });

    test('deleteComment em comentário PENDING_REVIEW sanitiza erro e oculta detalhes internos', () async {
      repo.shouldThrowConflictOnDelete = true;

      expect(
        () => notifier.deleteComment(reviewId: 'r-1', discussionId: 'd-pending'),
        throwsA(isA<ApiException>().having(
          (e) => e.detail,
          'detail',
          'Este comentário está em análise pela moderação e não pode ser alterado ou excluído no momento.',
        )),
      );
    });

    test('reportComment submete denúncia e retorna mensagem genérica padronizada', () async {
      final message = await notifier.reportComment(
        discussionId: 'd-spam',
        reason: ReportReason.spam,
        detail: 'Propaganda indevida',
      );

      expect(repo.lastReportedId, 'd-spam');
      expect(repo.lastReportReason, ReportReason.spam);
      expect(message, 'Denúncia recebida. Obrigado por ajudar a manter a comunidade segura.');
    });
  });
}
