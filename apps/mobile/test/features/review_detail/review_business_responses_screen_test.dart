import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rewit_mobile/features/business/domain/entities/business_entities.dart';
import 'package:rewit_mobile/features/business/domain/entities/business_response_entities.dart';
import 'package:rewit_mobile/features/business/domain/repositories/business_repository.dart';
import 'package:rewit_mobile/features/business/domain/repositories/business_response_repository.dart';
import 'package:rewit_mobile/features/business/presentation/widgets/review_business_responses_section.dart';
import 'package:rewit_mobile/features/discussions/domain/entities/discussion_entities.dart';
import 'package:rewit_mobile/features/discussions/domain/repositories/discussion_repository.dart';
import 'package:rewit_mobile/features/discussions/presentation/state/discussion_notifier.dart';
import 'package:rewit_mobile/features/feed/domain/entities/feed_entities.dart';
import 'package:rewit_mobile/features/feed/domain/repositories/feed_repository.dart';
import 'package:rewit_mobile/features/review_detail/domain/entities/helpful_result.dart';
import 'package:rewit_mobile/features/review_detail/domain/entities/review_media.dart';
import 'package:rewit_mobile/features/review_detail/domain/entities/update_review_input.dart';
import 'package:rewit_mobile/features/review_detail/presentation/screens/review_detail_screen.dart';

class MockBusinessResponseRepository implements BusinessResponseRepository {
  List<BusinessResponse> reviewResponses = [];
  List<BusinessResponse> discussionResponses = [];
  BusinessResponseEligibility? eligibility;
  bool shouldFailReviewResponses = false;

  @override
  Future<BusinessResponsePage> getReviewBusinessResponses(String reviewId, {int page = 0, int size = 20}) async {
    if (shouldFailReviewResponses) {
      throw Exception('Falha ao carregar respostas');
    }
    return BusinessResponsePage(
      content: List.of(reviewResponses),
      pageNumber: page,
      pageSize: size,
      totalElements: reviewResponses.length,
      totalPages: 1,
      isLast: true,
    );
  }

  @override
  Future<BusinessResponseEligibility> getReviewEligibility(String reviewId) async {
    return eligibility ??
        BusinessResponseEligibility(
          reviewId: reviewId,
          respondable: true,
          accounts: [],
        );
  }

  @override
  Future<BusinessResponse> publishReviewBusinessResponse({
    required String reviewId,
    required String businessAccountId,
    required String placeId,
    required String content,
  }) async {
    final created = BusinessResponse(
      id: 'resp-new-${reviewResponses.length + 1}',
      reviewId: reviewId,
      discussionId: null,
      businessAccountId: businessAccountId,
      corporateName: 'Padaria Modelo Ltda',
      placeId: placeId,
      placeName: 'Modelo Centro',
      content: content,
      createdAt: DateTime.now(),
    );
    reviewResponses.insert(0, created);
    return created;
  }

  @override
  Future<BusinessResponsePage> getDiscussionBusinessResponses(String discussionId, {int page = 0, int size = 20}) async {
    return BusinessResponsePage(
      content: List.of(discussionResponses),
      pageNumber: page,
      pageSize: size,
      totalElements: discussionResponses.length,
      totalPages: 1,
      isLast: true,
    );
  }

  @override
  Future<BusinessResponse> publishDiscussionBusinessResponse({
    required String discussionId,
    required String businessAccountId,
    required String placeId,
    required String content,
  }) async {
    final created = BusinessResponse(
      id: 'disc-resp-new-${discussionResponses.length + 1}',
      reviewId: 'rev-1',
      discussionId: discussionId,
      businessAccountId: businessAccountId,
      corporateName: 'Padaria Modelo Ltda',
      placeId: placeId,
      placeName: 'Modelo Centro',
      content: content,
      createdAt: DateTime.now(),
    );
    discussionResponses.insert(0, created);
    return created;
  }

