import 'dart:async';
import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:rewit_mobile/app/app.dart';
import 'package:rewit_mobile/app/config/app_config.dart';
import 'package:rewit_mobile/app/router/app_router.dart';
import 'package:rewit_mobile/app/theme/app_theme.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/core/network/http_client.dart';
import 'package:rewit_mobile/core/network/session_refresher.dart';
import 'package:rewit_mobile/core/network/token_refreshing_client.dart';
import 'package:rewit_mobile/core/storage/token_storage.dart';
import 'package:rewit_mobile/features/auth/data/models/auth_user_dto.dart';
import 'package:rewit_mobile/features/auth/domain/entities/auth_state.dart';
import 'package:rewit_mobile/features/auth/domain/repositories/auth_repository.dart';
import 'package:rewit_mobile/features/auth/domain/services/social_sign_in_service.dart';
import 'package:rewit_mobile/features/auth/presentation/screens/login_screen.dart';
import 'package:rewit_mobile/features/auth/presentation/state/auth_notifier.dart';
import 'package:rewit_mobile/features/feed/domain/entities/feed_entities.dart';
import 'package:rewit_mobile/features/feed/domain/repositories/feed_repository.dart';
import 'package:rewit_mobile/features/feed/presentation/state/feed_notifier.dart';
import 'package:rewit_mobile/features/feed/presentation/state/feed_state.dart';
import 'package:rewit_mobile/features/reports/data/repositories/report_repository_impl.dart';
import 'package:rewit_mobile/features/reports/domain/entities/report_entities.dart';
import 'package:rewit_mobile/features/reports/domain/repositories/report_repository.dart';
import 'package:rewit_mobile/features/reports/presentation/widgets/report_review_dialog.dart';
import 'package:rewit_mobile/features/review_detail/domain/entities/helpful_result.dart';
import 'package:rewit_mobile/features/review_detail/domain/entities/review_media.dart';
import 'package:rewit_mobile/features/review_detail/domain/entities/update_review_input.dart';
import 'package:rewit_mobile/features/review_detail/presentation/screens/review_detail_screen.dart';
import 'package:rewit_mobile/shared/widgets/error_banner.dart';
import 'package:rewit_mobile/shared/widgets/error_view.dart';

class _FakeReportRepository implements ReportRepository {
  int callCount = 0;
  String? lastReviewId;
  ReportReason? lastReason;
  String? lastDetail;
  Future<ReviewReportReceipt> Function({
    required String reviewId,
    required ReportReason reason,
    String? detail,
  })? customHandler;

  ReviewReportOutcome nextOutcome = ReviewReportOutcome.created;
  String nextStatus = 'PENDING';

  @override
  Future<ReviewReportReceipt> reportReview({
    required String reviewId,
    required ReportReason reason,
    String? detail,
  }) async {
    callCount++;
    lastReviewId = reviewId;
    lastReason = reason;
    lastDetail = detail;

    if (customHandler != null) {
      return customHandler!(
        reviewId: reviewId,
        reason: reason,
        detail: detail,
      );
    }

    return ReviewReportReceipt(
      id: '7c9e6679-7425-40de-944b-e07fc1f90ae7',
      reviewId: reviewId,
      reason: reason,
      status: nextStatus,
      createdAt: DateTime.utc(2026, 11, 15, 19, 0),
      outcome: nextOutcome,
    );
  }
}

class _FakeFeedRepository implements FeedRepository {
  int getReviewByIdCount = 0;
  late FeedReview review;
  Future<FeedReview> Function(String reviewId)? customGetReviewById;

  _FakeFeedRepository({FeedReview? initialReview}) {
    review = initialReview ??
        FeedReview(
          id: '3fa85f64-5717-4562-b3fc-2c963f66afa6',
          author: const FeedAuthor(
            id: 'author-uuid-1',
            handle: 'outro_usuario',
            displayName: 'Outro Usuário',
            isAnonymous: false,
          ),
          experienceText: 'Avaliação pública de outro usuário.',
          visibility: 'PUBLIC',
          status: 'ACTIVE',
          isAnonymous: false,
          isVerifiedOnSite: false,
          helpfulCount: 2,
          isHelpfulByMe: false,
          isMine: false,
          targets: [
            FeedTarget(
              id: 't-1',
              targetId: 'place-1',
              targetName: 'Café Central',
              rating: 4.0,
              createdAt: DateTime.utc(2026, 11, 15, 12, 0),
            ),
          ],
          createdAt: DateTime.utc(2026, 11, 15, 12, 0),
        );
  }

  @override
  Future<FeedReview> getReviewById(String reviewId) async {
    getReviewByIdCount++;
    if (customGetReviewById != null) {
      return customGetReviewById!(reviewId);
    }
    return review;
  }

  @override
  Future<FeedPage> getFeed({int page = 0, int size = 10}) async =>
      FeedPage(items: [review], page: 0, size: 10, windowSize: 1, totalPages: 1);

  @override
  Future<FeedPage> getFollowingFeed({int page = 0, int size = 10}) =>
      getFeed(page: page, size: size);

  @override
  Future<HelpfulResult> toggleHelpful(
    String reviewId, {
    required bool currentlyHelpful,
  }) async =>
      const HelpfulResult(helpful: true, helpfulCount: 3);

  @override
  Future<List<ReviewMediaItem>> getReviewMedia(String reviewId) async =>
      const [];

  @override
  Future<FeedReview> updateReview(
    String reviewId,
    UpdateReviewInput input,
  ) async =>
      review;

  @override
  Future<void> deleteReview(String reviewId) async {}
}

class _StubAuthRepository implements AuthRepository {
  @override
  Future<bool> hasStoredSession() async => true;

  @override
  Future<AuthUserDto> getMe() async => const AuthUserDto(
        id: 'viewer-uuid-99',
        email: 'viewer@rewit.test',
        handle: 'viewer',
        displayName: 'Viewer',
      );

  @override
  Future<Authenticated> login({
    required String email,
    required String password,
  }) =>
      throw UnimplementedError();

  @override
  Future<Authenticated> register({
    required String displayName,
    required String handle,
    required String email,
    required String password,
  }) =>
      throw UnimplementedError();

  @override
  Future<String> requestSocialNonce() => throw UnimplementedError();

  @override
  Future<Authenticated> signInWithSocial({
    required SocialAuthProvider provider,
    required String idToken,
    required String nonce,
  }) =>
      throw UnimplementedError();

  @override
  Future<Authenticated> reactivateWithSocial({
    required SocialAuthProvider provider,
    required String idToken,
    required String nonce,
  }) =>
      throw UnimplementedError();

  @override
  Future<Authenticated> reactivate({
    required String email,
    required String password,
  }) =>
      throw UnimplementedError();

  @override
  Future<void> deactivateAccount() async {}

  @override
  Future<void> changePassword({
    required String currentPassword,
    required String newPassword,
  }) async {}

  @override
  Future<Authenticated> refreshTokens() => throw UnimplementedError();

  int logoutCalls = 0;

  @override
  Future<void> logout() async {
    logoutCalls++;
  }
}

class _CountingNavigatorObserver extends NavigatorObserver {
  int didPopCount = 0;
  final List<Route<dynamic>> poppedRoutes = [];

  @override
  void didPop(Route<dynamic> route, Route<dynamic>? previousRoute) {
    didPopCount++;
    poppedRoutes.add(route);
    super.didPop(route, previousRoute);
  }
}

