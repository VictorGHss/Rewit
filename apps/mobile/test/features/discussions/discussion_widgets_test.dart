import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rewit_mobile/app/theme/app_theme.dart';
import 'package:rewit_mobile/features/discussions/domain/entities/discussion_entities.dart';
import 'package:rewit_mobile/features/discussions/domain/repositories/discussion_repository.dart';
import 'package:rewit_mobile/features/discussions/presentation/state/discussion_notifier.dart';
import 'package:rewit_mobile/features/discussions/presentation/widgets/create_discussion_form.dart';
import 'package:rewit_mobile/features/discussions/presentation/widgets/discussion_item_widget.dart';
import 'package:rewit_mobile/features/discussions/presentation/widgets/discussion_thread_widget.dart';
import 'package:rewit_mobile/features/discussions/presentation/widgets/discussions_section.dart';

void main() {
  group('Discussion Widgets Tests', () {
    testWidgets('DiscussionItemWidget renderiza comentário VISIBLE com autor, ações e conteúdo', (tester) async {
      bool replyTapped = false;
      bool deleteTapped = false;
      bool reportTapped = false;

      final item = DiscussionItem(
        id: 'd-1',
        reviewId: 'r-1',
        state: DiscussionViewState.visible,
        content: 'Excelente experiência gastronômica!',
        author: const DiscussionAuthor(
          id: 'u-1',
          handle: 'carlos',
          displayName: 'Carlos Eduardo',
        ),
        isFromOwner: true,
        createdAt: DateTime(2026, 10, 6, 14, 0),
        canReply: true,
        canDelete: true,
      );

      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.lightTheme,
          home: Scaffold(
            body: DiscussionItemWidget(
              item: item,
              isRoot: true,
              onReply: () => replyTapped = true,
              onDelete: () => deleteTapped = true,
              onReport: () => reportTapped = true,
            ),
          ),
        ),
      );

      expect(find.text('Carlos Eduardo'), findsOneWidget);
      expect(find.text('@carlos'), findsOneWidget);
      expect(find.text('Autor da avaliação'), findsOneWidget);
      expect(find.text('Excelente experiência gastronômica!'), findsOneWidget);
      expect(find.text('Responder'), findsOneWidget);
      expect(find.text('Excluir'), findsOneWidget);
      expect(find.text('Denunciar'), findsOneWidget);

      await tester.tap(find.text('Responder'));
      expect(replyTapped, isTrue);

      await tester.tap(find.text('Excluir'));
      expect(deleteTapped, isTrue);

      await tester.tap(find.text('Denunciar'));
      expect(reportTapped, isTrue);
    });

    testWidgets('DiscussionItemWidget renderiza REMOVED ocultando autor/conteúdo e mostrando placeholder acessível', (tester) async {
      final item = DiscussionItem(
        id: 'd-removed',
        reviewId: 'r-1',
        state: DiscussionViewState.removed,
        content: null,
        author: null,
        isFromOwner: false,
        createdAt: DateTime(2026, 10, 6, 14, 0),
        canReply: false,
        canDelete: false,
      );

      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.lightTheme,
          home: Scaffold(
            body: DiscussionItemWidget(
              item: item,
              isRoot: true,
              onReply: () {},
              onDelete: () {},
              onReport: () {},
            ),
          ),
        ),
      );

      expect(find.text('Comentário removido'), findsOneWidget);
      expect(find.text('Responder'), findsNothing);
      expect(find.text('Excluir'), findsNothing);
      expect(find.text('Denunciar'), findsNothing);
    });

    testWidgets('DiscussionItemWidget renderiza PENDING_REVIEW com banner de quarentena acessível e sem botões de ação', (tester) async {
      final item = DiscussionItem(
        id: 'd-pending',
        reviewId: 'r-1',
        state: DiscussionViewState.pendingReview,
        content: 'Meu comentário sob análise',
        author: const DiscussionAuthor(
          id: 'u-1',
          handle: 'autor',
          displayName: 'Autor Original',
        ),
        isFromOwner: false,
        createdAt: DateTime(2026, 10, 6, 14, 0),
        canReply: false,
        canDelete: false,
      );

      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.lightTheme,
          home: Scaffold(
            body: DiscussionItemWidget(
              item: item,
              isRoot: true,
              onReply: () {},
              onDelete: () {},
              onReport: () {},
            ),
          ),
        ),
      );

      expect(find.text('Em análise pela moderação (visível apenas para você)'), findsOneWidget);
      expect(find.text('Meu comentário sob análise'), findsOneWidget);
      expect(find.text('Responder'), findsNothing);
      expect(find.text('Excluir'), findsNothing);
    });

    testWidgets('DiscussionItemWidget omite botão de exclusão quando canDelete=false', (tester) async {
      final item = DiscussionItem(
        id: 'd-viewonly',
        reviewId: 'r-1',
        state: DiscussionViewState.visible,
        content: 'Comentário de outro usuário',
        author: const DiscussionAuthor(displayName: 'Outro Usuário'),
        isFromOwner: false,
        createdAt: DateTime(2026, 10, 6, 14, 0),
        canReply: true,
        canDelete: false,
      );

      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.lightTheme,
          home: Scaffold(
            body: DiscussionItemWidget(
              item: item,
              isRoot: true,
              onReply: () {},
              onDelete: () {},
              onReport: () {},
            ),
          ),
        ),
      );

      expect(find.text('Excluir'), findsNothing);
      expect(find.text('Responder'), findsOneWidget);
    });

    testWidgets('DiscussionThreadWidget renderiza raiz e respostas embutidas com botão de carregar mais', (tester) async {
      bool loadMoreRepliesTapped = false;

      final thread = DiscussionThread(
        id: 'root-1',
        reviewId: 'r-1',
        state: DiscussionViewState.visible,
        content: 'Comentário raiz de discussão',
        author: const DiscussionAuthor(displayName: 'Raiz Author'),
        isFromOwner: true,
        createdAt: DateTime(2026, 10, 6, 12, 0),
        canReply: true,
        canDelete: true,
        replies: [
          DiscussionItem(
            id: 'rep-1',
            reviewId: 'r-1',
            parentId: 'root-1',
            state: DiscussionViewState.visible,
            content: 'Primeira resposta embutida',
            author: const DiscussionAuthor(displayName: 'Resposta Author'),
            isFromOwner: false,
            createdAt: DateTime(2026, 10, 6, 12, 10),
            canReply: false,
            canDelete: false,
          ),
        ],
        replyCount: 3,
        hasMoreReplies: true,
      );

      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.lightTheme,
          home: Scaffold(
            body: DiscussionThreadWidget(
              thread: thread,
              onLoadMoreReplies: () => loadMoreRepliesTapped = true,
            ),
          ),
        ),
      );

      expect(find.text('Comentário raiz de discussão'), findsOneWidget);
      expect(find.text('Primeira resposta embutida'), findsOneWidget);
      expect(find.text('Carregar mais respostas (2 restantes)'), findsOneWidget);

      await tester.tap(find.text('Carregar mais respostas (2 restantes)'));
      expect(loadMoreRepliesTapped, isTrue);
    });

    testWidgets('CreateDiscussionForm renderiza modo resposta quando parentId é fornecido', (tester) async {
      bool cancelTapped = false;

      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.lightTheme,
          home: Scaffold(
            body: CreateDiscussionForm(
              replyingToAuthorName: 'Maria Silva',
              replyingToParentId: 'root-1',
              onCancelReply: () => cancelTapped = true,
              onSubmit: (content, parentId) async {},
            ),
          ),
        ),
      );

      expect(find.text('Respondendo a Maria Silva'), findsOneWidget);
      await tester.tap(find.byIcon(Icons.close));
      expect(cancelTapped, isTrue);
    });

    testWidgets('DiscussionItemWidget renderiza com estilo destacado quando isHighlighted é true', (tester) async {
      final item = DiscussionItem(
        id: 'd-hl',
        reviewId: 'r-1',
        state: DiscussionViewState.visible,
        content: 'Comentário destacado',
        author: const DiscussionAuthor(displayName: 'Autor Teste'),
        isFromOwner: false,
        createdAt: DateTime(2026, 10, 9, 10, 0),
        canReply: true,
        canDelete: false,
      );

      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.lightTheme,
          home: Scaffold(
            body: DiscussionItemWidget(
              item: item,
              isHighlighted: true,
            ),
          ),
        ),
      );

      expect(find.text('Comentário destacado'), findsOneWidget);
      final itemFinder = find.byType(DiscussionItemWidget);
      final animatedContainer = tester.widget<AnimatedContainer>(
        find.descendant(of: itemFinder, matching: find.byType(AnimatedContainer)).first,
      );
      final decoration = animatedContainer.decoration as BoxDecoration;
      expect(decoration.border, isNotNull);
      expect(decoration.border!.top.width, 1.5);
    });

    testWidgets('DiscussionsSection destaca comentário raiz quando targetDiscussionId é raiz (C5.12)', (tester) async {
      final repo = _MockDiscussionRepository();
      repo.threads = [
        DiscussionThread(
          id: 'root-target',
          reviewId: 'r-1',
          state: DiscussionViewState.visible,
          content: 'Discussão alvo raiz',
          author: const DiscussionAuthor(displayName: 'Autor Alvo'),
          isFromOwner: false,
          createdAt: DateTime(2026, 10, 9, 10, 0),
          canReply: true,
          canDelete: false,
          replies: const [],
          replyCount: 0,
          hasMoreReplies: false,
        ),
      ];

      final notifier = DiscussionNotifier(repository: repo);
      await notifier.loadDiscussions('r-1');

      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.lightTheme,
          home: Scaffold(
            body: SingleChildScrollView(
              child: DiscussionsSection(
                reviewId: 'r-1',
                notifier: notifier,
                targetDiscussionId: 'root-target',
                isReplyTarget: false,
              ),
            ),
          ),
        ),
      );
      await tester.pump();

      expect(find.text('Discussão alvo raiz'), findsOneWidget);
      final itemFinder = find.byType(DiscussionItemWidget);
      final animatedContainer = tester.widget<AnimatedContainer>(
        find.descendant(of: itemFinder, matching: find.byType(AnimatedContainer)).first,
      );
      final decoration = animatedContainer.decoration as BoxDecoration;
      expect(decoration.border!.top.width, 1.5);

      // Avança o tempo para confirmar que o timer de highlight expira sem erro
      await tester.pump(const Duration(seconds: 3));
      final normalContainer = tester.widget<AnimatedContainer>(
        find.descendant(of: itemFinder, matching: find.byType(AnimatedContainer)).first,
      );
      final normalDecoration = normalContainer.decoration as BoxDecoration;
      expect(normalDecoration.border!.top.width, 1.0);
    });

    testWidgets('DiscussionsSection destaca resposta quando targetDiscussionId é resposta (C5.12)', (tester) async {
      final repo = _MockDiscussionRepository();
      repo.threads = [
        DiscussionThread(
          id: 'root-1',
          reviewId: 'r-1',
          state: DiscussionViewState.visible,
          content: 'Comentário raiz',
          author: const DiscussionAuthor(displayName: 'Autor Raiz'),
          isFromOwner: false,
          createdAt: DateTime(2026, 10, 9, 10, 0),
          canReply: true,
          canDelete: false,
          replies: [
            DiscussionItem(
              id: 'reply-target',
              reviewId: 'r-1',
              parentId: 'root-1',
              state: DiscussionViewState.visible,
              content: 'Resposta alvo contextual',
              author: const DiscussionAuthor(displayName: 'Autor Resposta'),
              isFromOwner: false,
              createdAt: DateTime(2026, 10, 9, 10, 5),
              canReply: false,
              canDelete: false,
            ),
          ],
          replyCount: 1,
          hasMoreReplies: false,
        ),
      ];

      final notifier = DiscussionNotifier(repository: repo);
      await notifier.loadDiscussions('r-1');

      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.lightTheme,
          home: Scaffold(
            body: SingleChildScrollView(
              child: DiscussionsSection(
                reviewId: 'r-1',
                notifier: notifier,
                targetDiscussionId: 'reply-target',
                isReplyTarget: true,
              ),
            ),
          ),
        ),
      );
      await tester.pump();

      expect(find.text('Resposta alvo contextual'), findsOneWidget);
      final itemWidgets = tester.widgetList<DiscussionItemWidget>(find.byType(DiscussionItemWidget)).toList();
      expect(itemWidgets.length, 2);
      expect(itemWidgets[0].isHighlighted, isFalse);
      expect(itemWidgets[1].isHighlighted, isTrue);

      await tester.pump(const Duration(seconds: 3));
    });

    testWidgets('DiscussionsSection não falha e não bloqueia se targetDiscussionId não estiver carregado (C5.12)', (tester) async {
      final repo = _MockDiscussionRepository();
      repo.threads = [
        DiscussionThread(
          id: 'root-1',
          reviewId: 'r-1',
          state: DiscussionViewState.visible,
          content: 'Comentário existente',
          author: const DiscussionAuthor(displayName: 'Autor 1'),
          isFromOwner: false,
          createdAt: DateTime(2026, 10, 9, 10, 0),
          canReply: true,
          canDelete: false,
          replies: const [],
          replyCount: 0,
          hasMoreReplies: false,
        ),
      ];

      final notifier = DiscussionNotifier(repository: repo);
      await notifier.loadDiscussions('r-1');

      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.lightTheme,
          home: Scaffold(
            body: SingleChildScrollView(
              child: DiscussionsSection(
                reviewId: 'r-1',
                notifier: notifier,
                targetDiscussionId: 'root-inexistente',
                isReplyTarget: false,
              ),
            ),
          ),
        ),
      );
      await tester.pump();

      expect(find.text('Comentário existente'), findsOneWidget);
      final animatedContainer = tester.widget<AnimatedContainer>(find.byType(AnimatedContainer).first);
      final decoration = animatedContainer.decoration as BoxDecoration;
      expect(decoration.border!.top.width, 1.0);
    });

    testWidgets('DiscussionsSection destaca comentário raiz encontrado após carregar outra página (loadMore)', (tester) async {
      final repo = _MockDiscussionRepository();
      repo.pages = {
        0: [
          DiscussionThread(
            id: 'root-page-1',
            reviewId: 'r-1',
            state: DiscussionViewState.visible,
            content: 'Discussão da primeira página',
            author: const DiscussionAuthor(displayName: 'Autor 1'),
            isFromOwner: false,
            createdAt: DateTime(2026, 10, 9, 10, 0),
            canReply: true,
            canDelete: false,
            replies: const [],
            replyCount: 0,
            hasMoreReplies: false,
          ),
        ],
        1: [
          DiscussionThread(
            id: 'root-page-2-target',
            reviewId: 'r-1',
            state: DiscussionViewState.visible,
            content: 'Discussão alvo da segunda página',
            author: const DiscussionAuthor(displayName: 'Autor 2'),
            isFromOwner: false,
            createdAt: DateTime(2026, 10, 9, 10, 1),
            canReply: true,
            canDelete: false,
            replies: const [],
            replyCount: 0,
            hasMoreReplies: false,
          ),
        ],
      };

      final notifier = DiscussionNotifier(repository: repo);
      await notifier.loadDiscussions('r-1');

      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.lightTheme,
          home: Scaffold(
            body: SingleChildScrollView(
              child: DiscussionsSection(
                reviewId: 'r-1',
                notifier: notifier,
                targetDiscussionId: 'root-page-2-target',
                isReplyTarget: false,
              ),
            ),
          ),
        ),
      );
      await tester.pump();

      // Página 1 não contém o alvo; o comentário existente não deve estar destacado
      expect(find.text('Discussão da primeira página'), findsOneWidget);
      expect(find.text('Discussão alvo da segunda página'), findsNothing);
      final firstItem = tester.widget<AnimatedContainer>(find.byType(AnimatedContainer).first);
      expect((firstItem.decoration as BoxDecoration).border!.top.width, 1.0);

      // Clica em carregar mais comentários para buscar a página 2
      expect(find.text('Carregar mais comentários'), findsOneWidget);
      await tester.tap(find.text('Carregar mais comentários'));
      await tester.pumpAndSettle();

      // Alvo agora está visível e destacado!
      expect(find.text('Discussão alvo da segunda página'), findsOneWidget);
      final itemWidgets = tester.widgetList<DiscussionItemWidget>(find.byType(DiscussionItemWidget)).toList();
      expect(itemWidgets.length, 2);
      expect(itemWidgets[0].isHighlighted, isFalse);
      expect(itemWidgets[1].isHighlighted, isTrue);

      await tester.pump(const Duration(seconds: 3));
    });

    testWidgets('DiscussionsSection destaca resposta quando carregada via loadMoreReplies', (tester) async {
      final repo = _MockDiscussionRepository();
      repo.threads = [
        DiscussionThread(
          id: 'root-1',
          reviewId: 'r-1',
          state: DiscussionViewState.visible,
          content: 'Comentário raiz sem respostas iniciais',
          author: const DiscussionAuthor(displayName: 'Autor Raiz'),
          isFromOwner: false,
          createdAt: DateTime(2026, 10, 9, 10, 0),
          canReply: true,
          canDelete: false,
          replies: const [],
          replyCount: 1,
          hasMoreReplies: true,
        ),
      ];
      repo.threadReplies = {
        'root-1': [
          DiscussionItem(
            id: 'reply-target-loaded-later',
            reviewId: 'r-1',
            parentId: 'root-1',
            state: DiscussionViewState.visible,
            content: 'Resposta encontrada após carregar replies',
            author: const DiscussionAuthor(displayName: 'Autor Resposta'),
            isFromOwner: false,
            createdAt: DateTime(2026, 10, 9, 10, 5),
            canReply: false,
            canDelete: false,
          ),
        ],
      };

      final notifier = DiscussionNotifier(repository: repo);
      await notifier.loadDiscussions('r-1');

      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.lightTheme,
          home: Scaffold(
            body: SingleChildScrollView(
              child: DiscussionsSection(
                reviewId: 'r-1',
                notifier: notifier,
                targetDiscussionId: 'reply-target-loaded-later',
                isReplyTarget: true,
              ),
            ),
          ),
        ),
      );
      await tester.pump();

      // Resposta ainda não está na árvore
      expect(find.text('Resposta encontrada após carregar replies'), findsNothing);

      // Carrega mais respostas
      expect(find.text('Carregar mais respostas (1 restantes)'), findsOneWidget);
      await tester.tap(find.text('Carregar mais respostas (1 restantes)'));
      await tester.pumpAndSettle();

      // Resposta agora foi carregada e destacada!
      expect(find.text('Resposta encontrada após carregar replies'), findsOneWidget);
      final itemWidgets = tester.widgetList<DiscussionItemWidget>(find.byType(DiscussionItemWidget)).toList();
      expect(itemWidgets.length, 2);
      expect(itemWidgets[0].isHighlighted, isFalse);
      expect(itemWidgets[1].isHighlighted, isTrue);

      await tester.pump(const Duration(seconds: 3));
    });

    testWidgets('DiscussionsSection não repete destaque após o alvo já ter sido tratado', (tester) async {
      final repo = _MockDiscussionRepository();
      repo.threads = [
        DiscussionThread(
          id: 'root-target-once',
          reviewId: 'r-1',
          state: DiscussionViewState.visible,
          content: 'Discussão alvo único',
          author: const DiscussionAuthor(displayName: 'Autor Único'),
          isFromOwner: false,
          createdAt: DateTime(2026, 10, 9, 10, 0),
          canReply: true,
          canDelete: false,
          replies: const [],
          replyCount: 0,
          hasMoreReplies: false,
        ),
      ];

      final notifier = DiscussionNotifier(repository: repo);
      await notifier.loadDiscussions('r-1');

      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.lightTheme,
          home: Scaffold(
            body: SingleChildScrollView(
              child: DiscussionsSection(
                reviewId: 'r-1',
                notifier: notifier,
                targetDiscussionId: 'root-target-once',
                isReplyTarget: false,
              ),
            ),
          ),
        ),
      );
      await tester.pump();

      // Destaque inicial ativo
      final itemFinder = find.byType(DiscussionItemWidget);
      var container = tester.widget<AnimatedContainer>(
        find.descendant(of: itemFinder, matching: find.byType(AnimatedContainer)).first,
      );
      expect((container.decoration as BoxDecoration).border!.top.width, 1.5);

      // Aguarda expirar timer de 2s
      await tester.pump(const Duration(seconds: 3));
      container = tester.widget<AnimatedContainer>(
        find.descendant(of: itemFinder, matching: find.byType(AnimatedContainer)).first,
      );
      expect((container.decoration as BoxDecoration).border!.top.width, 1.0);

      // Dispara nova notificação no notifier (refresh)
      await notifier.refresh('r-1');
      await tester.pumpAndSettle();

      // O alvo já foi tratado e NÃO deve ser destacado novamente
      container = tester.widget<AnimatedContainer>(
        find.descendant(of: itemFinder, matching: find.byType(AnimatedContainer)).first,
      );
      expect((container.decoration as BoxDecoration).border!.top.width, 1.0);
    });
  });
}