  @override
  Future<void> deleteBusinessResponse(String responseId) async {
    reviewResponses.removeWhere((r) => r.id == responseId);
    discussionResponses.removeWhere((r) => r.id == responseId);
  }
}

class MockBusinessRepository implements BusinessRepository {
  List<BusinessAccount> accounts = [];

  @override
  Future<List<BusinessAccount>> getMyBusinessAccounts() async => accounts;

  @override
  Future<BusinessAccount> createBusinessAccount({required String corporateName, required String taxId}) async {
    throw UnimplementedError();
  }

  @override
  Future<PlaceClaim> requestPlaceClaim({
    required String businessAccountId,
    required String placeId,
    required String evidenceDescription,
  }) async {
    throw UnimplementedError();
  }

  @override
  Future<List<PlaceClaim>> getBusinessPlaceClaims(String businessAccountId, {int page = 0, int size = 20, String? status}) async {
    return [];
  }
}

class MockFeedRepository implements FeedRepository {
  @override
  Future<FeedPage> getFeed({int page = 0, int size = 10, String? city, String? category}) async {
    return const FeedPage(items: [], page: 0, size: 10, windowSize: 0, totalPages: 0);
  }

  @override
  Future<FeedReview> getReviewById(String id) async => throw UnimplementedError();

  @override
  Future<List<ReviewMediaItem>> getReviewMedia(String reviewId) async => [];

  @override
  Future<HelpfulResult> toggleHelpful(String reviewId, {required bool currentlyHelpful}) async {
    return HelpfulResult(helpful: !currentlyHelpful, helpfulCount: 1);
  }

  @override
  Future<FeedReview> updateReview(String reviewId, UpdateReviewInput input) async => throw UnimplementedError();

  @override
  Future<void> deleteReview(String reviewId) async {}
}

class MockDiscussionRepository implements DiscussionRepository {
  List<DiscussionThread> threads = [];

  @override
  Future<DiscussionPage> getDiscussions(String reviewId, {int page = 0, int size = 20}) async {
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
    return const DiscussionRepliesPage(content: [], pageNumber: 0, pageSize: 20, totalElements: 0, totalPages: 0, isLast: true);
  }

  @override
  Future<DiscussionItem> createDiscussion({required String reviewId, required String content, String? parentId}) async {
    throw UnimplementedError();
  }

  @override
  Future<void> deleteDiscussion(String discussionId) async {}

  @override
  Future<String> reportDiscussion({required String discussionId, required ReportReason reason, String? detail}) async {
    return 'Denúncia registrada';
  }
}

