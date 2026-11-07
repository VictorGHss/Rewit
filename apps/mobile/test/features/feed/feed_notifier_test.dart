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
  int getFeedCallCount = 0;
  int getFollowingFeedCallCount = 0;
  int toggleHelpfulCallCount = 0;
  FeedPage? page0Response;
  FeedPage? page1Response;
  FeedPage? followingPage0Response;
  FeedPage? followingPage1Response;
  bool shouldThrowOnInitial = false;
  bool shouldThrowOnLoadMore = false;
  bool shouldThrowOnFollowingInitial = false;
  bool shouldThrowOnFollowingLoadMore = false;
  bool shouldThrowOnHelpful = false;
  Completer<HelpfulResult>? toggleHelpfulCompleter;
  Completer<FeedPage>? delayedPage1Completer;
  Future<FeedPage> Function({int page, int size})? customGetFeedHandler;
  Future<FeedPage> Function({int page, int size})? customGetFollowingFeedHandler;

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
    getFeedCallCount++;
    if (customGetFeedHandler != null) {
      return customGetFeedHandler!(page: page, size: size);
    }
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
  Future<FeedPage> getFollowingFeed({int page = 0, int size = 10}) async {
    getFollowingFeedCallCount++;
    if (customGetFollowingFeedHandler != null) {
      return customGetFollowingFeedHandler!(page: page, size: size);
    }
    if (page == 0 && shouldThrowOnFollowingInitial) {
      throw const NetworkException('Sem conexão com o servidor no following');
    }
    if (page > 0 && shouldThrowOnFollowingLoadMore) {
      throw const ApiException(ProblemDetail(
        type: 'about:blank',
        title: 'Erro de Servidor',
        status: 500,
        detail: 'Falha temporária ao carregar página do following',
      ));
    }
    if (page == 0 && followingPage0Response != null) {
      return followingPage0Response!;
    }
    if (page > 0 && followingPage1Response != null) {
      return followingPage1Response!;
    }
    return getFeed(page: page, size: size);
  }

  @override
  Future<FeedReview> getReviewById(String reviewId) async {
    return _makeReview(reviewId);
  }

  @override
  Future<HelpfulResult> toggleHelpful(String reviewId, {required bool currentlyHelpful}) async {
    toggleHelpfulCallCount++;
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

    test('removeReview não exibe FeedEmpty prematuramente quando hasMore é true e recarrega itens do servidor (Item 5)', () async {
      repository.page0Response = FeedPage(
        items: [repository._makeReview('rev-page0-only')],
        page: 0,
        size: 1,
        windowSize: 2,
        totalPages: 2,
      );
      await notifier.loadInitial();
      expect(notifier.state, isA<FeedSuccess>());
      expect((notifier.state as FeedSuccess).hasMore, isTrue);

      // Prepara a resposta do recarregamento que trará os novos itens da página 0
      repository.page0Response = FeedPage(
        items: [repository._makeReview('rev-shifted-from-page1')],
        page: 0,
        size: 1,
        windowSize: 1,
        totalPages: 1,
      );

      // Remove o único item carregado da página 0
      notifier.removeReview('rev-page0-only');

      // Não deve exibir FeedEmpty prematuro; deve recarregar e transicionar para FeedSuccess com o novo item
      await pumpEventQueue();
      expect(notifier.state, isA<FeedSuccess>());
      final success = notifier.state as FeedSuccess;
      expect(success.reviews.length, 1);
      expect(success.reviews.first.id, 'rev-shifted-from-page1');
    });

    test('concorrência: removeReview durante loadMore em andamento não ressuscita a avaliação excluída (Item 4)', () async {
      await notifier.loadInitial();
      expect(notifier.state, isA<FeedSuccess>());
      expect((notifier.state as FeedSuccess).reviews.map((r) => r.id), contains('rev-1'));

      final completer = Completer<FeedPage>();
      repository.delayedPage1Completer = completer;

      // Dispara loadMore em segundo plano
      final loadMoreFuture = notifier.loadMore();

      // Durante a chamada, remove 'rev-1'
      notifier.removeReview('rev-1');
      expect((notifier.state as FeedSuccess).reviews.map((r) => r.id), isNot(contains('rev-1')));

      // Completa o loadMore da página 1 (que retorna 'rev-3' e inadvertidamente repete 'rev-1' devido a shift)
      completer.complete(FeedPage(
        items: [repository._makeReview('rev-1'), repository._makeReview('rev-3')],
        page: 1,
        size: 10,
        windowSize: 20,
        totalPages: 2,
      ));
      await loadMoreFuture;

      // 'rev-1' NÃO pode ter sido ressuscitada!
      final success = notifier.state as FeedSuccess;
      expect(success.reviews.map((r) => r.id), isNot(contains('rev-1')));
      expect(success.reviews.map((r) => r.id), contains('rev-3'));
    });

    test('loadMore com erro bloqueia novas requisições em scroll até que retry seja acionado (Item 4)', () async {
      await notifier.loadInitial();
      repository.shouldThrowOnLoadMore = true;

      // Primeiro loadMore falha
      await notifier.loadMore();
      final errorState = notifier.state as FeedSuccess;
      expect(errorState.loadMoreError, isNotNull);

      // Novo loadMore (ex: disparado por listener de scroll) NÃO deve executar chamada
      repository.shouldThrowOnLoadMore = false;
      await notifier.loadMore();
      expect((notifier.state as FeedSuccess).currentPage, 0); // Permanece na página 0

      // Somente retry() deliberado tenta novamente
      await notifier.retry();
      expect((notifier.state as FeedSuccess).currentPage, 1);
      expect((notifier.state as FeedSuccess).loadMoreError, isNull);
    });

    test('exclusão seguida de carregamento da próxima página não pula avaliação deslocada pelo offset shift', () async {
      final page0Initial = List.generate(10, (i) => repository._makeReview('rev-${i + 1}'));
      final page0AfterDelete = [
        ...page0Initial.where((r) => r.id != 'rev-3'),
        repository._makeReview('rev-11'),
      ];
      final page1AfterDelete = List.generate(4, (i) => repository._makeReview('rev-${i + 12}'));

      int callCountPage0 = 0;
      repository.customGetFeedHandler = ({page = 0, size = 10}) async {
        if (page == 0) {
          callCountPage0++;
          if (callCountPage0 == 1) {
            return FeedPage(
              items: page0Initial,
              page: 0,
              size: 10,
              windowSize: 20,
              totalPages: 2,
            );
          } else {
            return FeedPage(
              items: page0AfterDelete,
              page: 0,
              size: 10,
              windowSize: 20,
              totalPages: 2,
            );
          }
        } else {
          return FeedPage(
            items: page1AfterDelete,
            page: 1,
            size: 10,
            windowSize: 20,
            totalPages: 2,
          );
        }
      };

      // Carrega página 0 inicial
      await notifier.loadInitial();
      expect((notifier.state as FeedSuccess).reviews.length, 10);

      // Exclusão no cliente após remoção no servidor
      notifier.removeReview('rev-3');
      expect((notifier.state as FeedSuccess).reviews.length, 9);

      // Usuário solicita mais itens (loadMore)
      await notifier.loadMore();

      final successAfterMore = notifier.state as FeedSuccess;
      // rev-11 NÃO foi pulado!
      expect(successAfterMore.reviews.map((r) => r.id), contains('rev-11'));
      expect(successAfterMore.reviews.length, 10); // 9 anteriores + rev-11

      // Próxima busca avança para a página 1 normalmente
      await notifier.loadMore();
      final successFinal = notifier.state as FeedSuccess;
      expect(successFinal.reviews.length, 14);
      expect(successFinal.reviews.map((r) => r.id), containsAll(['rev-11', 'rev-12', 'rev-13', 'rev-14', 'rev-15']));
    });

    test('reset descarta o feed da sessão encerrada e ignora a página ainda em curso', () async {
      await notifier.loadInitial();
      final pendingPage = Completer<FeedPage>();
      repository.customGetFeedHandler = ({int page = 0, int size = 10}) => pendingPage.future;
      final staleLoad = notifier.refresh();

      notifier.reset();
      expect(notifier.state, isA<FeedInitial>());

      pendingPage.complete(FeedPage(
        items: [repository._makeReview('rev-outra-conta')],
        page: 0,
        size: 10,
        windowSize: 10,
        totalPages: 1,
      ));
      await staleLoad;
      expect(notifier.state, isA<FeedInitial>(), reason: 'a resposta pedida pela sessão anterior não é exibida');

      repository.customGetFeedHandler = null;
      await notifier.loadInitial();
      expect((notifier.state as FeedSuccess).reviews.map((r) => r.id).toList(), ['rev-1', 'rev-2']);
    });

    test('reset: voto útil em curso da sessão encerrada não altera nem libera o voto da sessão seguinte', () async {
      await notifier.loadInitial();
      final previousVote = Completer<HelpfulResult>();
      repository.toggleHelpfulCompleter = previousVote;
      final previousToggle = notifier.toggleHelpful('rev-1');

      notifier.reset();
      expect(notifier.isTogglingHelpful('rev-1'), isFalse);

      // Outra conta carrega o próprio feed, com a mesma avaliação, e vota nela
      await notifier.loadInitial();
      final currentVote = Completer<HelpfulResult>();
      repository.toggleHelpfulCompleter = currentVote;
      final currentToggle = notifier.toggleHelpful('rev-1');
      expect(notifier.isTogglingHelpful('rev-1'), isTrue);

      previousVote.complete(const HelpfulResult(helpful: false, helpfulCount: 42));
      await previousToggle;
      final duringCurrentVote = (notifier.state as FeedSuccess).reviews.firstWhere((r) => r.id == 'rev-1');
      expect(duringCurrentVote.isHelpfulByMe, isTrue, reason: 'otimista do voto atual, não o resultado anterior');
      expect(duringCurrentVote.helpfulCount, 1);
      expect(notifier.isTogglingHelpful('rev-1'), isTrue);

      currentVote.complete(const HelpfulResult(helpful: true, helpfulCount: 1));
      await currentToggle;
      expect(notifier.isTogglingHelpful('rev-1'), isFalse);
      expect((notifier.state as FeedSuccess).reviews.firstWhere((r) => r.id == 'rev-1').isHelpfulByMe, isTrue);
    });
  });

  group('Home V2 Dual Feeds and Mutation Synchronization', () {
    late FakeFeedRepository repository;
    late FeedNotifier notifier;

    FeedReview makeReview(String id, {bool isHelpful = false, int helpfulCount = 0, String text = 'Review text'}) {
      return FeedReview(
        id: id,
        author: const FeedAuthor(displayName: 'Test User', handle: 'tester'),
        experienceText: text,
        visibility: 'PUBLIC',
        status: 'ACTIVE',
        isHelpfulByMe: isHelpful,
        helpfulCount: helpfulCount,
        createdAt: DateTime.now(),
      );
    }

    setUp(() {
      repository = FakeFeedRepository();
      notifier = FeedNotifier(feedRepository: repository);
    });

    test('1. Carregamento inicial independente dos dois feeds (forYou e following)', () async {
      repository.page0Response = FeedPage(
        items: [makeReview('foryou-1'), makeReview('foryou-2')],
        page: 0,
        size: 10,
        windowSize: 20,
        totalPages: 2,
      );
      repository.followingPage0Response = FeedPage(
        items: [makeReview('following-1'), makeReview('following-2')],
        page: 0,
        size: 10,
        windowSize: 20,
        totalPages: 2,
      );

      expect(notifier.stateOf(FeedType.forYou), isA<FeedInitial>());
      expect(notifier.stateOf(FeedType.following), isA<FeedInitial>());

      // Carrega forYou
      await notifier.loadInitial(type: FeedType.forYou);
      expect(notifier.stateOf(FeedType.forYou), isA<FeedSuccess>());
      expect(notifier.stateOf(FeedType.following), isA<FeedInitial>());
      final forYouState = notifier.stateOf(FeedType.forYou) as FeedSuccess;
      expect(forYouState.reviews.map((r) => r.id).toList(), ['foryou-1', 'foryou-2']);

      // Carrega following
      await notifier.loadInitial(type: FeedType.following);
      expect(notifier.stateOf(FeedType.following), isA<FeedSuccess>());
      final followingState = notifier.stateOf(FeedType.following) as FeedSuccess;
      expect(followingState.reviews.map((r) => r.id).toList(), ['following-1', 'following-2']);

      // Ambos preservam seus dados independentes
      expect((notifier.stateOf(FeedType.forYou) as FeedSuccess).reviews.first.id, 'foryou-1');
      expect((notifier.stateOf(FeedType.following) as FeedSuccess).reviews.first.id, 'following-1');
    });

    test('2. Paginação independente entre forYou e following', () async {
      repository.page0Response = FeedPage(items: [makeReview('fy-1')], page: 0, size: 10, windowSize: 10, totalPages: 2);
      repository.page1Response = FeedPage(items: [makeReview('fy-2')], page: 1, size: 10, windowSize: 10, totalPages: 2);
      repository.followingPage0Response = FeedPage(items: [makeReview('foll-1')], page: 0, size: 10, windowSize: 10, totalPages: 2);
      repository.followingPage1Response = FeedPage(items: [makeReview('foll-2')], page: 1, size: 10, windowSize: 10, totalPages: 2);

      await notifier.loadInitial(type: FeedType.forYou);
      await notifier.loadInitial(type: FeedType.following);

      // Pagina apenas forYou
      await notifier.loadMore(type: FeedType.forYou);
      final forYouSuccess = notifier.stateOf(FeedType.forYou) as FeedSuccess;
      expect(forYouSuccess.currentPage, 1);
      expect(forYouSuccess.hasMore, isFalse);
      expect(forYouSuccess.reviews.map((r) => r.id).toList(), ['fy-1', 'fy-2']);

      // Following permanece na página 0 com hasMore true
      final followingInitialState = notifier.stateOf(FeedType.following) as FeedSuccess;
      expect(followingInitialState.currentPage, 0);
      expect(followingInitialState.hasMore, isTrue);
      expect(followingInitialState.reviews.map((r) => r.id).toList(), ['foll-1']);

      // Pagina following
      await notifier.loadMore(type: FeedType.following);
      final followingPaginated = notifier.stateOf(FeedType.following) as FeedSuccess;
      expect(followingPaginated.currentPage, 1);
      expect(followingPaginated.hasMore, isFalse);
      expect(followingPaginated.reviews.map((r) => r.id).toList(), ['foll-1', 'foll-2']);
    });

    test('3. Refresh independente (forYou não afeta following e vice-versa)', () async {
      repository.page0Response = FeedPage(items: [makeReview('fy-1')], page: 0, size: 10, windowSize: 10, totalPages: 2);
      repository.page1Response = FeedPage(items: [makeReview('fy-2')], page: 1, size: 10, windowSize: 10, totalPages: 2);
      repository.followingPage0Response = FeedPage(items: [makeReview('foll-1')], page: 0, size: 10, windowSize: 10, totalPages: 2);
      repository.followingPage1Response = FeedPage(items: [makeReview('foll-2')], page: 1, size: 10, windowSize: 10, totalPages: 2);

      await notifier.loadInitial(type: FeedType.forYou);
      await notifier.loadMore(type: FeedType.forYou);
      await notifier.loadInitial(type: FeedType.following);
      await notifier.loadMore(type: FeedType.following);

      expect((notifier.stateOf(FeedType.forYou) as FeedSuccess).reviews.length, 2);
      expect((notifier.stateOf(FeedType.following) as FeedSuccess).reviews.length, 2);

      // Refresh em forYou
      await notifier.refresh(type: FeedType.forYou);
      expect((notifier.stateOf(FeedType.forYou) as FeedSuccess).currentPage, 0);
      expect((notifier.stateOf(FeedType.forYou) as FeedSuccess).reviews.length, 1);

      // Following permanece intacto com 2 reviews e página 1
      final follState = notifier.stateOf(FeedType.following) as FeedSuccess;
      expect(follState.currentPage, 1);
      expect(follState.reviews.length, 2);
      expect(follState.reviews.map((r) => r.id).toList(), ['foll-1', 'foll-2']);
    });

    test('4. Reset independente e reset global', () async {
      await notifier.loadInitial(type: FeedType.forYou);
      await notifier.loadInitial(type: FeedType.following);

      expect(notifier.stateOf(FeedType.forYou), isA<FeedSuccess>());
      expect(notifier.stateOf(FeedType.following), isA<FeedSuccess>());

      // Reset exclusivo do forYou
      notifier.reset(type: FeedType.forYou);
      expect(notifier.stateOf(FeedType.forYou), isA<FeedInitial>());
      expect(notifier.stateOf(FeedType.following), isA<FeedSuccess>());

      // Reset global (logout) reseta ambos
      notifier.reset();
      expect(notifier.stateOf(FeedType.forYou), isA<FeedInitial>());
      expect(notifier.stateOf(FeedType.following), isA<FeedInitial>());
    });

    test('5. Preservação dos dados ao alternar entre feeds sem novas requisições', () async {
      repository.page0Response = FeedPage(items: [makeReview('fy-1')], page: 0, size: 10, windowSize: 10, totalPages: 1);
      repository.followingPage0Response = FeedPage(items: [makeReview('foll-1')], page: 0, size: 10, windowSize: 10, totalPages: 1);

      await notifier.loadInitial(type: FeedType.forYou);
      await notifier.loadInitial(type: FeedType.following);

      final forYouCalls = repository.getFeedCallCount;
      final followingCalls = repository.getFollowingFeedCallCount;

      // Alterna feed ativo para following
      notifier.setActiveFeedType(FeedType.following);
      expect(notifier.activeFeedType, FeedType.following);
      expect(notifier.state, isA<FeedSuccess>());
      expect((notifier.state as FeedSuccess).reviews.first.id, 'foll-1');

      // Alterna de volta para forYou
      notifier.setActiveFeedType(FeedType.forYou);
      expect(notifier.activeFeedType, FeedType.forYou);
      expect(notifier.state, isA<FeedSuccess>());
      expect((notifier.state as FeedSuccess).reviews.first.id, 'fy-1');

      // Nenhuma chamada de rede foi disparada na alternância
      expect(repository.getFeedCallCount, forYouCalls);
      expect(repository.getFollowingFeedCallCount, followingCalls);
    });

    test('6. Ausência de requisições duplicadas em carregamentos concorrentes', () async {
      final completer = Completer<FeedPage>();
      repository.customGetFeedHandler = ({int page = 0, int size = 10}) => completer.future;

      // Dispara primeira chamada (fica em FeedLoading)
      final call1 = notifier.loadInitial(type: FeedType.forYou);
      expect(notifier.stateOf(FeedType.forYou), isA<FeedLoading>());
      expect(repository.getFeedCallCount, 1);

      // Dispara segunda chamada concorrente enquanto em FeedLoading
      final call2 = notifier.loadInitial(type: FeedType.forYou);
      expect(repository.getFeedCallCount, 1, reason: 'não duplica requisição se já está em loading');

      completer.complete(FeedPage(items: [makeReview('rev-1')], page: 0, size: 10, windowSize: 10, totalPages: 1));
      await call1;
      await call2;
      expect(notifier.stateOf(FeedType.forYou), isA<FeedSuccess>());
    });

    test('7. Tratamento de erro isolado sem contaminar o outro feed', () async {
      await notifier.loadInitial(type: FeedType.forYou);
      expect(notifier.stateOf(FeedType.forYou), isA<FeedSuccess>());

      repository.shouldThrowOnFollowingInitial = true;
      await notifier.loadInitial(type: FeedType.following);

      expect(notifier.stateOf(FeedType.following), isA<FeedError>());
      // forYou continua íntegro em FeedSuccess
      expect(notifier.stateOf(FeedType.forYou), isA<FeedSuccess>());
    });

    test('8. Retry após erro recupera apenas o feed afetado', () async {
      await notifier.loadInitial(type: FeedType.forYou);
      repository.shouldThrowOnFollowingInitial = true;
      await notifier.loadInitial(type: FeedType.following);
      expect(notifier.stateOf(FeedType.following), isA<FeedError>());

      // Corrige erro e dá retry em following
      repository.shouldThrowOnFollowingInitial = false;
      await notifier.retry(type: FeedType.following);

      expect(notifier.stateOf(FeedType.following), isA<FeedSuccess>());
      expect(notifier.stateOf(FeedType.forYou), isA<FeedSuccess>());
    });

    test('9. Proteção contra respostas assíncronas obsoletas em canais independentes', () async {
      await notifier.loadInitial(type: FeedType.forYou);
      final slowPage1 = Completer<FeedPage>();
      repository.delayedPage1Completer = slowPage1;

      final loadMoreFuture = notifier.loadMore(type: FeedType.forYou);
      // Dispara refresh antes do loadMore responder
      await notifier.refresh(type: FeedType.forYou);

      // Agora responde o loadMore antigo da página 1
      slowPage1.complete(FeedPage(items: [makeReview('stale-fy-2')], page: 1, size: 10, windowSize: 10, totalPages: 2));
      await loadMoreFuture;

      final currentState = notifier.stateOf(FeedType.forYou) as FeedSuccess;
      expect(currentState.currentPage, 0, reason: 'resposta obsoleta de loadMore não sobrescreve refresh');
      expect(currentState.reviews.map((r) => r.id).toList(), ['rev-1', 'rev-2']);
    });

    test('10. Sincronização de voto útil entre feeds (otimista, reconciliação e rollback)', () async {
      final sharedReview = makeReview('rev-shared', isHelpful: false, helpfulCount: 0);
      final forYouOnly = makeReview('rev-fy-only');
      final followingOnly = makeReview('rev-foll-only');

      repository.page0Response = FeedPage(items: [sharedReview, forYouOnly], page: 0, size: 10, windowSize: 10, totalPages: 1);
      repository.followingPage0Response = FeedPage(items: [sharedReview, followingOnly], page: 0, size: 10, windowSize: 10, totalPages: 1);

      await notifier.loadInitial(type: FeedType.forYou);
      await notifier.loadInitial(type: FeedType.following);

      // Cenário A: Voto útil com sucesso sincroniza em ambos
      final voteFuture = notifier.toggleHelpful('rev-shared');

      // Otimista imediato em ambos os feeds
      final forYouOti = (notifier.stateOf(FeedType.forYou) as FeedSuccess).reviews.firstWhere((r) => r.id == 'rev-shared');
      final follOti = (notifier.stateOf(FeedType.following) as FeedSuccess).reviews.firstWhere((r) => r.id == 'rev-shared');
      expect(forYouOti.isHelpfulByMe, isTrue);
      expect(forYouOti.helpfulCount, 1);
      expect(follOti.isHelpfulByMe, isTrue);
      expect(follOti.helpfulCount, 1);
      expect(repository.toggleHelpfulCallCount, 1, reason: 'apenas 1 requisição de rede para a mesma avaliação');

      await voteFuture;

      // Reconciliado com backend
      final forYouRec = (notifier.stateOf(FeedType.forYou) as FeedSuccess).reviews.firstWhere((r) => r.id == 'rev-shared');
      final follRec = (notifier.stateOf(FeedType.following) as FeedSuccess).reviews.firstWhere((r) => r.id == 'rev-shared');
      expect(forYouRec.isHelpfulByMe, isTrue);
      expect(forYouRec.helpfulCount, 1);
      expect(follRec.isHelpfulByMe, isTrue);
      expect(follRec.helpfulCount, 1);

      // Cenário B: Rollback quando o backend falha reverte em ambos
      repository.shouldThrowOnHelpful = true;
      try {
        await notifier.toggleHelpful('rev-shared');
      } catch (_) {}

      final forYouRoll = (notifier.stateOf(FeedType.forYou) as FeedSuccess).reviews.firstWhere((r) => r.id == 'rev-shared');
      final follRoll = (notifier.stateOf(FeedType.following) as FeedSuccess).reviews.firstWhere((r) => r.id == 'rev-shared');
      expect(forYouRoll.isHelpfulByMe, isTrue, reason: 'revertido para o estado anterior');
      expect(forYouRoll.helpfulCount, 1);
      expect(follRoll.isHelpfulByMe, isTrue);
      expect(follRoll.helpfulCount, 1);
    });

    test('11. Sincronização de edição de avaliação entre feeds', () async {
      final sharedReview = makeReview('rev-shared', text: 'Texto original');
      repository.page0Response = FeedPage(items: [sharedReview], page: 0, size: 10, windowSize: 10, totalPages: 1);
      repository.followingPage0Response = FeedPage(items: [sharedReview], page: 0, size: 10, windowSize: 10, totalPages: 1);

      await notifier.loadInitial(type: FeedType.forYou);
      await notifier.loadInitial(type: FeedType.following);

      final updated = sharedReview.copyWith(experienceText: 'Texto editado e sincronizado!');
      notifier.updateReview(updated);

      final forYouReview = (notifier.stateOf(FeedType.forYou) as FeedSuccess).reviews.first;
      final followingReview = (notifier.stateOf(FeedType.following) as FeedSuccess).reviews.first;

      expect(forYouReview.experienceText, 'Texto editado e sincronizado!');
      expect(followingReview.experienceText, 'Texto editado e sincronizado!');
    });

    test('12. Sincronização de remoção de avaliação entre feeds', () async {
      final shared = makeReview('rev-shared');
      final fyKeep = makeReview('fy-keep');
      final follKeep = makeReview('foll-keep');

      repository.page0Response = FeedPage(items: [shared, fyKeep], page: 0, size: 10, windowSize: 10, totalPages: 1);
      repository.followingPage0Response = FeedPage(items: [shared, follKeep], page: 0, size: 10, windowSize: 10, totalPages: 1);

      await notifier.loadInitial(type: FeedType.forYou);
      await notifier.loadInitial(type: FeedType.following);

      notifier.removeReview('rev-shared');

      final forYouList = (notifier.stateOf(FeedType.forYou) as FeedSuccess).reviews.map((r) => r.id).toList();
      final followingList = (notifier.stateOf(FeedType.following) as FeedSuccess).reviews.map((r) => r.id).toList();

      expect(forYouList, ['fy-keep']);
      expect(followingList, ['foll-keep']);
      expect(forYouList.contains('rev-shared'), isFalse);
      expect(followingList.contains('rev-shared'), isFalse);
    });

    test('13. Avaliações presentes em apenas um feed não alteram o outro', () async {
      final forYouOnly = makeReview('fy-only', text: 'Texto ForYou');
      final followingOnly = makeReview('foll-only', text: 'Texto Following');

      repository.page0Response = FeedPage(items: [forYouOnly], page: 0, size: 10, windowSize: 10, totalPages: 1);
      repository.followingPage0Response = FeedPage(items: [followingOnly], page: 0, size: 10, windowSize: 10, totalPages: 1);

      await notifier.loadInitial(type: FeedType.forYou);
      await notifier.loadInitial(type: FeedType.following);

      // Edita avaliação exclusiva do forYou
      notifier.updateReview(forYouOnly.copyWith(experienceText: 'ForYou modificado'));
      expect((notifier.stateOf(FeedType.forYou) as FeedSuccess).reviews.first.experienceText, 'ForYou modificado');
      expect((notifier.stateOf(FeedType.following) as FeedSuccess).reviews.first.experienceText, 'Texto Following');

      // Vota útil na avaliação exclusiva do following
      await notifier.toggleHelpful('foll-only');
      expect((notifier.stateOf(FeedType.following) as FeedSuccess).reviews.first.isHelpfulByMe, isTrue);
      expect((notifier.stateOf(FeedType.forYou) as FeedSuccess).reviews.first.isHelpfulByMe, isFalse);

      // Remove avaliação exclusiva do forYou
      notifier.removeReview('fy-only');
      expect(notifier.stateOf(FeedType.forYou), isA<FeedEmpty>());
      expect(notifier.stateOf(FeedType.following), isA<FeedSuccess>());
      expect((notifier.stateOf(FeedType.following) as FeedSuccess).reviews.map((r) => r.id).toList(), ['foll-only']);
    });

    test('14. Avaliações repetidas em diferentes páginas com deduplicação', () async {
      // Página 1 retorna item repetido já presente na página 0
      repository.page0Response = FeedPage(items: [makeReview('rev-dup')], page: 0, size: 10, windowSize: 10, totalPages: 2);
      repository.page1Response = FeedPage(items: [makeReview('rev-dup'), makeReview('rev-unique')], page: 1, size: 10, windowSize: 10, totalPages: 2);

      await notifier.loadInitial(type: FeedType.forYou);
      await notifier.loadMore(type: FeedType.forYou);

      final reviews = (notifier.stateOf(FeedType.forYou) as FeedSuccess).reviews;
      expect(reviews.length, 2);
      expect(reviews.map((r) => r.id).toList(), ['rev-dup', 'rev-unique']);
    });

    test('15. Ausência de efeitos colaterais em avaliações não relacionadas', () async {
      final r1 = makeReview('r1', isHelpful: false, helpfulCount: 5, text: 'R1');
      final r2 = makeReview('r2', isHelpful: true, helpfulCount: 10, text: 'R2');
      final r3 = makeReview('r3', isHelpful: false, helpfulCount: 2, text: 'R3');

      repository.page0Response = FeedPage(items: [r1, r2, r3], page: 0, size: 10, windowSize: 10, totalPages: 1);
      await notifier.loadInitial(type: FeedType.forYou);

      // Vota em r1
      await notifier.toggleHelpful('r1');
      var list = (notifier.stateOf(FeedType.forYou) as FeedSuccess).reviews;
      expect(list[1].id, 'r2');
      expect(list[1].isHelpfulByMe, isTrue);
      expect(list[1].helpfulCount, 10);
      expect(list[2].id, 'r3');
      expect(list[2].isHelpfulByMe, isFalse);
      expect(list[2].helpfulCount, 2);

      // Edita r1
      notifier.updateReview(r1.copyWith(experienceText: 'R1 Editado'));
      list = (notifier.stateOf(FeedType.forYou) as FeedSuccess).reviews;
      expect(list[1].experienceText, 'R2');
      expect(list[2].experienceText, 'R3');

      // Remove r1
      notifier.removeReview('r1');
      list = (notifier.stateOf(FeedType.forYou) as FeedSuccess).reviews;
      expect(list.length, 2);
      expect(list[0].id, 'r2');
      expect(list[0].experienceText, 'R2');
      expect(list[0].helpfulCount, 10);
      expect(list[1].id, 'r3');
      expect(list[1].experienceText, 'R3');
      expect(list[1].helpfulCount, 2);
    });
  });
}
