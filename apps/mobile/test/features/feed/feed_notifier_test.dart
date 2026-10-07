import 'package:flutter_test/flutter_test.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/features/feed/domain/entities/feed_entities.dart';
import 'package:rewit_mobile/features/feed/domain/repositories/feed_repository.dart';
import 'package:rewit_mobile/features/feed/presentation/state/feed_notifier.dart';
import 'package:rewit_mobile/features/feed/presentation/state/feed_state.dart';

class FakeFeedRepository implements FeedRepository {
  FeedPage? page0Response;
  FeedPage? page1Response;
  bool shouldThrowOnInitial = false;
  bool shouldThrowOnLoadMore = false;

  FeedReview _makeReview(String id) {
    return FeedReview(
      id: id,
      author: const FeedAuthor(displayName: 'Test User', handle: 'tester'),
      visibility: 'PUBLIC',
      status: 'ACTIVE',
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
  });
}
