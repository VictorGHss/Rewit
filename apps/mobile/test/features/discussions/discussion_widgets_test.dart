import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rewit_mobile/app/theme/app_theme.dart';
import 'package:rewit_mobile/features/discussions/domain/entities/discussion_entities.dart';
import 'package:rewit_mobile/features/discussions/presentation/widgets/create_discussion_form.dart';
import 'package:rewit_mobile/features/discussions/presentation/widgets/discussion_item_widget.dart';
import 'package:rewit_mobile/features/discussions/presentation/widgets/discussion_thread_widget.dart';

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
  });
}
