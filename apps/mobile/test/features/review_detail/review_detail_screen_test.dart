import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rewit_mobile/app/theme/app_theme.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/features/discussions/domain/entities/discussion_entities.dart';
import 'package:rewit_mobile/features/discussions/domain/repositories/discussion_repository.dart';
import 'package:rewit_mobile/features/feed/domain/entities/feed_entities.dart';
import 'package:rewit_mobile/features/feed/domain/repositories/feed_repository.dart';
import 'package:rewit_mobile/features/feed/presentation/state/feed_notifier.dart';
import 'package:rewit_mobile/features/feed/presentation/state/feed_state.dart';
import 'package:rewit_mobile/features/review_detail/domain/entities/helpful_result.dart';
import 'package:rewit_mobile/features/review_detail/domain/entities/review_media.dart';
import 'package:rewit_mobile/features/review_detail/domain/entities/update_review_input.dart';
import 'package:rewit_mobile/features/discussions/presentation/widgets/discussions_section.dart';
import 'package:rewit_mobile/features/review_detail/presentation/screens/review_detail_screen.dart';
import 'package:rewit_mobile/shared/widgets/error_view.dart';

class FakeFullFeedRepo implements FeedRepository {
  bool helpfulToggled = false;
  int currentHelpfulCount = 5;
  bool isHelpful = false;
  bool deleteCalled = false;
  String? deletedReviewId;
  bool shouldThrowOnDelete = false;
  FeedReview? reviewToReturnOnUpdate;
  FeedReview? reviewToReturnOnGetById;
  bool shouldThrowOnGetById = false;
  Future<FeedReview> Function(String reviewId)? customGetReviewById;

  late final FeedReview sampleReview = FeedReview(
    id: 'rev-screen-1',
    author: const FeedAuthor(
      id: 'usr-1',
      handle: 'gourmet',
      displayName: 'Chef Gourmet',
      isAnonymous: false,
    ),
    experienceText: 'O risoto de cogumelos estava perfeito e a sobremesa foi memorável.',
    visibility: 'PUBLIC',
    status: 'ACTIVE',
    isAnonymous: false,
    isVerifiedOnSite: true,
    helpfulCount: 5,
    isHelpfulByMe: false,
    targets: [
      FeedTarget(
        id: 'tgt-1',
        targetId: 'bistro-place',
        rating: 4.8,
        specificComment: 'Atendimento excepcional e ambiente aconchegante',
        createdAt: DateTime.now().subtract(const Duration(hours: 1)),
      ),
    ],
    createdAt: DateTime.now().subtract(const Duration(hours: 1)),
  );

  @override
  Future<FeedPage> getFeed({int page = 0, int size = 10}) async {
    return FeedPage(
      items: [sampleReview],
      page: 0,
      size: 10,
      windowSize: 1,
      totalPages: 1,
    );
  }

  @override
  Future<FeedReview> getReviewById(String reviewId) async {
    if (customGetReviewById != null) {
      return customGetReviewById!(reviewId);
    }
    if (shouldThrowOnGetById) {
      throw const ApiException(ProblemDetail(
        type: 'about:blank',
        title: 'Erro',
        status: 500,
        detail: 'Erro interno ao buscar avaliação',
      ));
    }
    return reviewToReturnOnGetById ?? sampleReview;
  }

  @override
  Future<HelpfulResult> toggleHelpful(String reviewId, {required bool currentlyHelpful}) async {
    helpfulToggled = true;
    isHelpful = !currentlyHelpful;
    currentHelpfulCount += isHelpful ? 1 : -1;
    return HelpfulResult(helpful: isHelpful, helpfulCount: currentHelpfulCount);
  }

  @override
  Future<List<ReviewMediaItem>> getReviewMedia(String reviewId) async {
    return [
      ReviewMediaItem(
        id: 'media-1',
        reviewId: reviewId,
        url: '/api/v1/reviews/$reviewId/media/media-1',
        mediaType: 'IMAGE',
        mimeType: 'image/jpeg',
        sizeBytes: 350000,
        status: 'ACTIVE',
        createdAt: DateTime(2026, 10, 6),
      ),
    ];
  }

  @override
  Future<FeedReview> updateReview(String reviewId, UpdateReviewInput input) async {
    return reviewToReturnOnUpdate ?? sampleReview;
  }

