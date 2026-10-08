import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rewit_mobile/app/theme/app_theme.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/features/feed/domain/entities/feed_entities.dart';
import 'package:rewit_mobile/features/feed/domain/repositories/feed_repository.dart';
import 'package:rewit_mobile/features/review_detail/domain/entities/helpful_result.dart';
import 'package:rewit_mobile/features/review_detail/domain/entities/review_media.dart';
import 'package:rewit_mobile/features/review_detail/domain/entities/update_review_input.dart';
import 'package:rewit_mobile/features/review_detail/presentation/screens/review_edit_screen.dart';

class FakeEditFeedRepository implements FeedRepository {
  UpdateReviewInput? lastUpdateInput;
  String? lastUpdateReviewId;
  Completer<FeedReview>? updateCompleter;
  bool shouldThrow409 = false;
  bool shouldThrowNetwork = false;

  final FeedReview baseReview;

  FakeEditFeedRepository({required this.baseReview});

  @override
  Future<FeedReview> updateReview(String reviewId, UpdateReviewInput input) async {
    lastUpdateReviewId = reviewId;
    lastUpdateInput = input;

    if (shouldThrow409) {
      throw const ApiException(ProblemDetail(
        type: 'about:blank',
        title: 'Conflito',
        status: 409,
        detail: 'A janela permitida de 24 horas para edição expirou',
        code: 'REVIEW_EDIT_WINDOW_EXPIRED',
      ));
    }

    if (shouldThrowNetwork) {
      throw const NetworkException('Sem conexão com a internet');
    }

    if (updateCompleter != null) {
      return updateCompleter!.future;
    }

    return baseReview.copyWith(
      experienceText: input.experienceText,
      visibility: input.visibility ?? baseReview.visibility,
      isAnonymous: input.isAnonymous ?? baseReview.isAnonymous,
    );
  }

  @override
  Future<void> deleteReview(String reviewId) async {}

  @override
  Future<FeedPage> getFeed({int page = 0, int size = 10}) async {
    return FeedPage(items: [baseReview], page: 0, size: 10, windowSize: 1, totalPages: 1);
  }

  @override
  Future<FeedReview> getReviewById(String reviewId) async => baseReview;

  @override
  Future<List<ReviewMediaItem>> getReviewMedia(String reviewId) async => [];

  @override
  Future<HelpfulResult> toggleHelpful(String reviewId, {required bool currentlyHelpful}) async {
    return const HelpfulResult(helpful: true, helpfulCount: 1);
  }
}

