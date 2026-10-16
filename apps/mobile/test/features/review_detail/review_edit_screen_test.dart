import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rewit_mobile/app/theme/app_theme.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/features/feed/domain/entities/feed_entities.dart';
import 'package:rewit_mobile/features/feed/domain/repositories/feed_repository.dart';
import 'package:rewit_mobile/features/review_creation/domain/entities/review_privacy_rules.dart';
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
  ApiException? exceptionToThrow;

  final FeedReview baseReview;

  FakeEditFeedRepository({required this.baseReview});

  @override
  Future<FeedReview> updateReview(String reviewId, UpdateReviewInput input) async {
    lastUpdateReviewId = reviewId;
    lastUpdateInput = input;

    if (exceptionToThrow != null) {
      throw exceptionToThrow!;
    }

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

    testWidgets('5. edição de avaliação anônima válida permite troca de visibilidade e salva', (tester) async {
      configureLargeScreen(tester);
      final anonymousReview = sampleReview.copyWith(isAnonymous: true, visibility: 'PUBLIC');
      final fakeRepo = FakeEditFeedRepository(baseReview: anonymousReview);

      await tester.pumpWidget(createTestWidget(
        review: anonymousReview,
        repository: fakeRepo,
      ));
      await tester.pumpAndSettle();

      // Registro legado NÃO deve ser exibido
      expect(find.byKey(const Key('legacy_privacy_notice')), findsNothing);

      // Aviso explicativo de que seguidores não está disponível para anônimos
      expect(find.byKey(const Key('edit_followers_unavailable_hint')), findsOneWidget);
      expect(find.text(ReviewPrivacyRules.followersUnavailableForAnonymous), findsOneWidget);

      // Altera visibilidade para Privada
      final dropdownFinder = find.byKey(const Key('edit_visibility_dropdown'));
      await tester.ensureVisible(dropdownFinder);
      await tester.tap(dropdownFinder);
      await tester.pumpAndSettle();

      final privateItem = find.text('Privada (apenas você)').last;
      await tester.tap(privateItem);
      await tester.pumpAndSettle();

      // Clica em Salvar
      final saveButton = find.byKey(const Key('submit_edit_button'));
      await tester.ensureVisible(saveButton);
      await tester.tap(saveButton);
      await tester.pumpAndSettle();

      expect(fakeRepo.lastUpdateReviewId, 'rev-edit-test-1');
      expect(fakeRepo.lastUpdateInput?.isAnonymous, isTrue);
      expect(fakeRepo.lastUpdateInput?.visibility, 'PRIVATE');
    });

    testWidgets('6. abertura e resolução explícita de um registro legado', (tester) async {
      configureLargeScreen(tester);
      final legacyReview = sampleReview.copyWith(isAnonymous: true, visibility: 'FOLLOWERS');
      final fakeRepo = FakeEditFeedRepository(baseReview: legacyReview);

      await tester.pumpWidget(createTestWidget(
        review: legacyReview,
        repository: fakeRepo,
      ));
      await tester.pumpAndSettle();

      // Aviso legado em destaque
      expect(find.byKey(const Key('legacy_privacy_notice')), findsOneWidget);
      expect(find.text(ReviewPrivacyRules.legacyCombinationNotice), findsOneWidget);

      // Botão Salvar desabilitado
      final saveButtonFinder = find.byKey(const Key('submit_edit_button'));
      await tester.ensureVisible(saveButtonFinder);
      final saveButtonWidget = tester.widget<FilledButton>(saveButtonFinder);
      expect(saveButtonWidget.onPressed, isNull);

      // Autor resolve explicitamente escolhendo Pública
      final dropdownFinder = find.byKey(const Key('edit_visibility_dropdown'));
      await tester.ensureVisible(dropdownFinder);
      await tester.tap(dropdownFinder);
      await tester.pumpAndSettle();

      final publicItem = find.text('Pública (visível a todos)').last;
      await tester.tap(publicItem);
      await tester.pumpAndSettle();

      // O aviso legado desaparece e o botão Salvar é reabilitado
      expect(find.byKey(const Key('legacy_privacy_notice')), findsNothing);
      final reenabledSaveButton = tester.widget<FilledButton>(saveButtonFinder);
      expect(reenabledSaveButton.onPressed, isNotNull);

      // Submete com sucesso
      await tester.tap(saveButtonFinder);
      await tester.pumpAndSettle();

      expect(fakeRepo.lastUpdateReviewId, 'rev-edit-test-1');
      expect(fakeRepo.lastUpdateInput?.isAnonymous, isTrue);
      expect(fakeRepo.lastUpdateInput?.visibility, 'PUBLIC');
    });

    testWidgets('7. preservação dos dados e mensagem orientativa diante de uma falha (HTTP 422)', (tester) async {
      configureLargeScreen(tester);
      final fakeRepo = FakeEditFeedRepository(baseReview: sampleReview);
      fakeRepo.exceptionToThrow = const ApiException(
        ProblemDetail(
          type: 'about:blank',
          title: 'Unprocessable Entity',
          status: 422,
          detail: 'Avaliações anônimas não podem ter visibilidade restrita a seguidores',
          code: 'ANONYMOUS_FOLLOWERS_VISIBILITY_NOT_ALLOWED',
        ),
      );

      await tester.pumpWidget(createTestWidget(
        review: sampleReview,
        repository: fakeRepo,
      ));
      await tester.pumpAndSettle();

      // Altera o texto da experiência
      final textField = find.byKey(const Key('edit_experience_text_field'));
      await tester.enterText(textField, 'Texto que deve ser preservado mesmo após falha 422');

      final saveButton = find.byKey(const Key('submit_edit_button'));
      await tester.ensureVisible(saveButton);
      await tester.tap(saveButton);
      await tester.pumpAndSettle();

      // Mensagem orientativa amigável mapeada de ANONYMOUS_FOLLOWERS_VISIBILITY_NOT_ALLOWED
      expect(find.text(ReviewPrivacyRules.incompatibleCombinationMessage), findsWidgets);

      // Conteúdo digitado pelo usuário preservado no formulário
      expect(find.text('Texto que deve ser preservado mesmo após falha 422'), findsOneWidget);
    });

    testWidgets('8. nenhuma requisição inválida enviada ao repositório para registro legado incompatível', (tester) async {
      configureLargeScreen(tester);
      final legacyReview = sampleReview.copyWith(isAnonymous: true, visibility: 'FOLLOWERS');
      final fakeRepo = FakeEditFeedRepository(baseReview: legacyReview);

      await tester.pumpWidget(createTestWidget(
        review: legacyReview,
        repository: fakeRepo,
      ));
      await tester.pumpAndSettle();

      // Botão de salvar no AppBar e no fim da tela ambos desabilitados
      final appBarSaveButton = tester.widget<TextButton>(find.byKey(const Key('save_review_edit_button')));
      expect(appBarSaveButton.onPressed, isNull);

      final bottomSaveButton = tester.widget<FilledButton>(find.byKey(const Key('submit_edit_button')));
      expect(bottomSaveButton.onPressed, isNull);

      // Nenhuma chamada enviada ao repositório
      expect(fakeRepo.lastUpdateInput, isNull);
      expect(fakeRepo.lastUpdateReviewId, isNull);
    });
  });
}
