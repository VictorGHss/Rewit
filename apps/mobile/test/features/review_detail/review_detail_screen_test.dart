import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rewit_mobile/app/theme/app_theme.dart';
import 'package:rewit_mobile/features/discussions/domain/entities/discussion_entities.dart';
import 'package:rewit_mobile/features/discussions/domain/repositories/discussion_repository.dart';
import 'package:rewit_mobile/features/feed/domain/entities/feed_entities.dart';
import 'package:rewit_mobile/features/feed/domain/repositories/feed_repository.dart';
import 'package:rewit_mobile/features/review_detail/domain/entities/helpful_result.dart';
import 'package:rewit_mobile/features/review_detail/domain/entities/review_media.dart';
import 'package:rewit_mobile/features/review_detail/presentation/screens/review_detail_screen.dart';

class FakeFullFeedRepo implements FeedRepository {
  bool helpfulToggled = false;
  int currentHelpfulCount = 5;
  bool isHelpful = false;

  final FeedReview sampleReview = FeedReview(
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
        createdAt: DateTime(2026, 10, 6),
      ),
    ],
    createdAt: DateTime(2026, 10, 6, 20, 0),
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
  Future<FeedReview> getReviewById(String reviewId) async => sampleReview;

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
  });
}