void main() {
  final sampleReview = FeedReview(
    id: 'rev-edit-test-1',
    author: const FeedAuthor(
      id: 'usr-author-1',
      displayName: 'Autor Original',
      handle: 'author1',
    ),
    experienceText: 'Texto inicial antes da edição.',
    visibility: 'PUBLIC',
    status: 'ACTIVE',
    isAnonymous: false,
    helpfulCount: 0,
    targets: [
      const FeedTarget(
        id: 't-1',
        targetId: 'target-uuid-1',
        targetType: 'PLACE',
        rating: 4.0,
        specificComment: 'Ambiente agradável',
      ),
    ],

    createdAt: DateTime.now(),
  );

  Widget createTestWidget({
    required FeedReview review,
    required FeedRepository repository,
  }) {
    return MaterialApp(
      theme: AppTheme.lightTheme,
      home: ReviewEditScreen(
        review: review,
        feedRepository: repository,
      ),
    );
  }

  void configureLargeScreen(WidgetTester tester) {
    tester.view.physicalSize = const Size(800, 2400);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(() {
      tester.view.resetPhysicalSize();
      tester.view.resetDevicePixelRatio();
    });
  }

  group('ReviewEditScreen Widget Tests', () {
    testWidgets('preenche campos corretamente a partir da avaliação existente', (tester) async {
      configureLargeScreen(tester);
      final fakeRepo = FakeEditFeedRepository(baseReview: sampleReview);

      await tester.pumpWidget(createTestWidget(
        review: sampleReview,
        repository: fakeRepo,
      ));
      await tester.pumpAndSettle();

      expect(find.text('Editar Avaliação'), findsOneWidget);
      expect(find.text('Texto inicial antes da edição.'), findsOneWidget);
      expect(find.text('Pública (visível a todos)'), findsOneWidget);
      expect(find.text('Publicar como anônimo'), findsOneWidget);
      expect(find.text('ID: target-uuid-1'), findsOneWidget);
      expect(find.text('Comentário específico: "Ambiente agradável"'), findsOneWidget);

      // Sem alerta de helpfulCount > 0 porque helpfulCount é 0
      expect(find.textContaining('voto(s) de útil'), findsNothing);
    });

    testWidgets('exibe aviso de votos úteis caso helpfulCount seja maior que 0', (tester) async {
      configureLargeScreen(tester);
      final reviewWithHelpful = sampleReview.copyWith(helpfulCount: 3);
      final fakeRepo = FakeEditFeedRepository(baseReview: reviewWithHelpful);

      await tester.pumpWidget(createTestWidget(
        review: reviewWithHelpful,
        repository: fakeRepo,
      ));
      await tester.pumpAndSettle();

      expect(find.textContaining('Esta avaliação já possui 3 voto(s) de útil'), findsOneWidget);
    });

    testWidgets('altera texto e configurações e submete com sucesso', (tester) async {
      configureLargeScreen(tester);
      final fakeRepo = FakeEditFeedRepository(baseReview: sampleReview);

      await tester.pumpWidget(createTestWidget(
        review: sampleReview,
        repository: fakeRepo,
      ));
      await tester.pumpAndSettle();

      // Altera o texto
      final textField = find.byKey(const Key('edit_experience_text_field'));
      await tester.enterText(textField, 'Texto totalmente revisado e melhorado.');

      // Alterna o switch de anônimo
      final anonSwitch = find.byKey(const Key('edit_anonymous_switch'));
      await tester.tap(anonSwitch);
      await tester.pumpAndSettle();

      // Clica em Salvar
      final saveButton = find.byKey(const Key('submit_edit_button'));
      await tester.ensureVisible(saveButton);
      await tester.tap(saveButton);
      await tester.pumpAndSettle();

      expect(fakeRepo.lastUpdateReviewId, 'rev-edit-test-1');
      expect(fakeRepo.lastUpdateInput?.experienceText, 'Texto totalmente revisado e melhorado.');
      expect(fakeRepo.lastUpdateInput?.isAnonymous, isTrue);
      expect(fakeRepo.lastUpdateInput?.visibility, 'PUBLIC');
    });

    testWidgets('impede double-submit enquanto submissão está pendente', (tester) async {
      configureLargeScreen(tester);
      final completer = Completer<FeedReview>();
      final fakeRepo = FakeEditFeedRepository(baseReview: sampleReview);
      fakeRepo.updateCompleter = completer;

      await tester.pumpWidget(createTestWidget(
        review: sampleReview,
        repository: fakeRepo,
      ));
      await tester.pumpAndSettle();

      final saveButton = find.byKey(const Key('submit_edit_button'));
      await tester.ensureVisible(saveButton);
      await tester.tap(saveButton);
      await tester.pump(); // Inicia submit

      // Durante a submissão, deve exibir indicador de progresso e texto 'Salvando...'
      expect(find.text('Salvando...'), findsOneWidget);

      // Tenta clicar novamente
      await tester.tap(saveButton);
      await tester.pump();

      // Completa a chamada
      completer.complete(sampleReview.copyWith(experienceText: 'Final'));
      await tester.pumpAndSettle();
    });

    testWidgets('preserva dados do formulário e exibe mensagem em caso de erro 409 do backend', (tester) async {
      configureLargeScreen(tester);
      final fakeRepo = FakeEditFeedRepository(baseReview: sampleReview);
      fakeRepo.shouldThrow409 = true;

      await tester.pumpWidget(createTestWidget(
        review: sampleReview,
        repository: fakeRepo,
      ));
      await tester.pumpAndSettle();

      final textField = find.byKey(const Key('edit_experience_text_field'));
      await tester.enterText(textField, 'Tentativa de alteração com janela expirada');

      final saveButton = find.byKey(const Key('submit_edit_button'));
      await tester.ensureVisible(saveButton);
      await tester.tap(saveButton);
      await tester.pumpAndSettle();

      // Mensagem de erro RFC 7807 exibida
      expect(find.text('A janela permitida de 24 horas para edição expirou'), findsWidgets);

      // Entrada do usuário preservada
      expect(find.text('Tentativa de alteração com janela expirada'), findsOneWidget);
    });
  });
}