  @override
  Future<void> deleteReview(String reviewId) async {
    deleteCalled = true;
    deletedReviewId = reviewId;
    if (shouldThrowOnDelete) {
      throw const ApiException(ProblemDetail(
        type: 'about:blank',
        title: 'Acesso Proibido',
        status: 403,
        detail: 'Apenas o autor pode excluir a avaliação',
        code: 'REVIEW_NOT_OWNED',
      ));
    }
  }
}



class FakeDiscussionRepoForDetail implements DiscussionRepository {
  @override
  Future<DiscussionPage> getDiscussions(String reviewId, {int page = 0, int size = 20}) async {
    return DiscussionPage(
      content: [
        DiscussionThread(
          id: 'disc-thread-1',
          reviewId: reviewId,
          state: DiscussionViewState.visible,
          content: 'Adorei a recomendação!',
          author: const DiscussionAuthor(displayName: 'Visitante Fiel', handle: 'visitante'),
          isFromOwner: false,
          createdAt: DateTime(2026, 10, 6, 21, 0),
          canReply: true,
          canDelete: true,
          replies: [],
          replyCount: 0,
          hasMoreReplies: false,
        ),
      ],
      pageNumber: 0,
      pageSize: 20,
      totalElements: 1,
      totalPages: 1,
      isLast: true,
    );
  }

  @override
  Future<DiscussionRepliesPage> getReplies(String discussionId, {int page = 0, int size = 20}) async {
    return const DiscussionRepliesPage(
      content: [],
      pageNumber: 0,
      pageSize: 20,
      totalElements: 0,
      totalPages: 0,
      isLast: true,
    );
  }

  @override
  Future<DiscussionItem> createDiscussion({
    required String reviewId,
    required String content,
    String? parentId,
  }) async {
    return DiscussionItem(
      id: 'new-disc',
      reviewId: reviewId,
      parentId: parentId,
      state: DiscussionViewState.visible,
      content: content,
      isFromOwner: false,
      createdAt: DateTime.now(),
      canReply: parentId == null,
      canDelete: true,
    );
  }

  @override
  Future<void> deleteDiscussion(String discussionId) async {}

  @override
  Future<String> reportDiscussion({
    required String discussionId,
    required ReportReason reason,
    String? detail,
  }) async {
    return 'Denúncia recebida. Obrigado por ajudar a manter a comunidade segura.';
  }
}