void main() {
  const targetReviewUuid = '3fa85f64-5717-4562-b3fc-2c963f66afa6';

  late _FakeReportRepository reportRepo;
  late _FakeFeedRepository feedRepo;

  setUp(() {
    reportRepo = _FakeReportRepository();
    feedRepo = _FakeFeedRepository();
  });

  Future<void> pumpReviewDetail(
    WidgetTester tester, {
    FeedReview? review,
    String? currentUserId = 'viewer-uuid-99',
    bool? isAuthor,
    ThemeData? theme,
    bool useNullFeedRepo = false,
    bool useNullReportRepo = false,
    FeedNotifier? feedNotifier,
    AuthNotifier? authNotifier,
    bool settle = true,
  }) async {
    final activeReview = review ?? feedRepo.review;
    feedRepo.review = activeReview;

    await tester.pumpWidget(
      MaterialApp(
        theme: theme ?? AppTheme.lightTheme,
        home: ReviewDetailScreen(
          key: UniqueKey(),
          reviewId: activeReview.id,
          initialReview: activeReview,
          feedRepository: useNullFeedRepo ? null : feedRepo,
          feedNotifier: feedNotifier,
          reportRepository: useNullReportRepo ? null : reportRepo,
          currentUserId: currentUserId,
          isAuthor: isAuthor,
          authNotifier: authNotifier,
        ),
      ),
    );
    if (settle) {
      await tester.pumpAndSettle();
    } else {
      await tester.pump();
    }
  }

  Future<void> openReportDialogFromMenu(WidgetTester tester) async {
    final menuButton = find.byKey(const Key('review_detail_more_menu_button'));
    expect(menuButton, findsOneWidget);
    await tester.tap(menuButton);
    await tester.pumpAndSettle();

    final reportItem = find.byKey(const Key('review_detail_report_action'));
    expect(reportItem, findsOneWidget);
    expect(find.text('Denunciar avaliação'), findsOneWidget);

    await tester.tap(reportItem);
    await tester.pumpAndSettle();
  }

  group('Denúncia de Avaliações - Visibilidade, Formulário e Tratamento de Erros', () {
    testWidgets('1. A ação aparece para usuário que pode denunciar avaliação ACTIVE de outra pessoa', (tester) async {
      await pumpReviewDetail(tester);

      expect(
        find.byKey(const Key('review_detail_more_menu_button')),
        findsOneWidget,
      );
      await tester.tap(find.byKey(const Key('review_detail_more_menu_button')));
      await tester.pumpAndSettle();

      expect(
        find.byKey(const Key('review_detail_report_action')),
        findsOneWidget,
      );
      expect(find.text('Denunciar avaliação'), findsOneWidget);
    });

    testWidgets('2. A ação NÃO aparece para o próprio autor (isMine=true ou currentUserId coincidente)', (tester) async {
      // Caso A: isMine = true (inclusive avaliação anônima própria)
      await pumpReviewDetail(
        tester,
        review: feedRepo.review.copyWith(isMine: true, isAnonymous: true),
        currentUserId: 'viewer-uuid-99',
      );
      expect(
        find.byKey(const Key('review_detail_more_menu_button')),
        findsNothing,
      );

      // Caso B: isMine = null, mas currentUserId == author.id
      final publicWithoutIsMine = FeedReview(
        id: feedRepo.review.id,
        author: feedRepo.review.author,
        experienceText: feedRepo.review.experienceText,
        visibility: feedRepo.review.visibility,
        status: feedRepo.review.status,
        isAnonymous: false,
        isVerifiedOnSite: feedRepo.review.isVerifiedOnSite,
        helpfulCount: feedRepo.review.helpfulCount,
        isHelpfulByMe: feedRepo.review.isHelpfulByMe,
        isMine: null,
        targets: feedRepo.review.targets,
        createdAt: feedRepo.review.createdAt,
      );
      await pumpReviewDetail(
        tester,
        review: publicWithoutIsMine,
        currentUserId: 'author-uuid-1',
      );
      expect(
        find.byKey(const Key('review_detail_more_menu_button')),
        findsNothing,
      );
    });

    testWidgets('3. A ação NÃO aparece para avaliação não ativa (UNDER_REVIEW ou REMOVED)', (tester) async {
      await pumpReviewDetail(
        tester,
        review: feedRepo.review.copyWith(status: 'UNDER_REVIEW', isMine: false),
      );
      expect(
        find.byKey(const Key('review_detail_more_menu_button')),
        findsNothing,
      );

      await pumpReviewDetail(
        tester,
        review: feedRepo.review.copyWith(status: 'REMOVED', isMine: false),
      );
      expect(
        find.byKey(const Key('review_detail_more_menu_button')),
        findsNothing,
      );
    });

    testWidgets('4 e 6. Seleção de motivo funciona e envia o UUID correto da avaliação e o código do motivo', (tester) async {
      await pumpReviewDetail(tester);
      await openReportDialogFromMenu(tester);

      // Verifica que todos os 6 motivos estão visíveis em português
      for (final reason in ReportReason.values) {
        expect(find.text(reason.label), findsOneWidget);
      }

      // Seleciona FRAUD
      await tester.tap(find.byKey(const Key('report_reason_FRAUD')));
      await tester.pumpAndSettle();

      await tester.enterText(
        find.byKey(const Key('report_review_detail_input')),
        '  Avaliação fraudulenta combinada  ',
      );
      await tester.pump();

      await tester.tap(find.byKey(const Key('report_review_submit_button')));
      await tester.pumpAndSettle();

      expect(reportRepo.callCount, 1);
      expect(reportRepo.lastReviewId, targetReviewUuid);
      expect(reportRepo.lastReason, ReportReason.fraud);
      expect(reportRepo.lastDetail, 'Avaliação fraudulenta combinada');
    });

    testWidgets('5. O campo de detalhe respeita o limite de 500 caracteres', (tester) async {
      await pumpReviewDetail(tester);
      await openReportDialogFromMenu(tester);

      final textField = tester.widget<TextField>(
        find.byKey(const Key('report_review_detail_input')),
      );
      expect(textField.maxLength, 500);

      // Digitar 520 caracteres trunca em 500 pelo formatter nativo de maxLength
      final text520 = 'x' * 520;
      await tester.enterText(
        find.byKey(const Key('report_review_detail_input')),
        text520,
      );
      await tester.pump();

      expect(textField.controller!.text.length, 500);

      // Mesmo se o controller receber > 500 programaticamente, o formulário bloqueia antes de enviar
      textField.controller!.text = 'y' * 505;
      await tester.pump();

      await tester.tap(find.byKey(const Key('report_review_submit_button')));
      await tester.pumpAndSettle();

      expect(reportRepo.callCount, 0);
      expect(
        find.text('O detalhe da denúncia não pode exceder 500 caracteres.'),
        findsOneWidget,
      );
    });

    testWidgets('7. Cancelar fecha o diálogo sem chamar o repositório', (tester) async {
      await pumpReviewDetail(tester);
      await openReportDialogFromMenu(tester);

      await tester.tap(find.byKey(const Key('report_reason_SPAM')));
      await tester.enterText(
        find.byKey(const Key('report_review_detail_input')),
        'Texto preenchido antes de cancelar',
      );
      await tester.pump();

      await tester.tap(find.byKey(const Key('report_review_cancel_button')));
      await tester.pumpAndSettle();

      expect(find.byType(ReportReviewDialog), findsNothing);
      expect(reportRepo.callCount, 0);
    });

    testWidgets('8. Estado de loading impede duplo envio, desabilita campos e bloqueia PopScope', (tester) async {
      final completer = Completer<ReviewReportReceipt>();
      reportRepo.customHandler = ({
        required reviewId,
        required reason,
        detail,
      }) =>
          completer.future;

      await pumpReviewDetail(tester);
      await openReportDialogFromMenu(tester);

      await tester.tap(find.byKey(const Key('report_review_submit_button')));
      await tester.pump();

      // Apenas 1 chamada disparada
      expect(reportRepo.callCount, 1);

      // Botões de enviar e cancelar e input desabilitados durante o envio
      final submitButton = tester.widget<FilledButton>(
        find.byKey(const Key('report_review_submit_button')),
      );
      final cancelButton = tester.widget<TextButton>(
        find.byKey(const Key('report_review_cancel_button')),
      );
      final detailInput = tester.widget<TextField>(
        find.byKey(const Key('report_review_detail_input')),
      );
      expect(submitButton.onPressed, isNull);
      expect(cancelButton.onPressed, isNull);
      expect(detailInput.enabled, isFalse);

      // Tentativa de fechar pelo gesto/botão voltar do sistema é bloqueada pelo PopScope
      await tester.binding.handlePopRoute();
      await tester.pump();
      expect(find.byType(ReportReviewDialog), findsOneWidget);

      // Conclui envio
      completer.complete(
        ReviewReportReceipt(
          id: 'rep-1',
          reviewId: targetReviewUuid,
          reason: ReportReason.inappropriateContent,
          status: 'PENDING',
          createdAt: DateTime.utc(2026, 11, 15, 19, 0),
          outcome: ReviewReportOutcome.created,
        ),
      );
      await tester.pumpAndSettle();

      expect(find.byType(ReportReviewDialog), findsNothing);
    });

    testWidgets('9. HTTP 201 (created) encerra o diálogo e exibe feedback de nova denúncia enviada', (tester) async {
      reportRepo.nextOutcome = ReviewReportOutcome.created;

      await pumpReviewDetail(tester);
      await openReportDialogFromMenu(tester);

      await tester.tap(find.byKey(const Key('report_review_submit_button')));
      await tester.pumpAndSettle();

      expect(find.byType(ReportReviewDialog), findsNothing);
      expect(
        find.text(
          'Denúncia enviada para análise. Obrigado por ajudar a manter a comunidade segura.',
        ),
        findsOneWidget,
      );
    });

    testWidgets('10a. HTTP 200 com status PENDING encerra o diálogo e informa que a denúncia aguarda análise', (tester) async {
      reportRepo.nextOutcome = ReviewReportOutcome.alreadyReported;
      reportRepo.nextStatus = 'PENDING';

      await pumpReviewDetail(tester);
      await openReportDialogFromMenu(tester);

      await tester.tap(find.byKey(const Key('report_review_submit_button')));
      await tester.pumpAndSettle();

      expect(find.byType(ReportReviewDialog), findsNothing);
      expect(
        find.text(
          'Você já denunciou esta avaliação anteriormente. Sua denúncia aguarda análise.',
        ),
        findsOneWidget,
      );
      expect(
        find.textContaining('Denúncia enviada para análise'),
        findsNothing,
      );
    });

    testWidgets('10b. HTTP 200 com status REJECTED exibe mensagem neutra sem afirmar análise pendente', (tester) async {
      reportRepo.nextOutcome = ReviewReportOutcome.alreadyReported;
      reportRepo.nextStatus = 'REJECTED';

      await pumpReviewDetail(tester);
      await openReportDialogFromMenu(tester);

      await tester.tap(find.byKey(const Key('report_review_submit_button')));
      await tester.pumpAndSettle();

      expect(find.byType(ReportReviewDialog), findsNothing);
      expect(
        find.text('Você já registrou uma denúncia anterior para esta avaliação.'),
        findsOneWidget,
      );
      expect(find.textContaining('em análise'), findsNothing);
      expect(find.textContaining('aguarda análise'), findsNothing);
    });

    testWidgets('10c. HTTP 200 com status ACCEPTED informa que a denúncia anterior já foi processada', (tester) async {
      reportRepo.nextOutcome = ReviewReportOutcome.alreadyReported;
      reportRepo.nextStatus = 'ACCEPTED';

      await pumpReviewDetail(tester);
      await openReportDialogFromMenu(tester);

      await tester.tap(find.byKey(const Key('report_review_submit_button')));
      await tester.pumpAndSettle();

      expect(find.byType(ReportReviewDialog), findsNothing);
      expect(
        find.text(
          'Você já denunciou esta avaliação anteriormente e a denúncia já foi processada pela moderação.',
        ),
        findsOneWidget,
      );
    });

    testWidgets('10d. HTTP 200 com status desconhecido trata de forma neutra sem prometer análise pendente', (tester) async {
      reportRepo.nextOutcome = ReviewReportOutcome.alreadyReported;
      reportRepo.nextStatus = 'UNKNOWN_CUSTOM_STATUS';

      await pumpReviewDetail(tester);
      await openReportDialogFromMenu(tester);

      await tester.tap(find.byKey(const Key('report_review_submit_button')));
      await tester.pumpAndSettle();

      expect(find.byType(ReportReviewDialog), findsNothing);
      expect(
        find.text('Você já registrou uma denúncia anterior para esta avaliação.'),
        findsOneWidget,
      );
      expect(find.textContaining('aguarda análise'), findsNothing);
    });

    testWidgets('11a. Trata 400 SELF_REPORT_FORBIDDEN com mensagem específica', (tester) async {
      reportRepo.customHandler = ({
        required reviewId,
        required reason,
        detail,
      }) async {
        throw const ApiException(
          ProblemDetail(
            type: 'about:blank',
            title: 'Bad Request',
            status: 400,
            code: 'SELF_REPORT_FORBIDDEN',
            detail: 'O autor não pode denunciar a própria avaliação',
          ),
        );
      };

      await pumpReviewDetail(tester);
      await openReportDialogFromMenu(tester);

      await tester.tap(find.byKey(const Key('report_review_submit_button')));
      await tester.pumpAndSettle();

      expect(find.byType(ReportReviewDialog), findsOneWidget);
      expect(
        find.text('Você não pode denunciar a sua própria avaliação.'),
        findsOneWidget,
      );
    });

    testWidgets('11b. Trata 400 VALIDATION_ERROR e MALFORMED_REQUEST preservando dados preenchidos', (tester) async {
      reportRepo.customHandler = ({
        required reviewId,
        required reason,
        detail,
      }) async {
        throw const ApiException(
          ProblemDetail(
            type: 'about:blank',
            title: 'Erro de validação',
            status: 400,
            code: 'VALIDATION_ERROR',
            detail: 'Erro de validação nos campos',
            fieldErrors: {
              'detail': 'O detalhe contém caracteres inválidos.',
            },
          ),
        );
      };

      await pumpReviewDetail(tester);
      await openReportDialogFromMenu(tester);

      await tester.tap(find.byKey(const Key('report_reason_HATE_SPEECH')));
      await tester.enterText(
        find.byKey(const Key('report_review_detail_input')),
        'Detalhe preservado para correção',
      );
      await tester.pump();

      await tester.tap(find.byKey(const Key('report_review_submit_button')));
      await tester.pumpAndSettle();

      expect(
        find.text('O detalhe contém caracteres inválidos.'),
        findsOneWidget,
      );
      final detailField = tester.widget<TextField>(
        find.byKey(const Key('report_review_detail_input')),
      );
      expect(detailField.controller!.text, 'Detalhe preservado para correção');
    });

    testWidgets('11c. Trata 401 ACCOUNT_DISABLED mantendo o formulário inerte sem pop local nem banner contraditório', (tester) async {
      final observer = _CountingNavigatorObserver();
      reportRepo.customHandler = ({
        required reviewId,
        required reason,
        detail,
      }) async {
        throw const ApiException(
          ProblemDetail(
            type: 'about:blank',
            title: 'Unauthorized',
            status: 401,
            code: 'ACCOUNT_DISABLED',
            detail: 'Conta desativada',
          ),
        );
      };

      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.lightTheme,
          navigatorObservers: [observer],
          home: ReviewDetailScreen(
            reviewId: feedRepo.review.id,
            initialReview: feedRepo.review,
            feedRepository: feedRepo,
            reportRepository: reportRepo,
            currentUserId: 'viewer-uuid-99',
          ),
        ),
      );
      await tester.pumpAndSettle();
      await openReportDialogFromMenu(tester);

      final popsBefore = observer.didPopCount;
      await tester.tap(find.byKey(const Key('report_review_submit_button')));
      await tester.pump();
      await tester.pump();

      expect(observer.didPopCount, popsBefore);
      expect(find.byType(ReportReviewDialog), findsOneWidget);
      expect(
        find.text('Sua conta está desativada e não pode enviar denúncias.'),
        findsNothing,
      );
      expect(
        find.text('Sua sessão expirou. Faça login novamente para continuar.'),
        findsNothing,
      );
      final submitButton = tester.widget<FilledButton>(
        find.byKey(const Key('report_review_submit_button')),
      );
      expect(submitButton.onPressed, isNull);
    });

    testWidgets('11d. Trata 403 FORBIDDEN informando restrição de acesso', (tester) async {
      reportRepo.customHandler = ({
        required reviewId,
        required reason,
        detail,
      }) async {
        throw const ApiException(
          ProblemDetail(
            type: 'about:blank',
            title: 'Forbidden',
            status: 403,
            code: 'FORBIDDEN',
            detail: 'Acesso negado para denunciar esta avaliação',
          ),
        );
      };

      await pumpReviewDetail(tester);
      await openReportDialogFromMenu(tester);

      await tester.tap(find.byKey(const Key('report_review_submit_button')));
      await tester.pumpAndSettle();

      expect(
        find.text(
          'Não foi possível realizar a denúncia por restrição de acesso a esta avaliação.',
        ),
        findsOneWidget,
      );
    });

    testWidgets('11e. Trata 404 REVIEW_NOT_FOUND fechando o diálogo, removendo a avaliação antiga e entrando em estado indisponível', (tester) async {
      reportRepo.customHandler = ({
        required reviewId,
        required reason,
        detail,
      }) async {
        throw const ApiException(
          ProblemDetail(
            type: 'about:blank',
            title: 'Not Found',
            status: 404,
            code: 'REVIEW_NOT_FOUND',
            detail: 'Avaliação não encontrada',
          ),
        );
      };

      final feedNotifier = FeedNotifier(feedRepository: feedRepo);
      await feedNotifier.loadInitial();
      expect(
        (feedNotifier.state as FeedSuccess)
            .reviews
            .any((r) => r.id == targetReviewUuid),
        isTrue,
      );

      await pumpReviewDetail(tester, feedNotifier: feedNotifier);
      final loadsBeforeReport = feedRepo.getReviewByIdCount;

      await openReportDialogFromMenu(tester);
      await tester.tap(find.byKey(const Key('report_review_submit_button')));
      await tester.pumpAndSettle();

      // Diálogo foi fechado, avaliação antiga sumiu da tela e ação de denúncia foi removida
      expect(find.byType(ReportReviewDialog), findsNothing);
      expect(find.byType(ErrorView), findsOneWidget);
      expect(find.text('Avaliação indisponível'), findsOneWidget);
      expect(find.text('Esta avaliação não está mais disponível.'), findsOneWidget);
      expect(find.text('Avaliação pública de outro usuário.'), findsNothing);
      expect(find.byKey(const Key('review_detail_more_menu_button')), findsNothing);
      expect(
        find.text('Não foi possível denunciar: a avaliação não está mais disponível.'),
        findsOneWidget,
      );
      // Não dispara recarga redundante nem depende do feedRepository para marcar indisponibilidade
      expect(feedRepo.getReviewByIdCount, loadsBeforeReport);
      expect(feedNotifier.state, isA<FeedEmpty>());
      feedNotifier.dispose();
    });

    testWidgets('11e2. 404 REVIEW_NOT_FOUND com feedRepository nulo também transiciona para indisponível e devolve resultado ao voltar', (tester) async {
      reportRepo.customHandler = ({
        required reviewId,
        required reason,
        detail,
      }) async {
        throw const ApiException(
          ProblemDetail(
            type: 'about:blank',
            title: 'Not Found',
            status: 404,
            code: 'REVIEW_NOT_FOUND',
            detail: 'Avaliação não encontrada',
          ),
        );
      };

      Object? poppedResult;
      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.lightTheme,
          home: Builder(
            builder: (context) => TextButton(
              onPressed: () async {
                poppedResult = await Navigator.of(context).push(
                  MaterialPageRoute(
                    builder: (_) => ReviewDetailScreen(
                      reviewId: targetReviewUuid,
                      initialReview: feedRepo.review,
                      feedRepository: null,
                      reportRepository: reportRepo,
                      currentUserId: 'viewer-uuid-99',
                    ),
                  ),
                );
              },
              child: const Text('Abrir'),
            ),
          ),
        ),
      );

      await tester.tap(find.text('Abrir'));
      await tester.pumpAndSettle();

      await openReportDialogFromMenu(tester);
      await tester.tap(find.byKey(const Key('report_review_submit_button')));
      await tester.pumpAndSettle();

      expect(find.byType(ReportReviewDialog), findsNothing);
      expect(find.text('Avaliação indisponível'), findsOneWidget);
      expect(find.byKey(const Key('review_detail_more_menu_button')), findsNothing);

      await tester.tap(find.byType(BackButton));
      await tester.pumpAndSettle();

      expect(
        poppedResult,
        {'deleted': true, 'unavailable': true, 'reviewId': targetReviewUuid},
      );
    });

    testWidgets('11e3. Reload canônico com 404 REVIEW_NOT_FOUND substitui initialReview obsoleta por estado indisponível', (tester) async {
      feedRepo.customGetReviewById = (_) async {
        throw const ApiException(
          ProblemDetail(
            type: 'about:blank',
            title: 'Not Found',
            status: 404,
            code: 'REVIEW_NOT_FOUND',
            detail: 'Avaliação removida',
          ),
        );
      };

      await pumpReviewDetail(tester);

      expect(find.text('Avaliação indisponível'), findsOneWidget);
      expect(find.text('Esta avaliação não está mais disponível.'), findsOneWidget);
      expect(find.text('Avaliação pública de outro usuário.'), findsNothing);
      expect(find.byKey(const Key('review_detail_more_menu_button')), findsNothing);
    });

    testWidgets('11f. Trata 429 RATE_LIMIT_EXCEEDED utilizando Retry-After e ErrorBanner.formatRetryAfter', (tester) async {
      reportRepo.customHandler = ({
        required reviewId,
        required reason,
        detail,
      }) async {
        throw const ApiException(
          ProblemDetail(
            type: 'about:blank',
            title: 'Too Many Requests',
            status: 429,
            code: 'RATE_LIMIT_EXCEEDED',
            detail: 'Limite de denúncias excedido.',
          ),
          retryAfterSeconds: 35,
        );
      };

      await pumpReviewDetail(tester);
      await openReportDialogFromMenu(tester);

      await tester.tap(find.byKey(const Key('report_review_submit_button')));
      await tester.pumpAndSettle();

      expect(
        find.text('Você atingiu o limite de denúncias em pouco tempo.'),
        findsOneWidget,
      );
      expect(
        find.text(ErrorBanner.formatRetryAfter(35)),
        findsOneWidget,
      );
    });

    testWidgets('12. NetworkException exibe erro de conexão e preserva motivo e detalhes para nova tentativa', (tester) async {
      var shouldFailNetwork = true;
      reportRepo.customHandler = ({
        required reviewId,
        required reason,
        detail,
      }) async {
        if (shouldFailNetwork) {
          throw const NetworkException('Falha de conexão com o servidor.');
        }
        return ReviewReportReceipt(
          id: 'rep-after-retry',
          reviewId: reviewId,
          reason: reason,
          status: 'PENDING',
          createdAt: DateTime.utc(2026, 11, 15, 19, 10),
          outcome: ReviewReportOutcome.created,
        );
      };

      await pumpReviewDetail(tester);
      await openReportDialogFromMenu(tester);

      await tester.tap(find.byKey(const Key('report_reason_MISINFORMATION')));
      await tester.enterText(
        find.byKey(const Key('report_review_detail_input')),
        'Endereço e horário falsos no relato',
      );
      await tester.pump();

      // 1ª tentativa: falha de rede
      await tester.tap(find.byKey(const Key('report_review_submit_button')));
      await tester.pumpAndSettle();

      expect(
        find.text(
          'Sem conexão com o servidor. Verifique sua internet e tente novamente.',
        ),
        findsOneWidget,
      );

      // Dados continuam preservados no formulário
      final detailField = tester.widget<TextField>(
        find.byKey(const Key('report_review_detail_input')),
      );
      expect(
        detailField.controller!.text,
        'Endereço e horário falsos no relato',
      );

      // 2ª tentativa: sucesso sem precisar redigitar
      shouldFailNetwork = false;
      await tester.tap(find.byKey(const Key('report_review_submit_button')));
      await tester.pumpAndSettle();

      expect(find.byType(ReportReviewDialog), findsNothing);
      expect(reportRepo.lastReason, ReportReason.misinformation);
      expect(reportRepo.lastDetail, 'Endereço e horário falsos no relato');
      expect(
        find.text(
          'Denúncia enviada para análise. Obrigado por ajudar a manter a comunidade segura.',
        ),
        findsOneWidget,
      );
    });

    testWidgets('13. Renderiza corretamente nos temas claro e escuro', (tester) async {
      for (final theme in [AppTheme.lightTheme, AppTheme.darkTheme]) {
        await pumpReviewDetail(tester, theme: theme);
        await openReportDialogFromMenu(tester);

        expect(find.byType(ReportReviewDialog), findsOneWidget);
        expect(find.text('Denunciar avaliação'), findsOneWidget);
        expect(tester.takeException(), isNull);

        await tester.tap(find.byKey(const Key('report_review_cancel_button')));
        await tester.pumpAndSettle();
      }
    });

    testWidgets('14. Ação e diálogo possuem semântica de acessibilidade para leitor de tela', (tester) async {
      final semanticsHandle = tester.ensureSemantics();
      try {
        await pumpReviewDetail(tester);

        expect(find.byTooltip('Mais opções da avaliação'), findsOneWidget);

        await tester.tap(find.byTooltip('Mais opções da avaliação'));
        await tester.pumpAndSettle();

        expect(
          find.bySemanticsLabel('Denunciar avaliação'),
          findsWidgets,
        );
      } finally {
        semanticsHandle.dispose();
      }
    });

    testWidgets('15. AppRouter injeta reportRepository corretamente na rota ReviewDetailScreen', (tester) async {
      final authNotifier = AuthNotifier(authRepository: _StubAuthRepository());
      await authNotifier.checkAuthStatus();

      final router = AppRouter(
        authNotifier: authNotifier,
        feedRepository: feedRepo,
        reportRepository: reportRepo,
      );

      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.lightTheme,
          onGenerateRoute: router.onGenerateRoute,
          initialRoute: AppRouter.reviewDetail,
          onGenerateInitialRoutes: (initialRoute) => [
            router.onGenerateRoute(
              RouteSettings(
                name: initialRoute,
                arguments: feedRepo.review,
              ),
            ),
          ],
        ),
      );
      await tester.pumpAndSettle();

      await openReportDialogFromMenu(tester);
      await tester.tap(find.byKey(const Key('report_review_submit_button')));
      await tester.pumpAndSettle();

      expect(reportRepo.callCount, 1);
      expect(reportRepo.lastReviewId, targetReviewUuid);
      authNotifier.dispose();
    });
  });

  group('Denúncia de Avaliações - Autoria Fail-Closed, Sessão Expirada e Resiliência', () {
    FeedReview buildAnonymousFeedV2Review({bool? isMine}) {
      return FeedReview(
        id: targetReviewUuid,
        author: const FeedAuthor(
          id: null,
          handle: null,
          displayName: 'Anônimo',
          isAnonymous: true,
        ),
        experienceText: 'Avaliação anônima vinda do Feed V2.',
        visibility: 'PUBLIC',
        status: 'ACTIVE',
        isAnonymous: true,
        isVerifiedOnSite: true,
        helpfulCount: 3,
        isHelpfulByMe: false,
        isMine: isMine,
        targets: [
          FeedTarget(
            id: 't-anon',
            targetId: 'place-1',
            targetName: 'Café Central',
            rating: 4.5,
            createdAt: DateTime.utc(2026, 11, 15, 12, 0),
          ),
        ],
        createdAt: DateTime.utc(2026, 11, 15, 12, 0),
      );
    }

    testWidgets('16. Avaliação anônima com isMine == null mantém denúncia oculta e libera somente após isMine == false canônico', (tester) async {
      final canonicalCompleter = Completer<FeedReview>();
      final anonInitial = buildAnonymousFeedV2Review(isMine: null);
      feedRepo.customGetReviewById = (_) => canonicalCompleter.future;

      await pumpReviewDetail(
        tester,
        review: anonInitial,
        currentUserId: 'viewer-uuid-99',
        // Mesmo que widget.isAuthor seja passado como false, não deve confiar sem isMine
        isAuthor: false,
        settle: false,
      );

      // Enquanto detalhe canônico não retorna, denúncia permanece oculta
      expect(
        find.byKey(const Key('review_detail_more_menu_button')),
        findsNothing,
      );

      canonicalCompleter.complete(buildAnonymousFeedV2Review(isMine: false));
      await tester.pumpAndSettle();

      expect(
        find.byKey(const Key('review_detail_more_menu_button')),
        findsOneWidget,
      );
    });

    testWidgets('17. Avaliação anônima permanece com denúncia oculta após isMine == true ou falha no reload canônico', (tester) async {
      final anonInitial = buildAnonymousFeedV2Review(isMine: null);

      // Caso A: detalhe retorna isMine == true (própria avaliação anônima)
      feedRepo.customGetReviewById =
          (_) async => buildAnonymousFeedV2Review(isMine: true);
      await pumpReviewDetail(
        tester,
        review: anonInitial,
        currentUserId: 'viewer-uuid-99',
      );
      expect(
        find.byKey(const Key('review_detail_more_menu_button')),
        findsNothing,
      );

      // Caso B: reload canônico falha (rede/500) -> mantém fail-closed
      feedRepo.customGetReviewById =
          (_) async => throw const NetworkException('Sem conexão');
      await pumpReviewDetail(
        tester,
        review: anonInitial,
        currentUserId: 'viewer-uuid-99',
      );
      expect(
        find.byKey(const Key('review_detail_more_menu_button')),
        findsNothing,
      );
    });

    testWidgets('18. Fallback sem isMine só libera avaliação pública com author.id e currentUserId válidos e diferentes', (tester) async {
      final publicWithoutIsMine = FeedReview(
        id: targetReviewUuid,
        author: const FeedAuthor(
          id: 'author-uuid-1',
          handle: 'outro_usuario',
          displayName: 'Outro Usuário',
          isAnonymous: false,
        ),
        experienceText: 'Avaliação pública sem isMine.',
        visibility: 'PUBLIC',
        status: 'ACTIVE',
        isAnonymous: false,
        isVerifiedOnSite: false,
        helpfulCount: 2,
        isHelpfulByMe: false,
        isMine: null,
        targets: const [],
        createdAt: DateTime.utc(2026, 11, 15, 12, 0),
      );

      // Caso A: UUIDs válidos e diferentes -> fallback seguro permite
      await pumpReviewDetail(
        tester,
        review: publicWithoutIsMine,
        currentUserId: 'viewer-uuid-99',
      );
      expect(
        find.byKey(const Key('review_detail_more_menu_button')),
        findsOneWidget,
      );

      // Caso B: currentUserId ausente -> bloqueia
      await pumpReviewDetail(
        tester,
        review: publicWithoutIsMine,
        currentUserId: null,
      );
      expect(
        find.byKey(const Key('review_detail_more_menu_button')),
        findsNothing,
      );

      // Caso C: author.id ausente -> bloqueia mesmo com isAuthor == false
      final missingAuthorId = FeedReview(
        id: targetReviewUuid,
        author: const FeedAuthor(
          id: null,
          handle: null,
          displayName: 'Usuário excluído',
          isAnonymous: false,
        ),
        experienceText: 'Avaliação sem id de autor.',
        visibility: 'PUBLIC',
        status: 'ACTIVE',
        isAnonymous: false,
        isVerifiedOnSite: false,
        helpfulCount: 0,
        isHelpfulByMe: false,
        isMine: null,
        targets: const [],
        createdAt: DateTime.utc(2026, 11, 15, 12, 0),
      );
      await pumpReviewDetail(
        tester,
        review: missingAuthorId,
        currentUserId: 'viewer-uuid-99',
        isAuthor: false,
      );
      expect(
        find.byKey(const Key('review_detail_more_menu_button')),
        findsNothing,
      );
    });

    testWidgets('19. Oculta ação de denúncia quando reportRepository é nulo e prioriza isMine canônico sobre widget.isAuthor', (tester) async {
      await pumpReviewDetail(
        tester,
        review: feedRepo.review.copyWith(isMine: false),
        useNullReportRepo: true,
      );
      expect(
        find.byKey(const Key('review_detail_more_menu_button')),
        findsNothing,
      );

      // isMine == true nunca permite denunciar mesmo se isAuthor: false
      await pumpReviewDetail(
        tester,
        review: feedRepo.review.copyWith(isMine: true),
        isAuthor: false,
      );
      expect(
        find.byKey(const Key('review_detail_more_menu_button')),
        findsNothing,
      );

      // widget.isAuthor == true com isMine == null bloqueia por cautela
      final unknownMineReview = FeedReview(
        id: targetReviewUuid,
        author: const FeedAuthor(
          id: 'author-uuid-1',
          handle: 'outro_usuario',
          displayName: 'Outro Usuário',
          isAnonymous: false,
        ),
        experienceText: 'Avaliação pública sem isMine.',
        visibility: 'PUBLIC',
        status: 'ACTIVE',
        isAnonymous: false,
        isVerifiedOnSite: false,
        helpfulCount: 2,
        isHelpfulByMe: false,
        isMine: null,
        targets: const [],
        createdAt: DateTime.utc(2026, 11, 15, 12, 0),
      );
      await pumpReviewDetail(
        tester,
        review: unknownMineReview,
        isAuthor: true,
      );
      expect(
        find.byKey(const Key('review_detail_more_menu_button')),
        findsNothing,
      );
    });

    testWidgets('19b. Dados contraditórios de autoria nunca liberam denúncia (isMine=false com isAuthor=true ou UUID coincidente)', (tester) async {
      // Caso A: backend envia isMine=false, mas widget.isAuthor=true -> bloqueia fail-closed
      await pumpReviewDetail(
        tester,
        review: feedRepo.review.copyWith(isMine: false),
        currentUserId: 'viewer-uuid-99',
        isAuthor: true,
      );
      expect(
        find.byKey(const Key('review_detail_more_menu_button')),
        findsNothing,
      );

      // Caso B: backend envia isMine=false, mas author.id público == currentUserId -> bloqueia fail-closed
      await pumpReviewDetail(
        tester,
        review: feedRepo.review.copyWith(isMine: false),
        currentUserId: 'author-uuid-1',
      );
      expect(
        find.byKey(const Key('review_detail_more_menu_button')),
        findsNothing,
      );

      // Caso C: widget.isAuthor=false com isMine=null e avaliação anônima -> não confia em isAuthor=false
      final anonUnknown = FeedReview(
        id: targetReviewUuid,
        author: const FeedAuthor(
          id: null,
          handle: null,
          displayName: 'Anônimo',
          isAnonymous: true,
        ),
        experienceText: 'Avaliação anônima.',
        visibility: 'PUBLIC',
        status: 'ACTIVE',
        isAnonymous: true,
        isVerifiedOnSite: false,
        helpfulCount: 0,
        isHelpfulByMe: false,
        isMine: null,
        targets: const [],
        createdAt: DateTime.utc(2026, 11, 15, 12, 0),
      );
      await pumpReviewDetail(
        tester,
        review: anonUnknown,
        currentUserId: 'viewer-uuid-99',
        isAuthor: false,
      );
      expect(
        find.byKey(const Key('review_detail_more_menu_button')),
        findsNothing,
      );
    });

    testWidgets('20. 401 AUTHENTICATION_REQUIRED fecha o diálogo de denúncia sem manter formulário ativo para reenvio', (tester) async {
      reportRepo.customHandler = ({
        required reviewId,
        required reason,
        detail,
      }) async {
        throw const ApiException(
          ProblemDetail(
            type: 'about:blank',
            title: 'Unauthorized',
            status: 401,
            code: 'AUTHENTICATION_REQUIRED',
            detail: 'Token expirado',
          ),
        );
      };

      await pumpReviewDetail(tester);
      await openReportDialogFromMenu(tester);

      await tester.tap(find.byKey(const Key('report_review_submit_button')));
      await tester.pumpAndSettle();

      expect(find.byType(ReportReviewDialog), findsNothing);
      expect(find.byKey(const Key('report_review_submit_button')), findsNothing);
    });

    testWidgets('21. Fluxo integrado RewitApp: 401 AUTHENTICATION_REQUIRED com refresh rejeitado fecha diálogo e redireciona ao LoginScreen sem duplicação', (tester) async {
      final tokenStorage = InMemoryTokenStorage(
        initialAccessToken:
            '${base64Url.encode(utf8.encode('{"alg":"HS256"}'))}.${base64Url.encode(utf8.encode('{"sub":"viewer-uuid-99"}'))}.sig',
        initialRefreshToken: 'refresh-old',
      );
      var refreshAttempts = 0;

      late final AuthNotifier authNotifier;
      final sessionRefresher = SessionRefresher(
        refreshAccessToken: () async {
          refreshAttempts++;
          return '';
        },
      );

      final mockClient = MockClient((request) async {
        if (request.url.path == '/api/v1/reports') {
          return http.Response(
            jsonEncode({
              'status': 401,
              'code': 'AUTHENTICATION_REQUIRED',
              'detail': 'Sessão expirada',
            }),
            401,
            headers: {'content-type': 'application/problem+json'},
          );
        }
        return http.Response(jsonEncode({'items': []}), 200);
      });

      final httpClient = RewitHttpClient(
        baseUrl: 'https://api.rewit.test',
        client: TokenRefreshingClient(
          inner: mockClient,
          sessionRefresher: sessionRefresher,
        ),
        tokenStorage: tokenStorage,
        onSessionExpired: () {
          unawaited(authNotifier.handleSessionExpired());
        },
      );

      authNotifier = AuthNotifier(
        authRepository: _StubAuthRepository(),
        sessionRefresher: sessionRefresher,
        clearLocalSession: tokenStorage.clearTokens,
      );
      await authNotifier.checkAuthStatus();

      final router = AppRouter(
        authNotifier: authNotifier,
        feedRepository: feedRepo,
        reportRepository: ReportRepositoryImpl(httpClient: httpClient),
      );

      await tester.pumpWidget(
        RewitApp(
          config: const AppConfig(apiBaseUrl: 'https://api.rewit.test'),
          router: router,
        ),
      );
      await tester.pumpAndSettle();

      final nav = Navigator.of(tester.element(find.byType(Scaffold).first));
      unawaited(
        nav.pushNamed(AppRouter.reviewDetail, arguments: feedRepo.review),
      );
      await tester.pumpAndSettle();

      await openReportDialogFromMenu(tester);
      await tester.tap(find.byKey(const Key('report_review_submit_button')));
      await tester.pumpAndSettle();

      expect(find.byType(ReportReviewDialog), findsNothing);
      expect(find.byType(ReviewDetailScreen), findsNothing);
      expect(find.byType(LoginScreen), findsOneWidget);
      expect(
        find.text('Sua sessão expirou. Faça login novamente.'),
        findsOneWidget,
      );
      expect(refreshAttempts, greaterThanOrEqualTo(1));
      expect(await tokenStorage.getAccessToken(), isNull);
      expect(
        tester.state<NavigatorState>(find.byType(Navigator).first).canPop(),
        isFalse,
      );

      authNotifier.dispose();
    });

    testWidgets('22. Erro inesperado no envio restaura o formulário sem travar em loading infinito', (tester) async {
      reportRepo.customHandler = ({
        required reviewId,
        required reason,
        detail,
      }) async {
        throw StateError('Falha inesperada de serialização');
      };

      await pumpReviewDetail(tester);
      await openReportDialogFromMenu(tester);

      await tester.tap(find.byKey(const Key('report_review_submit_button')));
      await tester.pumpAndSettle();

      expect(find.byType(ReportReviewDialog), findsOneWidget);
      expect(
        find.text(
          'Não foi possível concluir a denúncia no momento. Tente novamente.',
        ),
        findsOneWidget,
      );
      final submitButton = tester.widget<FilledButton>(
        find.byKey(const Key('report_review_submit_button')),
      );
      expect(submitButton.onPressed, isNotNull);
    });

    testWidgets('23. Refresh rejected: diálogo não executa pop local nem libera formulário, aguardando desempilhamento global sem duplicação', (tester) async {
      // Parte A: verificação isolada com NavigatorObserver comprovando ausência de pop local após rejected
      final observer = _CountingNavigatorObserver();
      final rejectedNotifier = AuthNotifier(
        authRepository: _StubAuthRepository(),
        sessionRefresher: SessionRefresher(refreshAccessToken: () async => ''),
        clearLocalSession: () async {},
      );
      await rejectedNotifier.checkAuthStatus();

      reportRepo.customHandler = ({
        required reviewId,
        required reason,
        detail,
      }) async {
        unawaited(rejectedNotifier.handleSessionExpired());
        throw const ApiException(
          ProblemDetail(
            type: 'about:blank',
            title: 'Unauthorized',
            status: 401,
            code: 'AUTHENTICATION_REQUIRED',
            detail: 'Token expirado',
          ),
        );
      };

      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.lightTheme,
          navigatorObservers: [observer],
          home: ReviewDetailScreen(
            key: UniqueKey(),
            reviewId: feedRepo.review.id,
            initialReview: feedRepo.review,
            feedRepository: feedRepo,
            reportRepository: reportRepo,
            currentUserId: 'viewer-uuid-99',
            authNotifier: rejectedNotifier,
          ),
        ),
      );
      await tester.pumpAndSettle();
      await openReportDialogFromMenu(tester);

      final popsBeforeSubmit = observer.didPopCount;
      await tester.tap(find.byKey(const Key('report_review_submit_button')));
      await tester.pump();
      await tester.pump();

      // Não executou Navigator.pop() local nem liberou o formulário ao receber rejected
      expect(observer.didPopCount, popsBeforeSubmit);
      expect(find.byType(ReportReviewDialog), findsOneWidget);
      final lockedSubmit = tester.widget<FilledButton>(
        find.byKey(const Key('report_review_submit_button')),
      );
      expect(lockedSubmit.onPressed, isNull);
      expect(rejectedNotifier.state, isA<Unauthenticated>());
      rejectedNotifier.dispose();

      // Parte B: fluxo completo no RewitApp aguardando refresh em voo sem pop local nem logout/navegação duplicada
      final tokenStorage = InMemoryTokenStorage(
        initialAccessToken:
            '${base64Url.encode(utf8.encode('{"alg":"HS256"}'))}.${base64Url.encode(utf8.encode('{"sub":"viewer-uuid-99"}'))}.sig',
        initialRefreshToken: 'refresh-old',
      );
      final stubAuthRepo = _StubAuthRepository();
      final refreshCompleter = Completer<String>();
      var authNotifications = 0;

      late final AuthNotifier authNotifier;
      final sessionRefresher = SessionRefresher(
        refreshAccessToken: () => refreshCompleter.future,
      );

      final mockClient = MockClient((request) async {
        if (request.url.path == '/api/v1/reports') {
          return http.Response(
            jsonEncode({
              'status': 401,
              'code': 'AUTHENTICATION_REQUIRED',
              'detail': 'Sessão expirada',
            }),
            401,
            headers: {'content-type': 'application/problem+json'},
          );
        }
        return http.Response(jsonEncode({'items': []}), 200);
      });

      final httpClient = RewitHttpClient(
        baseUrl: 'https://api.rewit.test',
        client: TokenRefreshingClient(
          inner: mockClient,
          sessionRefresher: sessionRefresher,
        ),
        tokenStorage: tokenStorage,
        onSessionExpired: () {
          unawaited(authNotifier.handleSessionExpired());
        },
      );

      authNotifier = AuthNotifier(
        authRepository: stubAuthRepo,
        sessionRefresher: sessionRefresher,
        clearLocalSession: tokenStorage.clearTokens,
      );
      await authNotifier.checkAuthStatus();
      authNotifier.addListener(() {
        authNotifications++;
      });

      final router = AppRouter(
        authNotifier: authNotifier,
        feedRepository: feedRepo,
        reportRepository: ReportRepositoryImpl(httpClient: httpClient),
      );

      await tester.pumpWidget(
        RewitApp(
          config: const AppConfig(apiBaseUrl: 'https://api.rewit.test'),
          router: router,
        ),
      );
      await tester.pumpAndSettle();

      final nav = Navigator.of(tester.element(find.byType(Scaffold).first));
      unawaited(
        nav.pushNamed(AppRouter.reviewDetail, arguments: feedRepo.review),
      );
      await tester.pumpAndSettle();
      await openReportDialogFromMenu(tester);

      await tester.tap(find.byKey(const Key('report_review_submit_button')));
      await tester.pump();
      await tester.pump();

      // Enquanto refresh está em voo, o diálogo permanece montado e bloqueado
      expect(find.byType(ReportReviewDialog), findsOneWidget);
      final submitDuringRefresh = tester.widget<FilledButton>(
        find.byKey(const Key('report_review_submit_button')),
      );
      expect(submitDuringRefresh.onPressed, isNull);

      // Quando o refresh é recusado, o RewitApp desempilha tudo globalmente (1 transição de auth)
      refreshCompleter.complete('');
      await tester.pumpAndSettle();

      expect(find.byType(ReportReviewDialog), findsNothing);
      expect(find.byType(ReviewDetailScreen), findsNothing);
      expect(find.byType(LoginScreen), findsOneWidget);
      expect(authNotifications, 1);
      expect(stubAuthRepo.logoutCalls, 0);
      expect(authNotifier.state, isA<Unauthenticated>());
      expect(
        tester.state<NavigatorState>(find.byType(Navigator).first).canPop(),
        isFalse,
      );

      authNotifier.dispose();
    });

    testWidgets('24. Refresh refreshed: preserva razão e detalhes, reabilita formulário e permite nova tentativa com sucesso', (tester) async {
      final authNotifier = AuthNotifier(
        authRepository: _StubAuthRepository(),
        sessionRefresher: SessionRefresher(refreshAccessToken: () async => 'novo-access-token'),
        clearLocalSession: () async {},
      );
      await authNotifier.checkAuthStatus();

      var attempt = 0;
      reportRepo.customHandler = ({
        required reviewId,
        required reason,
        detail,
      }) async {
        attempt++;
        if (attempt == 1) {
          // Simula 401 disparando verificação paralela via handleSessionExpired() cujo refresh tem sucesso
          unawaited(authNotifier.handleSessionExpired());
          throw const ApiException(
            ProblemDetail(
              type: 'about:blank',
              title: 'Unauthorized',
              status: 401,
              code: 'AUTHENTICATION_REQUIRED',
              detail: 'Token expirado',
            ),
          );
        }
        return ReviewReportReceipt(
          id: 'rep-after-refresh',
          reviewId: reviewId,
          reason: reason,
          status: 'PENDING',
          createdAt: DateTime.utc(2026, 11, 15, 21, 0),
          outcome: ReviewReportOutcome.created,
        );
      };

      await pumpReviewDetail(tester, authNotifier: authNotifier);
      await openReportDialogFromMenu(tester);

      await tester.tap(find.byKey(const Key('report_reason_FRAUD')));
      await tester.enterText(
        find.byKey(const Key('report_review_detail_input')),
        'Perfil falso combinado',
      );
      await tester.pump();

      await tester.tap(find.byKey(const Key('report_review_submit_button')));
      await tester.pumpAndSettle();

      // Diálogo permanece aberto com dados preservados e formulário reabilitado
      expect(find.byType(ReportReviewDialog), findsOneWidget);
      expect(
        find.text('Sua sessão foi renovada. Tente enviar a denúncia novamente.'),
        findsOneWidget,
      );
      final detailField = tester.widget<TextField>(
        find.byKey(const Key('report_review_detail_input')),
      );
      expect(detailField.controller!.text, 'Perfil falso combinado');
      final submitButton = tester.widget<FilledButton>(
        find.byKey(const Key('report_review_submit_button')),
      );
      expect(submitButton.onPressed, isNotNull);

      // Segunda tentativa conclui com os mesmos dados preservados
      await tester.tap(find.byKey(const Key('report_review_submit_button')));
      await tester.pumpAndSettle();

      expect(find.byType(ReportReviewDialog), findsNothing);
      expect(reportRepo.lastReason, ReportReason.fraud);
      expect(reportRepo.lastDetail, 'Perfil falso combinado');

      authNotifier.dispose();
    });

    testWidgets('25. Refresh unavailable: preserva dados, reabilita formulário e mantém sessão autenticada', (tester) async {
      final authNotifier = AuthNotifier(
        authRepository: _StubAuthRepository(),
        sessionRefresher: SessionRefresher(
          refreshAccessToken: () async => throw StateError('Rede indisponível'),
        ),
        clearLocalSession: () async {},
      );
      await authNotifier.checkAuthStatus();

      reportRepo.customHandler = ({
        required reviewId,
        required reason,
        detail,
      }) async {
        unawaited(authNotifier.handleSessionExpired());
        throw const ApiException(
          ProblemDetail(
            type: 'about:blank',
            title: 'Unauthorized',
            status: 401,
            code: 'AUTHENTICATION_REQUIRED',
            detail: 'Token expirado',
          ),
        );
      };

      await pumpReviewDetail(tester, authNotifier: authNotifier);
      await openReportDialogFromMenu(tester);

      await tester.tap(find.byKey(const Key('report_reason_SPAM')));
      await tester.enterText(
        find.byKey(const Key('report_review_detail_input')),
        'Propaganda repetida',
      );
      await tester.pump();

      await tester.tap(find.byKey(const Key('report_review_submit_button')));
      await tester.pumpAndSettle();

      expect(find.byType(ReportReviewDialog), findsOneWidget);
      expect(
        find.text(
          'Não foi possível verificar sua sessão no momento. Verifique sua conexão e tente novamente.',
        ),
        findsOneWidget,
      );
      final detailField = tester.widget<TextField>(
        find.byKey(const Key('report_review_detail_input')),
      );
      expect(detailField.controller!.text, 'Propaganda repetida');
      final submitButton = tester.widget<FilledButton>(
        find.byKey(const Key('report_review_submit_button')),
      );
      expect(submitButton.onPressed, isNotNull);
      expect(authNotifier.state, isA<Authenticated>());

      authNotifier.dispose();
    });

    testWidgets('26. Fluxo integrado RewitApp: 401 ACCOUNT_DISABLED aciona logout global imediato sem refresh e leva à LoginScreen sem reativação social', (tester) async {
      final tokenStorage = InMemoryTokenStorage(
        initialAccessToken:
            '${base64Url.encode(utf8.encode('{"alg":"HS256"}'))}.${base64Url.encode(utf8.encode('{"sub":"viewer-uuid-99"}'))}.sig',
        initialRefreshToken: 'refresh-active',
      );
      final stubAuthRepo = _StubAuthRepository();
      var refreshCalls = 0;
      var authNotifications = 0;

      late final AuthNotifier authNotifier;
      final sessionRefresher = SessionRefresher(
        refreshAccessToken: () async {
          refreshCalls++;
          return 'never-called';
        },
      );

      const rawServerDetail = 'Internal user status=SUSPENDED reason=policy';
      final mockClient = MockClient((request) async {
        if (request.url.path == '/api/v1/reports') {
          return http.Response(
            jsonEncode({
              'status': 401,
              'code': 'ACCOUNT_DISABLED',
              'detail': rawServerDetail,
            }),
            401,
            headers: {'content-type': 'application/problem+json'},
          );
        }
        return http.Response(jsonEncode({'items': []}), 200);
      });

      final httpClient = RewitHttpClient(
        baseUrl: 'https://api.rewit.test',
        client: TokenRefreshingClient(
          inner: mockClient,
          sessionRefresher: sessionRefresher,
        ),
        tokenStorage: tokenStorage,
        onSessionExpiredWithDetails: (details) {
          unawaited(authNotifier.handleSessionExpired(details));
        },
      );

      authNotifier = AuthNotifier(
        authRepository: stubAuthRepo,
        sessionRefresher: sessionRefresher,
        clearLocalSession: tokenStorage.clearTokens,
      );
      await authNotifier.checkAuthStatus();
      authNotifier.addListener(() => authNotifications++);

      final router = AppRouter(
        authNotifier: authNotifier,
        feedRepository: feedRepo,
        reportRepository: ReportRepositoryImpl(httpClient: httpClient),
      );

      await tester.pumpWidget(
        RewitApp(
          config: const AppConfig(apiBaseUrl: 'https://api.rewit.test'),
          router: router,
        ),
      );
      await tester.pumpAndSettle();

      final nav = Navigator.of(tester.element(find.byType(Scaffold).first));
      unawaited(
        nav.pushNamed(AppRouter.reviewDetail, arguments: feedRepo.review),
      );
      await tester.pumpAndSettle();
      await openReportDialogFromMenu(tester);

      await tester.tap(find.byKey(const Key('report_review_submit_button')));
      await tester.pumpAndSettle();

      // Rotas internas foram desempilhadas uma única vez até a LoginScreen
      expect(find.byType(ReportReviewDialog), findsNothing);
      expect(find.byType(ReviewDetailScreen), findsNothing);
      expect(find.byType(LoginScreen), findsOneWidget);
      expect(
        tester.state<NavigatorState>(find.byType(Navigator).first).canPop(),
        isFalse,
      );

      // Motivo ACCOUNT_DISABLED preservado, mensagem neutra exibida, sem SESSION_EXPIRED nem detalhe bruto
      expect(authNotifications, 1);
      expect(refreshCalls, 0);
      expect(stubAuthRepo.logoutCalls, 0);
      expect(authNotifier.state, isA<Unauthenticated>());
      final unauth = authNotifier.state as Unauthenticated;
      expect(unauth.errorCode, 'ACCOUNT_DISABLED');
      expect(unauth.socialProvider, isNull);
      expect(unauth.canOfferSocialReactivation, isFalse);

      expect(find.text(AuthNotifier.accountDisabledMessage), findsOneWidget);
      expect(find.text(rawServerDetail), findsNothing);
      expect(
        find.text('Sua sessão expirou. Faça login novamente.'),
        findsNothing,
      );
      expect(find.byKey(const Key('social_reactivate')), findsNothing);
      expect(find.text('Reativar Conta'), findsNothing);

      expect(await tokenStorage.getAccessToken(), isNull);
      expect(await tokenStorage.getRefreshToken(), isNull);

      authNotifier.dispose();
    });
  });
}
