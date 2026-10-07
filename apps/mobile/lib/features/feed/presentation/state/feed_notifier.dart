import 'package:flutter/foundation.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import '../../domain/repositories/feed_repository.dart';
import 'feed_state.dart';

/// Gerenciador reativo de estado do Feed V2 (ChangeNotifier).
class FeedNotifier extends ChangeNotifier {
  final FeedRepository _feedRepository;
  FeedState _state = const FeedInitial();

  FeedNotifier({required FeedRepository feedRepository})
      : _feedRepository = feedRepository;

  FeedState get state => _state;

  /// Carrega a primeira página do feed (página 0).
  Future<void> loadInitial({int size = 10}) async {
    _state = const FeedLoading();
    notifyListeners();

    try {
      final feedPage = await _feedRepository.getFeed(page: 0, size: size);
      if (feedPage.isEmpty) {
        _state = const FeedEmpty();
      } else {
        _state = FeedSuccess(
          reviews: feedPage.items,
          currentPage: feedPage.page,
          totalPages: feedPage.totalPages,
          hasMore: feedPage.hasMore,
        );
      }
      notifyListeners();
    } on ApiException catch (e) {
      _state = FeedError(message: e.detail, code: e.errorCode);
      notifyListeners();
    } on NetworkException catch (e) {
      _state = FeedError(message: e.message);
      notifyListeners();
    } catch (_) {
      _state = const FeedError(message: 'Não foi possível carregar o feed.');
      notifyListeners();
    }
  }

  /// Recarrega o feed desde o início (Pull-to-refresh).
  Future<void> refresh({int size = 10}) async {
    await loadInitial(size: size);
  }

  /// Carrega a próxima página (infinite scroll / paginação).
  Future<void> loadMore({int size = 10}) async {
    final currentState = _state;
    if (currentState is! FeedSuccess) return;
    if (!currentState.hasMore || currentState.isLoadingMore) return;

    _state = currentState.copyWith(isLoadingMore: true, clearLoadMoreError: true);
    notifyListeners();

    try {
      final nextPage = currentState.currentPage + 1;
      final feedPage = await _feedRepository.getFeed(page: nextPage, size: size);

      _state = currentState.copyWith(
        reviews: [...currentState.reviews, ...feedPage.items],
        currentPage: feedPage.page,
        totalPages: feedPage.totalPages,
        hasMore: feedPage.hasMore,
        isLoadingMore: false,
      );
      notifyListeners();
    } on ApiException catch (e) {
      _state = currentState.copyWith(
        isLoadingMore: false,
        loadMoreError: e.detail,
      );
      notifyListeners();
    } on NetworkException catch (e) {
      _state = currentState.copyWith(
        isLoadingMore: false,
        loadMoreError: e.message,
      );
      notifyListeners();
    } catch (_) {
      _state = currentState.copyWith(
        isLoadingMore: false,
        loadMoreError: 'Erro ao carregar mais avaliações.',
      );
      notifyListeners();
    }
  }

  /// Repete o carregamento que falhou.
  Future<void> retry() async {
    final currentState = _state;
    if (currentState is FeedSuccess && currentState.loadMoreError != null) {
      await loadMore();
    } else {
      await loadInitial();
    }
  }
}