void main() {
  group('ReviewDetailScreen Integration Tests', () {
    late FakeFullFeedRepo feedRepo;
    late FakeDiscussionRepoForDetail discussionRepo;

    setUp(() {
      feedRepo = FakeFullFeedRepo();
      discussionRepo = FakeDiscussionRepoForDetail();
    });

    testWidgets('exibe informações completas da avaliação, mídias e seção de discussões', (tester) async {
      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.lightTheme,
          home: ReviewDetailScreen(
            reviewId: 'rev-screen-1',
            initialReview: feedRepo.sampleReview,
            feedRepository: feedRepo,
            discussionRepository: discussionRepo,
          ),
        ),
      );

      // Espera carregamento de mídias e discussões
      await tester.pump();
      await tester.pumpAndSettle();

      // Autor e detalhes
      expect(find.text('Chef Gourmet'), findsOneWidget);
      expect(find.text('@gourmet'), findsOneWidget);
      expect(find.text('Presença confirmada no estabelecimento (Check-in validado)'), findsOneWidget);
      expect(find.text('Alvos da Avaliação'), findsOneWidget);
      expect(find.text('Alvo: bistro-place'), findsOneWidget);
      expect(find.text('Atendimento excepcional e ambiente aconchegante'), findsOneWidget);
      expect(find.text('4.8'), findsOneWidget);
      expect(find.text('O risoto de cogumelos estava perfeito e a sobremesa foi memorável.'), findsOneWidget);

      // Seção Helpful
      expect(find.text('5 pessoas acharam útil'), findsOneWidget);

      // Mídia carregada
      expect(find.text('Fotos e Anexos (1)'), findsOneWidget);
      expect(find.text('JPEG'), findsOneWidget);

      // Seção de Discussões
      expect(find.text('Comentários da Comunidade'), findsOneWidget);
      expect(find.text('Adorei a recomendação!'), findsOneWidget);
      expect(find.text('Visitante Fiel'), findsOneWidget);
    });

    testWidgets('permite alternar voto útil interativo com chamada ao repositório', (tester) async {
      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.lightTheme,
          home: ReviewDetailScreen(
            reviewId: 'rev-screen-1',
            initialReview: feedRepo.sampleReview,
            feedRepository: feedRepo,
            discussionRepository: discussionRepo,
          ),
        ),
      );

      await tester.pump();
      await tester.pumpAndSettle();

      expect(find.text('Votar útil'), findsOneWidget);

      // Garante visibilidade dentro do SingleChildScrollView e clica em "Votar útil"
      await tester.ensureVisible(find.text('Votar útil'));
      await tester.tap(find.text('Votar útil'));
      await tester.pumpAndSettle();

      expect(feedRepo.helpfulToggled, isTrue);
      expect(find.text('6 pessoas acharam útil'), findsOneWidget);
      expect(find.text('Útil'), findsWidgets);
    });

    testWidgets('botões de editar e excluir aparecem quando usuário autenticado é o autor', (tester) async {
      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.lightTheme,
          home: ReviewDetailScreen(
            reviewId: 'rev-screen-1',
            initialReview: feedRepo.sampleReview,
            feedRepository: feedRepo,
            discussionRepository: discussionRepo,
            currentUserId: 'usr-1',
          ),
        ),
      );

      await tester.pump();
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('review_detail_edit_button')), findsOneWidget);
      expect(find.byKey(const Key('review_detail_delete_button')), findsOneWidget);
    });

    testWidgets('botões de editar e excluir NÃO aparecem para outro usuário', (tester) async {
      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.lightTheme,
          home: ReviewDetailScreen(
            reviewId: 'rev-screen-1',
            initialReview: feedRepo.sampleReview,
            feedRepository: feedRepo,
            discussionRepository: discussionRepo,
            currentUserId: 'another-user',
          ),
        ),
      );

      await tester.pump();
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('review_detail_edit_button')), findsNothing);
      expect(find.byKey(const Key('review_detail_delete_button')), findsNothing);
    });

    testWidgets('botões de editar e excluir NÃO aparecem para avaliação anônima sem ID público', (tester) async {
      final anonReview = feedRepo.sampleReview.copyWith(
        isAnonymous: true,
        author: const FeedAuthor(
          id: null,
          displayName: 'Anônimo',
          isAnonymous: true,
        ),
      );
      feedRepo.reviewToReturnOnGetById = anonReview;

      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.lightTheme,
          home: ReviewDetailScreen(
            reviewId: 'rev-screen-1',
            initialReview: anonReview,
            feedRepository: feedRepo,
            discussionRepository: discussionRepo,
            currentUserId: 'usr-1',
          ),
        ),
      );

      await tester.pump();
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('review_detail_edit_button')), findsNothing);
      expect(find.byKey(const Key('review_detail_delete_button')), findsNothing);
    });

    group('posse contextual (isMine)', () {
      Future<void> pumpDetail(WidgetTester tester, FeedReview review, {String? currentUserId}) async {
        feedRepo.reviewToReturnOnGetById = review;
        await tester.pumpWidget(
          MaterialApp(
            theme: AppTheme.lightTheme,
            home: ReviewDetailScreen(
              reviewId: review.id,
              initialReview: review,
              feedRepository: feedRepo,
              discussionRepository: discussionRepo,
              currentUserId: currentUserId,
            ),
          ),
        );
        await tester.pump();
        await tester.pumpAndSettle();
      }

      void expectActions(Matcher matcher) {
        expect(find.byKey(const Key('review_detail_edit_button')), matcher);
        expect(find.byKey(const Key('review_detail_delete_button')), matcher);
      }

      const anonymousAuthor = FeedAuthor(id: null, displayName: 'Anônimo', isAnonymous: true);

      testWidgets('review pública própria (isMine=true) mostra ações', (tester) async {
        await pumpDetail(tester, feedRepo.sampleReview.copyWith(isMine: true), currentUserId: 'usr-1');
        expectActions(findsOneWidget);
      });

      testWidgets('review de terceiro (isMine=false) não mostra ações, mesmo com id público coincidente', (tester) async {
        await pumpDetail(tester, feedRepo.sampleReview.copyWith(isMine: false), currentUserId: 'usr-1');
        expectActions(findsNothing);
      });

      testWidgets('review anônima própria (isMine=true, sem id público) mostra ações', (tester) async {
        final review = feedRepo.sampleReview.copyWith(isAnonymous: true, author: anonymousAuthor, isMine: true);
        await pumpDetail(tester, review, currentUserId: 'usr-1');
        expectActions(findsOneWidget);
      });

      testWidgets('review anônima de terceiro (isMine=false) não mostra ações', (tester) async {
        final review = feedRepo.sampleReview.copyWith(isAnonymous: true, author: anonymousAuthor, isMine: false);
        await pumpDetail(tester, review, currentUserId: 'usr-1');
        expectActions(findsNothing);
      });

      testWidgets('review de autor excluído (isMine=false) não mostra ações', (tester) async {
        final review = feedRepo.sampleReview.copyWith(
          author: const FeedAuthor(id: null, displayName: 'Usuário excluído', isAnonymous: true),
          isMine: false,
        );
        await pumpDetail(tester, review, currentUserId: 'usr-1');
        expectActions(findsNothing);
      });

      testWidgets('review sem isMine e sem autoria comprovada não mostra ações', (tester) async {
        expect(feedRepo.sampleReview.isMine, isNull);
        await pumpDetail(tester, feedRepo.sampleReview, currentUserId: 'another-user');
        expectActions(findsNothing);
      });
    });

    group('atualização canônica de ownership em segundo plano', () {
      testWidgets('initialReview anônima do feed (isMine=null) ganha botões de editar e excluir após getReviewById canônico retornar isMine=true', (tester) async {
        final anonymousFeedReview = feedRepo.sampleReview.copyWith(
          isAnonymous: true,
          author: const FeedAuthor(id: null, displayName: 'Anônimo', isAnonymous: true),
          isMine: null,
        );
        final canonicalOwnedReview = anonymousFeedReview.copyWith(
          isMine: true,
        );

        feedRepo.reviewToReturnOnGetById = canonicalOwnedReview;

        await tester.pumpWidget(
          MaterialApp(
            theme: AppTheme.lightTheme,
            home: ReviewDetailScreen(
              reviewId: 'rev-screen-1',
              initialReview: anonymousFeedReview,
              feedRepository: feedRepo,
              discussionRepository: discussionRepo,
              currentUserId: 'usr-1',
            ),
          ),
        );

        // Frame inicial: renderiza imediatamente com initialReview sem spinner de tela cheia
        expect(find.text('Carregando detalhes...'), findsNothing);
        expect(find.text('Anônimo'), findsWidgets);

        // Aguarda resolução da chamada em background getReviewById
        await tester.pump();
        await tester.pumpAndSettle();

        // Agora botões de editar e excluir aparecem pois isMine=true foi carregado da versão canônica
        expect(find.byKey(const Key('review_detail_edit_button')), findsOneWidget);
        expect(find.byKey(const Key('review_detail_delete_button')), findsOneWidget);
      });

      testWidgets('initialReview não exibe botões se getReviewById canônico retornar isMine=false', (tester) async {
        final anonymousFeedReview = feedRepo.sampleReview.copyWith(
          isAnonymous: true,
          author: const FeedAuthor(id: null, displayName: 'Anônimo', isAnonymous: true),
          isMine: null,
        );
        final canonicalOtherReview = anonymousFeedReview.copyWith(
          isMine: false,
        );

        feedRepo.reviewToReturnOnGetById = canonicalOtherReview;

        await tester.pumpWidget(
          MaterialApp(
            theme: AppTheme.lightTheme,
            home: ReviewDetailScreen(
              reviewId: 'rev-screen-1',
              initialReview: anonymousFeedReview,
              feedRepository: feedRepo,
              discussionRepository: discussionRepo,
              currentUserId: 'usr-1',
            ),
          ),
        );

        await tester.pump();
        await tester.pumpAndSettle();

        expect(find.byKey(const Key('review_detail_edit_button')), findsNothing);
        expect(find.byKey(const Key('review_detail_delete_button')), findsNothing);
      });

      testWidgets('falha no getReviewById preserva initialReview visível sem exibir tela de erro ou quebrar a tela', (tester) async {
        final initialReview = feedRepo.sampleReview.copyWith(
          experienceText: 'Avaliação visível vinda do feed antes da falha de rede.',
        );

        feedRepo.shouldThrowOnGetById = true;

        await tester.pumpWidget(
          MaterialApp(
            theme: AppTheme.lightTheme,
            home: ReviewDetailScreen(
              reviewId: 'rev-screen-1',
              initialReview: initialReview,
              feedRepository: feedRepo,
              discussionRepository: discussionRepo,
              currentUserId: 'usr-1',
            ),
          ),
        );

        await tester.pump();
        await tester.pumpAndSettle();

        // Não deve mostrar ErrorView
        expect(find.byType(ErrorView), findsNothing);
        expect(find.text('Erro ao carregar avaliação'), findsNothing);

        // Conteúdo da initialReview continua na tela
        expect(find.text('Avaliação visível vinda do feed antes da falha de rede.'), findsOneWidget);
      });
    });

    testWidgets('diálogo de exclusão descreve remoção sem prometer exclusão permanente', (tester) async {
      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.lightTheme,
          home: ReviewDetailScreen(
            reviewId: 'rev-screen-1',
            initialReview: feedRepo.sampleReview.copyWith(isMine: true),
            feedRepository: feedRepo,
            discussionRepository: discussionRepo,
          ),
        ),
      );
      await tester.pump();
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('review_detail_delete_button')));
      await tester.pumpAndSettle();

      expect(
        find.textContaining('Essa publicação será removida e deixará de aparecer para outras pessoas.'),
        findsOneWidget,
      );
      expect(find.textContaining('permanentemente'), findsNothing);
    });

    testWidgets('excluir avaliação: cancelar no diálogo não chama deleteReview', (tester) async {
      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.lightTheme,
          home: ReviewDetailScreen(
            reviewId: 'rev-screen-1',
            initialReview: feedRepo.sampleReview,
            feedRepository: feedRepo,
            discussionRepository: discussionRepo,
            currentUserId: 'usr-1',
          ),
        ),
      );

      await tester.pump();
      await tester.pumpAndSettle();

      // Clica em excluir
      await tester.tap(find.byKey(const Key('review_detail_delete_button')));
      await tester.pumpAndSettle();

      expect(find.text('Excluir Avaliação'), findsOneWidget);

      // Clica em cancelar
      await tester.tap(find.text('Cancelar'));
      await tester.pumpAndSettle();

      expect(find.text('Excluir Avaliação'), findsNothing);
      expect(feedRepo.deleteCalled, isFalse);
    });

    testWidgets('excluir avaliação: confirmar chama deleteReview, atualiza FeedNotifier e fecha tela', (tester) async {
      final feedNotifier = FeedNotifier(feedRepository: feedRepo);
      await feedNotifier.loadInitial();
      expect(feedNotifier.state, isA<FeedSuccess>());

      dynamic popResult;

      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.lightTheme,
          home: Builder(
            builder: (context) => Scaffold(
              body: ElevatedButton(
                onPressed: () async {
                  popResult = await Navigator.of(context).push(
                    MaterialPageRoute(
                      builder: (_) => ReviewDetailScreen(
                        reviewId: 'rev-screen-1',
                        initialReview: feedRepo.sampleReview,
                        feedRepository: feedRepo,
                        discussionRepository: discussionRepo,
                        feedNotifier: feedNotifier,
                        currentUserId: 'usr-1',
                      ),
                    ),
                  );
                },
                child: const Text('Abrir Detalhe'),
              ),
            ),
          ),
        ),
      );

      await tester.tap(find.text('Abrir Detalhe'));
      await tester.pumpAndSettle();

      // Clica em excluir
      await tester.tap(find.byKey(const Key('review_detail_delete_button')));
      await tester.pumpAndSettle();

      // Confirma no diálogo
      await tester.tap(find.byKey(const Key('confirm_delete_review_button')));
      await tester.pumpAndSettle();

      expect(feedRepo.deleteCalled, isTrue);
      expect(feedRepo.deletedReviewId, 'rev-screen-1');
      expect(popResult, {'deleted': true, 'reviewId': 'rev-screen-1'});
      expect(feedNotifier.state, isA<FeedEmpty>());
    });

    testWidgets('excluir avaliação: erro do backend exibe SnackBar e não fecha a tela', (tester) async {
      feedRepo.shouldThrowOnDelete = true;

      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.lightTheme,
          home: ReviewDetailScreen(
            reviewId: 'rev-screen-1',
            initialReview: feedRepo.sampleReview,
            feedRepository: feedRepo,
            discussionRepository: discussionRepo,
            currentUserId: 'usr-1',
          ),
        ),
      );

      await tester.pump();
      await tester.pumpAndSettle();

      // Clica em excluir
      await tester.tap(find.byKey(const Key('review_detail_delete_button')));
      await tester.pumpAndSettle();

      // Confirma no diálogo
      await tester.tap(find.byKey(const Key('confirm_delete_review_button')));
      await tester.pumpAndSettle();

      expect(feedRepo.deleteCalled, isTrue);
      expect(find.text('Falha ao excluir avaliação: Apenas o autor pode excluir a avaliação'), findsOneWidget);

      // Tela continua aberta com o título
      expect(find.text('Avaliação'), findsOneWidget);
    });

    testWidgets('editar avaliação: abre ReviewEditScreen e atualiza dados exibidos na tela de detalhe após sucesso', (tester) async {
      tester.view.physicalSize = const Size(800, 2400);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(() {
        tester.view.resetPhysicalSize();
        tester.view.resetDevicePixelRatio();
      });

      feedRepo.reviewToReturnOnUpdate = feedRepo.sampleReview.copyWith(
        experienceText: 'Texto editado e confirmado com sucesso!',
      );

      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.lightTheme,
          home: ReviewDetailScreen(
            reviewId: 'rev-screen-1',
            initialReview: feedRepo.sampleReview,
            feedRepository: feedRepo,
            discussionRepository: discussionRepo,
            currentUserId: 'usr-1',
          ),
        ),
      );

      await tester.pump();
      await tester.pumpAndSettle();

      // Clica em Editar
      await tester.tap(find.byKey(const Key('review_detail_edit_button')));
      await tester.pumpAndSettle();

      expect(find.text('Editar Avaliação'), findsOneWidget);

      // Clica em Salvar
      await tester.tap(find.byKey(const Key('submit_edit_button')));
      await tester.pumpAndSettle();

      // Voltou para a tela de detalhe com o texto atualizado
      expect(find.text('Texto editado e confirmado com sucesso!'), findsOneWidget);
      expect(find.text('Avaliação atualizada com sucesso!'), findsOneWidget);
    });

    testWidgets('ReviewDetailScreen repassa targetDiscussionId e isReplyTarget para DiscussionsSection (C5.12)', (tester) async {
      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.lightTheme,
          home: ReviewDetailScreen(
            reviewId: 'rev-screen-1',
            initialReview: feedRepo.sampleReview,
            targetDiscussionId: 'disc-target-1',
            isReplyTarget: true,
            feedRepository: feedRepo,
            discussionRepository: discussionRepo,
            currentUserId: 'usr-1',
          ),
        ),
      );

      await tester.pump();
      await tester.pumpAndSettle();

      final discussionsSection = tester.widget<DiscussionsSection>(find.byType(DiscussionsSection));
      expect(discussionsSection.targetDiscussionId, 'disc-target-1');
      expect(discussionsSection.isReplyTarget, isTrue);
    });

    group('PopScope e navegação ao voltar (Item 3)', () {
      testWidgets('gesto de voltar do sistema retorna avaliação editada quando _wasEdited for true', (tester) async {
        tester.view.physicalSize = const Size(800, 1600);
        tester.view.devicePixelRatio = 1.0;
        addTearDown(() {
          tester.view.resetPhysicalSize();
          tester.view.resetDevicePixelRatio();
        });

        feedRepo.reviewToReturnOnUpdate = feedRepo.sampleReview.copyWith(
          experienceText: 'Texto editado e confirmado com sucesso!',
        );

        FeedReview? poppedResult;
        await tester.pumpWidget(
          MaterialApp(
            theme: AppTheme.lightTheme,
            home: Builder(
              builder: (context) => ElevatedButton(
                onPressed: () async {
                  poppedResult = await Navigator.of(context).push<FeedReview>(
                    MaterialPageRoute(
                      builder: (_) => ReviewDetailScreen(
                        reviewId: 'rev-screen-1',
                        initialReview: feedRepo.sampleReview,
                        feedRepository: feedRepo,
                        currentUserId: 'usr-1',
                      ),
                    ),
                  );
                },
                child: const Text('Abrir Detalhe'),
              ),
            ),
          ),
        );

        await tester.tap(find.text('Abrir Detalhe'));
        await tester.pumpAndSettle();

        // Edita a avaliação
        await tester.tap(find.byKey(const Key('review_detail_edit_button')));
        await tester.pumpAndSettle();
        await tester.tap(find.byKey(const Key('submit_edit_button')));
        await tester.pumpAndSettle();

        // Simula gesto de voltar do sistema (Android back)
        await tester.binding.handlePopRoute();
        await tester.pumpAndSettle();

        expect(poppedResult, isNotNull);
        expect(poppedResult?.experienceText, 'Texto editado e confirmado com sucesso!');
      });

      testWidgets('botão voltar da AppBar retorna avaliação editada quando _wasEdited for true', (tester) async {
        tester.view.physicalSize = const Size(800, 1600);
        tester.view.devicePixelRatio = 1.0;
        addTearDown(() {
          tester.view.resetPhysicalSize();
          tester.view.resetDevicePixelRatio();
        });

        feedRepo.reviewToReturnOnUpdate = feedRepo.sampleReview.copyWith(
          experienceText: 'Texto editado e confirmado com sucesso!',
        );

        FeedReview? poppedResult;
        await tester.pumpWidget(
          MaterialApp(
            theme: AppTheme.lightTheme,
            home: Builder(
              builder: (context) => ElevatedButton(
                onPressed: () async {
                  poppedResult = await Navigator.of(context).push<FeedReview>(
                    MaterialPageRoute(
                      builder: (_) => ReviewDetailScreen(
                        reviewId: 'rev-screen-1',
                        initialReview: feedRepo.sampleReview,
                        feedRepository: feedRepo,
                        currentUserId: 'usr-1',
                      ),
                    ),
                  );
                },
                child: const Text('Abrir Detalhe'),
              ),
            ),
          ),
        );

        await tester.tap(find.text('Abrir Detalhe'));
        await tester.pumpAndSettle();

        // Edita a avaliação
        await tester.tap(find.byKey(const Key('review_detail_edit_button')));
        await tester.pumpAndSettle();
        await tester.tap(find.byKey(const Key('submit_edit_button')));
        await tester.pumpAndSettle();

        // Clica no botão de voltar da AppBar
        await tester.tap(find.byType(BackButton));
        await tester.pumpAndSettle();

        expect(poppedResult, isNotNull);
        expect(poppedResult?.experienceText, 'Texto editado e confirmado com sucesso!');
      });

      testWidgets('gesto de voltar do sistema retorna null quando não houve alterações', (tester) async {
        dynamic poppedResult = 'valor-inicial';
        await tester.pumpWidget(
          MaterialApp(
            theme: AppTheme.lightTheme,
            home: Builder(
              builder: (context) => ElevatedButton(
                onPressed: () async {
                  poppedResult = await Navigator.of(context).push<FeedReview>(
                    MaterialPageRoute(
                      builder: (_) => ReviewDetailScreen(
                        reviewId: 'rev-screen-1',
                        initialReview: feedRepo.sampleReview,
                        feedRepository: feedRepo,
                        currentUserId: 'usr-1',
                      ),
                    ),
                  );
                },
                child: const Text('Abrir Detalhe'),
              ),
            ),
          ),
        );

        await tester.tap(find.text('Abrir Detalhe'));
        await tester.pumpAndSettle();

        // Volta sem editar
        await tester.binding.handlePopRoute();
        await tester.pumpAndSettle();

        expect(poppedResult, isNull);
      });
    });

    group('Visibilidade de ações (editar e excluir) por estado e tempo (Item 6)', () {
      testWidgets('ACTIVE dentro da janela de 24h exibe editar e excluir para o autor', (tester) async {
        final rev = feedRepo.sampleReview.copyWith(
          status: 'ACTIVE',
          createdAt: DateTime.now().subtract(const Duration(hours: 2)),
          isMine: true,
        );
        await tester.pumpWidget(
          MaterialApp(
            theme: AppTheme.lightTheme,
            home: ReviewDetailScreen(
              reviewId: rev.id,
              initialReview: rev,
              currentUserId: 'usr-1',
            ),
          ),
        );
        await tester.pumpAndSettle();

        expect(find.byKey(const Key('review_detail_edit_button')), findsOneWidget);
        expect(find.byKey(const Key('review_detail_delete_button')), findsOneWidget);
      });

      testWidgets('ACTIVE fora da janela de 24h oculta editar e exibe excluir para o autor', (tester) async {
        final rev = feedRepo.sampleReview.copyWith(
          status: 'ACTIVE',
          createdAt: DateTime.now().subtract(const Duration(hours: 25)),
          isMine: true,
        );
        await tester.pumpWidget(
          MaterialApp(
            theme: AppTheme.lightTheme,
            home: ReviewDetailScreen(
              reviewId: rev.id,
              initialReview: rev,
              currentUserId: 'usr-1',
            ),
          ),
        );
        await tester.pumpAndSettle();

        expect(find.byKey(const Key('review_detail_edit_button')), findsNothing);
        expect(find.byKey(const Key('review_detail_delete_button')), findsOneWidget);
      });

      testWidgets('UNDER_REVIEW oculta tanto editar quanto excluir para o autor', (tester) async {
        final rev = feedRepo.sampleReview.copyWith(
          status: 'UNDER_REVIEW',
          createdAt: DateTime.now().subtract(const Duration(hours: 1)),
          isMine: true,
        );
        await tester.pumpWidget(
          MaterialApp(
            theme: AppTheme.lightTheme,
            home: ReviewDetailScreen(
              reviewId: rev.id,
              initialReview: rev,
              currentUserId: 'usr-1',
            ),
          ),
        );
        await tester.pumpAndSettle();

        expect(find.byKey(const Key('review_detail_edit_button')), findsNothing);
        expect(find.byKey(const Key('review_detail_delete_button')), findsNothing);
      });

      testWidgets('REMOVED oculta tanto editar quanto excluir para o autor', (tester) async {
        final rev = feedRepo.sampleReview.copyWith(
          status: 'REMOVED',
          createdAt: DateTime.now().subtract(const Duration(hours: 1)),
          isMine: true,
        );
        await tester.pumpWidget(
          MaterialApp(
            theme: AppTheme.lightTheme,
            home: ReviewDetailScreen(
              reviewId: rev.id,
              initialReview: rev,
              currentUserId: 'usr-1',
            ),
          ),
        );
        await tester.pumpAndSettle();

        expect(find.byKey(const Key('review_detail_edit_button')), findsNothing);
        expect(find.byKey(const Key('review_detail_delete_button')), findsNothing);
      });

      testWidgets('avaliação que não pertence ao usuário atual oculta editar e excluir', (tester) async {
        final rev = feedRepo.sampleReview.copyWith(
          status: 'ACTIVE',
          createdAt: DateTime.now().subtract(const Duration(hours: 1)),
          isMine: false,
        );
        await tester.pumpWidget(
          MaterialApp(
            theme: AppTheme.lightTheme,
            home: ReviewDetailScreen(
              reviewId: rev.id,
              initialReview: rev,
              currentUserId: 'outro-user',
            ),
          ),
        );
        await tester.pumpAndSettle();

        expect(find.byKey(const Key('review_detail_edit_button')), findsNothing);
        expect(find.byKey(const Key('review_detail_delete_button')), findsNothing);
      });
    });

    group('Corrida no reload canônico do detalhe (Item 7)', () {
      testWidgets('resposta atrasada do getReviewById canônico não sobrescreve mutação mais recente', (tester) async {
        final completer = Completer<FeedReview>();
        feedRepo.customGetReviewById = (_) => completer.future;

        await tester.pumpWidget(
          MaterialApp(
            theme: AppTheme.lightTheme,
            home: ReviewDetailScreen(
              reviewId: 'rev-screen-1',
              initialReview: feedRepo.sampleReview,
              feedRepository: feedRepo,
              currentUserId: 'usr-1',
            ),
          ),
        );
        await tester.pump();

        // Mutação local: usuário altera o voto útil enquanto getReviewById está atrasado
        final helpfulButton = find.text('Votar útil');
        await tester.ensureVisible(helpfulButton);
        await tester.tap(helpfulButton);
        await tester.pumpAndSettle();

        // Voto útil foi alterado para 6
        expect(find.text('6 pessoas acharam útil'), findsOneWidget);

        // Resposta atrasada do carregamento canônico chega com dados velhos (helpfulCount = 5)
        completer.complete(feedRepo.sampleReview.copyWith(
          helpfulCount: 5,
          isHelpfulByMe: false,
          experienceText: 'Texto Antigo Sobrescrito Indevidamente',
        ));
        await tester.pumpAndSettle();

        // Mutação mais recente foi preservada
        expect(find.text('6 pessoas acharam útil'), findsOneWidget);
        expect(find.text('Texto Antigo Sobrescrito Indevidamente'), findsNothing);
      });
    });
  });
}