class _MockDiscussionRepository implements DiscussionRepository {
  List<DiscussionThread> threads = [];
  Map<int, List<DiscussionThread>> pages = {};
  Map<String, List<DiscussionItem>> threadReplies = {};

  @override
  Future<DiscussionPage> getDiscussions(String reviewId, {int page = 0, int size = 20}) async {
    if (pages.containsKey(page)) {
      final pageContent = pages[page]!;
      return DiscussionPage(
        content: pageContent,
        pageNumber: page,
        pageSize: size,
        totalElements: 40,
        totalPages: 2,
        isLast: page >= 1,
      );
    }
    return DiscussionPage(
      content: threads,
      pageNumber: page,
      pageSize: size,
      totalElements: threads.length,
      totalPages: 1,
      isLast: true,
    );
  }

  @override
  Future<DiscussionRepliesPage> getReplies(String discussionId, {int page = 0, int size = 20}) async {
    final replies = threadReplies[discussionId] ?? [];
    return DiscussionRepliesPage(
      content: replies,
      pageNumber: page,
      pageSize: size,
      totalElements: replies.length,
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
    throw UnimplementedError();
  }

  @override
  Future<void> deleteDiscussion(String discussionId) async {}

  @override
  Future<String> reportDiscussion({
    required String discussionId,
    required ReportReason reason,
    String? detail,
  }) async {
    return 'report-1';
  }
}
