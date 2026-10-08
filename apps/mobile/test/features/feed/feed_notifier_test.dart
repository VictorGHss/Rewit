import 'dart:async';
import 'package:flutter_test/flutter_test.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/features/feed/domain/entities/feed_entities.dart';
import 'package:rewit_mobile/features/feed/domain/repositories/feed_repository.dart';
import 'package:rewit_mobile/features/feed/presentation/state/feed_notifier.dart';
import 'package:rewit_mobile/features/feed/presentation/state/feed_state.dart';
import 'package:rewit_mobile/features/review_detail/domain/entities/helpful_result.dart';
import 'package:rewit_mobile/features/review_detail/domain/entities/review_media.dart';
import 'package:rewit_mobile/features/review_detail/domain/entities/update_review_input.dart';


class FakeFeedRepository implements FeedRepository {
  FeedPage? page0Response;
  FeedPage? page1Response;
  bool shouldThrowOnInitial = false;
  bool shouldThrowOnLoadMore = false;
  bool shouldThrowOnHelpful = false;
  Completer<HelpfulResult>? toggleHelpfulCompleter;
  Completer<FeedPage>? delayedPage1Completer;

  FeedReview _makeReview(String id, {bool isHelpful = false, int helpfulCount = 0}) {
    return FeedReview(
      id: id,
      author: const FeedAuthor(displayName: 'Test User', handle: 'tester'),
      visibility: 'PUBLIC',
      status: 'ACTIVE',
      isHelpfulByMe: isHelpful,
      helpfulCount: helpfulCount,
      createdAt: DateTime.now(),
    );
  }

  @override
  Future<FeedPage> getFeed({int page = 0, int size = 10}) async {
    if (page == 0 && shouldThrowOnInitial) {
      throw const NetworkException('Sem conexão com o servidor');
    }
    if (page > 0 && shouldThrowOnLoadMore) {
      throw const ApiException(ProblemDetail(
        type: 'about:blank',
        title: 'Erro de Servidor',
        status: 500,
        detail: 'Falha temporária ao carregar página',
      ));
    }
    if (page > 0 && delayedPage1Completer != null) {
      return delayedPage1Completer!.future;
    }

    if (page == 0) {
      return page0Response ??
          FeedPage(
            items: [_makeReview('rev-1'), _makeReview('rev-2')],
            page: 0,
            size: 10,
            windowSize: 20,
            totalPages: 2,
          );
    } else {
      return page1Response ??
          FeedPage(
            items: [_makeReview('rev-3'), _makeReview('rev-4')],
            page: 1,
            size: 10,
            windowSize: 20,
            totalPages: 2,
          );
    }
  }

  @override
  Future<FeedReview> getReviewById(String reviewId) async {
    return _makeReview(reviewId);
  }

  @override
  Future<HelpfulResult> toggleHelpful(String reviewId, {required bool currentlyHelpful}) async {
    if (shouldThrowOnHelpful) {
      throw const NetworkException('Falha de rede ao votar útil');
    }
    if (toggleHelpfulCompleter != null) {
      return toggleHelpfulCompleter!.future;
    }
    return HelpfulResult(helpful: !currentlyHelpful, helpfulCount: currentlyHelpful ? 0 : 1);
  }

  @override
  Future<List<ReviewMediaItem>> getReviewMedia(String reviewId) async {
    return [];
  }

  @override
  Future<FeedReview> updateReview(String reviewId, UpdateReviewInput input) async {
    return _makeReview(reviewId);
  }

  @override
  Future<void> deleteReview(String reviewId) async {}
}