void main() {
  group('ReviewBusinessResponsesSection Widget Tests (C11)', () {
    late MockBusinessResponseRepository responseRepo;
    late MockBusinessRepository businessRepo;

    setUp(() {
      responseRepo = MockBusinessResponseRepository();
      businessRepo = MockBusinessRepository();
    });

    testWidgets('exibe respostas oficiais com selo e oculta botão de publicação se inelegível', (tester) async {
      responseRepo.reviewResponses = [
        BusinessResponse(
          id: 'resp-1',
          reviewId: 'rev-1',
          discussionId: null,
          businessAccountId: 'acc-other',
          corporateName: 'Padaria Modelo Ltda',
          placeId: 'plc-1',
          placeName: 'Modelo Centro',
          content: 'Agradecemos sua avaliação!',
          createdAt: DateTime(2026, 10, 11, 15, 0),
        ),
      ];
      // Sem contas elegíveis
      responseRepo.eligibility = const BusinessResponseEligibility(
        reviewId: 'rev-1',
        respondable: true,
        accounts: [],
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: ReviewBusinessResponsesSection(
              reviewId: 'rev-1',
              repository: responseRepo,
              businessRepository: businessRepo,
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();

      // Selo institucional visível
      expect(find.text('Resposta oficial · Padaria Modelo Ltda · Modelo Centro'), findsOneWidget);
      expect(find.text('Agradecemos sua avaliação!'), findsOneWidget);
      // Botão de responder como empresa NÃO deve aparecer
      expect(find.byKey(const Key('open_publish_business_response_button')), findsNothing);
      // Resposta é de outra conta (acc-other), não deve oferecer ação de exclusão
      expect(find.text('Excluir'), findsNothing);
    });

    testWidgets('permite que conta proprietária exclua a própria resposta oficial após confirmação', (tester) async {
      // O usuário logado possui a conta acc-mine
      businessRepo.accounts = [
        BusinessAccount(
          id: 'acc-mine',
          corporateName: 'Padaria Modelo Ltda',
          taxId: '12.345.678/0001-90',
          verificationStatus: 'APPROVED',
          planTier: 'PREMIUM',
          createdAt: DateTime.now(),
          updatedAt: DateTime.now(),
        ),
      ];

      responseRepo.reviewResponses = [
        BusinessResponse(
          id: 'resp-mine-1',
          reviewId: 'rev-1',
          discussionId: null,
          businessAccountId: 'acc-mine',
          corporateName: 'Padaria Modelo Ltda',
          placeId: 'plc-1',
          placeName: 'Modelo Centro',
          content: 'Nossa resposta oficial própria.',
          createdAt: DateTime(2026, 10, 11, 15, 0),
        ),
      ];

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: ReviewBusinessResponsesSection(
              reviewId: 'rev-1',
              repository: responseRepo,
              businessRepository: businessRepo,
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();

      // Botão Excluir visível porque acc-mine pertence ao usuário logado
      expect(find.text('Excluir'), findsOneWidget);

      await tester.tap(find.text('Excluir'));
      await tester.pumpAndSettle();

      // Confirma exclusão
      expect(find.text('Excluir resposta oficial'), findsOneWidget);
      await tester.tap(find.byKey(const Key('confirm_delete_business_response_button')));
      await tester.pumpAndSettle();

      // Resposta removida da lista
      expect(find.text('Nossa resposta oficial própria.'), findsNothing);
      expect(responseRepo.reviewResponses, isEmpty);
    });

    testWidgets('permite publicar resposta oficial quando conta é elegível', (tester) async {
      responseRepo.eligibility = const BusinessResponseEligibility(
        reviewId: 'rev-1',
        respondable: true,
        accounts: [
          EligibleBusinessAccount(
            businessAccountId: 'acc-1',
            corporateName: 'Padaria Modelo Ltda',
            places: [
              EligiblePlace(placeId: 'plc-1', placeName: 'Modelo Centro'),
            ],
            activeResponseId: null,
            canPublish: true,
          ),
        ],
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: ReviewBusinessResponsesSection(
              reviewId: 'rev-1',
              repository: responseRepo,
              businessRepository: businessRepo,
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();

      // Botão Responder como empresa está visível
      final openButton = find.byKey(const Key('open_publish_business_response_button'));
      expect(openButton, findsOneWidget);

      // Clica para abrir formulário
      await tester.tap(openButton);
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('business_response_content_field')), findsOneWidget);

      // Preenche resposta e envia
      await tester.enterText(
        find.byKey(const Key('business_response_content_field')),
        'Obrigado pelo carinho!',
      );

      await tester.tap(find.byKey(const Key('submit_business_response_button')));
      await tester.pumpAndSettle();

      // Resposta aparece na lista com selo oficial
      expect(find.text('Resposta oficial · Padaria Modelo Ltda · Modelo Centro'), findsOneWidget);
      expect(find.text('Obrigado pelo carinho!'), findsOneWidget);
    });
  });

  group('ReviewDetailScreen Integration with Official Responses (C11)', () {
    late MockBusinessResponseRepository responseRepo;
    late MockBusinessRepository businessRepo;
    late MockFeedRepository feedRepo;
    late MockDiscussionRepository discussionRepo;

    final dummyReview = FeedReview(
      id: 'rev-1',
      author: const FeedAuthor(id: 'usr-1', displayName: 'Maria Silva'),
      targets: [
        FeedTarget(
          id: 't-1',
          targetId: 'plc-1',
          targetType: 'PLACE',
          rating: 4.5,
          createdAt: DateTime.now(),
        ),
      ],
      experienceText: 'Excelente atendimento no café!',
      visibility: 'PUBLIC',
      isVerifiedOnSite: true,
      helpfulCount: 5,
      createdAt: DateTime.now().subtract(const Duration(hours: 2)),
      status: 'ACTIVE',
      mediaItems: const [],
      isAnonymous: false,
    );

    setUp(() {
      responseRepo = MockBusinessResponseRepository();
      businessRepo = MockBusinessRepository();
      feedRepo = MockFeedRepository();
      discussionRepo = MockDiscussionRepository();
    });

    testWidgets('renderiza bloco oficial separado do texto da avaliação e integra discussões', (tester) async {
      responseRepo.reviewResponses = [
        BusinessResponse(
          id: 'resp-rev-1',
          reviewId: 'rev-1',
          discussionId: null,
          businessAccountId: 'acc-1',
          corporateName: 'Padaria Modelo Ltda',
          placeId: 'plc-1',
          placeName: 'Modelo Centro',
          content: 'Ficamos felizes com sua visita, Maria!',
          createdAt: DateTime(2026, 10, 11, 16, 0),
        ),
      ];

      // Discussão comunitária raiz com resposta oficial associada
      discussionRepo.threads = [
        DiscussionThread(
          id: 'disc-1',
          reviewId: 'rev-1',
          parentId: null,
          state: DiscussionViewState.visible,
          content: 'Vocês têm opções sem glúten?',
          author: const DiscussionAuthor(id: 'usr-2', displayName: 'João Santos'),
          isFromOwner: false,
          createdAt: DateTime(2026, 10, 11, 16, 30),
          canReply: true,
          canDelete: false,
          replies: const [],
          replyCount: 0,
          hasMoreReplies: false,
        ),
      ];

      responseRepo.discussionResponses = [
        BusinessResponse(
          id: 'resp-disc-1',
          reviewId: 'rev-1',
          discussionId: 'disc-1',
          businessAccountId: 'acc-1',
          corporateName: 'Padaria Modelo Ltda',
          placeId: 'plc-1',
          placeName: 'Modelo Centro',
          content: 'Sim, João! Temos pães e bolos sem glúten diariamente.',
          createdAt: DateTime(2026, 10, 11, 16, 35),
        ),
      ];

      final notifier = DiscussionNotifier(repository: discussionRepo);

      await tester.pumpWidget(
        MaterialApp(
          home: ReviewDetailScreen(
            reviewId: 'rev-1',
            initialReview: dummyReview,
            feedRepository: feedRepo,
            discussionRepository: discussionRepo,
            discussionNotifier: notifier,
            businessResponseRepository: responseRepo,
            businessRepository: businessRepo,
          ),
        ),
      );
      await tester.pumpAndSettle();

      // 1. Texto da avaliação e autoria de Maria
      expect(find.text('Maria Silva'), findsOneWidget);
      expect(find.text('Excelente atendimento no café!'), findsOneWidget);

      // 2. Bloco oficial da avaliação separado com selo
      expect(find.text('Ficamos felizes com sua visita, Maria!'), findsOneWidget);
      expect(find.text('Resposta oficial · Padaria Modelo Ltda · Modelo Centro'), findsNWidgets(2)); // Um na review e um na discussão!

      // 3. Comentário comunitário de João Santos
      expect(find.text('João Santos'), findsOneWidget);
      expect(find.text('Vocês têm opções sem glúten?'), findsOneWidget);

      // 4. Resposta oficial da empresa ao comentário do João
      expect(find.text('Sim, João! Temos pães e bolos sem glúten diariamente.'), findsOneWidget);
    });
  });
}