void main() {
  group('FeedNotifier', () {
    late FakeFeedRepository repository;
    late FeedNotifier notifier;

    setUp(() {
      repository = FakeFeedRepository();
      notifier = FeedNotifier(feedRepository: repository);
    });

    test('estado inicial é FeedInitial', () {
      expect(notifier.state, isA<FeedInitial>());
    });

    test('loadInitial com sucesso transiciona para FeedSuccess', () async {
      await notifier.loadInitial();

      expect(notifier.state, isA<FeedSuccess>());
      final success = notifier.state as FeedSuccess;
      expect(success.reviews.length, 2);
      expect(success.currentPage, 0);
      expect(success.hasMore, isTrue);
    });

    test('loadInitial vazio transiciona para FeedEmpty', () async {
      repository.page0Response = const FeedPage(
        items: [],
        page: 0,
        size: 10,
        windowSize: 0,
        totalPages: 0,
      );

      await notifier.loadInitial();

      expect(notifier.state, isA<FeedEmpty>());
    });

    test('loadInitial com erro transiciona para FeedError', () async {
      repository.shouldThrowOnInitial = true;

      await notifier.loadInitial();

      expect(notifier.state, isA<FeedError>());
      final error = notifier.state as FeedError;
      expect(error.message, contains('Sem conexão'));
    });

    test('loadMore anexa avaliações da página seguinte e atualiza hasMore', () async {
      await notifier.loadInitial();
      expect((notifier.state as FeedSuccess).reviews.length, 2);

      await notifier.loadMore();

      expect(notifier.state, isA<FeedSuccess>());
      final success = notifier.state as FeedSuccess;
      expect(success.reviews.length, 4);
      expect(success.currentPage, 1);
      expect(success.hasMore, isFalse); // página 1 de 2
    });

    test('loadMore não executa se hasMore for falso', () async {
      repository.page0Response = FeedPage(
        items: [repository._makeReview('single')],
        page: 0,
        size: 10,
        windowSize: 1,
        totalPages: 1, // apenas 1 página
      );

      await notifier.loadInitial();
      expect((notifier.state as FeedSuccess).hasMore, isFalse);

      await notifier.loadMore();
      expect((notifier.state as FeedSuccess).reviews.length, 1);
    });

    test('loadMore com erro preserva reviews existentes e define loadMoreError', () async {
      await notifier.loadInitial();
      repository.shouldThrowOnLoadMore = true;

      await notifier.loadMore();

      expect(notifier.state, isA<FeedSuccess>());
      final success = notifier.state as FeedSuccess;
      expect(success.reviews.length, 2); // manteve as anteriores
      expect(success.loadMoreError, 'Falha temporária ao carregar página');
      expect(success.isLoadingMore, isFalse);
    });

    test('refresh recarrega da página 0', () async {
      await notifier.loadInitial();
      await notifier.loadMore();
      expect((notifier.state as FeedSuccess).reviews.length, 4);

      await notifier.refresh();
      expect((notifier.state as FeedSuccess).reviews.length, 2);
      expect((notifier.state as FeedSuccess).currentPage, 0);
    });

    test('loadMore deduplica avaliações pelo id caso a página seguinte contenha itens repetidos', () async {
      await notifier.loadInitial();
      // Página 1 contém 'rev-2' (já presente na p0) e 'rev-3' (novo)
      repository.page1Response = FeedPage(
        items: [repository._makeReview('rev-2'), repository._makeReview('rev-3')],
        page: 1,
        size: 10,
        windowSize: 20,
        totalPages: 2,
      );

      await notifier.loadMore();

      final success = notifier.state as FeedSuccess;
      expect(success.reviews.length, 3); // rev-1, rev-2, rev-3 (sem duplicata)
      expect(success.reviews.map((r) => r.id).toList(), ['rev-1', 'rev-2', 'rev-3']);
    });

    test('sequence guard: refresh durante loadMore descarta resposta lenta da paginação', () async {
      await notifier.loadInitial();
      repository.delayedPage1Completer = Completer<FeedPage>();

      // Inicia loadMore assíncrono
      final loadMoreFuture = notifier.loadMore();
      expect((notifier.state as FeedSuccess).isLoadingMore, isTrue);

      // Usuário dispara refresh antes de loadMore completar
      repository.page0Response = FeedPage(
        items: [repository._makeReview('rev-fresh-1')],
        page: 0,
        size: 10,
        windowSize: 10,
        totalPages: 1,
      );
      await notifier.refresh();
      expect((notifier.state as FeedSuccess).reviews.map((r) => r.id).toList(), ['rev-fresh-1']);

      // Agora a resposta lenta do loadMore é resolvida
      repository.delayedPage1Completer!.complete(FeedPage(
        items: [repository._makeReview('rev-obsolete')],
        page: 1,
        size: 10,
        windowSize: 20,
        totalPages: 2,
      ));
      await loadMoreFuture;

      // Estado deve permanecer apenas com o resultado do refresh
      final finalSuccess = notifier.state as FeedSuccess;
      expect(finalSuccess.reviews.map((r) => r.id).toList(), ['rev-fresh-1']);
    });

    test('toggleHelpful atualiza otimisticamente e reconcilia com resposta do repositório', () async {
      await notifier.loadInitial();
      final initialReview = (notifier.state as FeedSuccess).reviews.first;
      expect(initialReview.id, 'rev-1');
      expect(initialReview.isHelpfulByMe, isFalse);
      expect(initialReview.helpfulCount, 0);

      await notifier.toggleHelpful('rev-1');

      final success = notifier.state as FeedSuccess;
      final updated = success.reviews.firstWhere((r) => r.id == 'rev-1');
      expect(updated.isHelpfulByMe, isTrue);
      expect(updated.helpfulCount, 1);
      expect(notifier.isTogglingHelpful('rev-1'), isFalse);
    });

    test('toggleHelpful reverte atualização otimista e lança erro quando repositório falha', () async {
      await notifier.loadInitial();
      repository.shouldThrowOnHelpful = true;

      await expectLater(
        notifier.toggleHelpful('rev-1'),
        throwsA(isA<NetworkException>()),
      );

      final success = notifier.state as FeedSuccess;
      final review = success.reviews.firstWhere((r) => r.id == 'rev-1');
      expect(review.isHelpfulByMe, isFalse);
      expect(review.helpfulCount, 0);
      expect(notifier.isTogglingHelpful('rev-1'), isFalse);
    });

    test('toggleHelpful impede chamadas concorrentes para a mesma avaliação enquanto pendente', () async {
      await notifier.loadInitial();
      repository.toggleHelpfulCompleter = Completer<HelpfulResult>();

      // Dispara primeira chamada que fica pendente
      final future1 = notifier.toggleHelpful('rev-1');
      expect(notifier.isTogglingHelpful('rev-1'), isTrue);

      // Segunda chamada simultânea para o mesmo ID deve ser ignorada
      final future2 = notifier.toggleHelpful('rev-1');

      // Completa requisição
      repository.toggleHelpfulCompleter!.complete(
        const HelpfulResult(helpful: true, helpfulCount: 1),
      );
      await future1;
      await future2;

      expect(notifier.isTogglingHelpful('rev-1'), isFalse);
      final success = notifier.state as FeedSuccess;
      expect(success.reviews.first.isHelpfulByMe, isTrue);
    });

    test('toggleHelpful garante que contador não fique negativo (clamp >= 0)', () async {
      repository.page0Response = FeedPage(
        items: [repository._makeReview('rev-zero', isHelpful: true, helpfulCount: 0)],
        page: 0,
        size: 10,
        windowSize: 1,
        totalPages: 1,
      );
      await notifier.loadInitial();

      await notifier.toggleHelpful('rev-zero');

      final success = notifier.state as FeedSuccess;
      final review = success.reviews.first;
      expect(review.isHelpfulByMe, isFalse);
      expect(review.helpfulCount, 0); // clamped to 0, not -1
    });

    test('updateReview atualiza dados da avaliação in-place sem recarregar o feed', () async {
      await notifier.loadInitial();
      final initialReview = (notifier.state as FeedSuccess).reviews.first;

      final updatedReview = initialReview.copyWith(
        experienceText: 'Texto atualizado na tela de detalhe',
        helpfulCount: 42,
      );

      notifier.updateReview(updatedReview);

      final success = notifier.state as FeedSuccess;
      final review = success.reviews.firstWhere((r) => r.id == 'rev-1');
      expect(review.experienceText, 'Texto atualizado na tela de detalhe');
      expect(review.helpfulCount, 42);
    });

    test('removeReview remove avaliação da lista in-place preservando as demais', () async {
      await notifier.loadInitial();
      expect((notifier.state as FeedSuccess).reviews.length, 2);

      notifier.removeReview('rev-1');

      final success = notifier.state as FeedSuccess;
      expect(success.reviews.length, 1);
      expect(success.reviews.first.id, 'rev-2');
    });

    test('removeReview transiciona para FeedEmpty quando o feed continha apenas o item excluído', () async {
      repository.page0Response = FeedPage(
        items: [repository._makeReview('rev-sole')],
        page: 0,
        size: 10,
        windowSize: 1,
        totalPages: 1,
      );
      await notifier.loadInitial();
      expect(notifier.state, isA<FeedSuccess>());

      notifier.removeReview('rev-sole');

      expect(notifier.state, isA<FeedEmpty>());
    });

    test('removeReview não altera estado quando o identificador não existe na lista', () async {
      await notifier.loadInitial();
      final beforeReviews = (notifier.state as FeedSuccess).reviews;

      notifier.removeReview('rev-inexistente');

      final afterReviews = (notifier.state as FeedSuccess).reviews;
      expect(afterReviews.length, beforeReviews.length);
    });
  });
}
