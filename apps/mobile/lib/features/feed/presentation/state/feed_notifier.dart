import 'dart:math' as math;
import 'package:flutter/foundation.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import '../../domain/entities/feed_entities.dart';
import '../../domain/repositories/feed_repository.dart';
import 'feed_state.dart';

/// Gerenciador reativo de estado do Feed V2 (ChangeNotifier).
class FeedNotifier extends ChangeNotifier {
  final FeedRepository _feedRepository;
  FeedState _state = const FeedInitial();

  int _currentRequestId = 0;
  final Set<String> _pendingHelpfulReviewIds = {};

  FeedNotifier({required FeedRepository feedRepository})
      : _feedRepository = feedRepository;

  FeedState get state => _state;

  /// Verifica se há uma alternância de voto útil em andamento para a avaliação.
  bool isTogglingHelpful(String reviewId) => _pendingHelpfulReviewIds.contains(reviewId);

  /// Carrega a primeira página do feed (página 0).
  Future<void> loadInitial({int size = 10}) async {
    final requestId = ++_currentRequestId;
    _state = const FeedLoading();
    notifyListeners();

    try {
      final feedPage = await _feedRepository.getFeed(page: 0, size: size);

      if (requestId != _currentRequestId) {
        return; // Resposta obsoleta descartada
      }

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
      if (requestId == _currentRequestId) {
        _state = FeedError(message: e.detail, code: e.errorCode);
        notifyListeners();
      }
    } on NetworkException catch (e) {
      if (requestId == _currentRequestId) {
        _state = FeedError(message: e.message);
        notifyListeners();
      }
    } catch (_) {
      if (requestId == _currentRequestId) {
        _state = const FeedError(message: 'Não foi possível carregar o feed.');
        notifyListeners();
      }
    }
  }

  /// Recarrega o feed desde o início (Pull-to-refresh).
  Future<void> refresh({int size = 10}) async {
    await loadInitial(size: size);
  }

  /// Carrega a próxima página (infinite scroll / paginação) com deduplicação por ID.
  Future<void> loadMore({int size = 10}) async {
    final currentState = _state;
    if (currentState is! FeedSuccess) return;
    if (!currentState.hasMore || currentState.isLoadingMore) return;

    final requestId = _currentRequestId;
    _state = currentState.copyWith(isLoadingMore: true, clearLoadMoreError: true);
    notifyListeners();

    try {
      final nextPage = currentState.currentPage + 1;
      final feedPage = await _feedRepository.getFeed(page: nextPage, size: size);

      if (requestId != _currentRequestId) {
        return; // Resposta obsoleta descartada se refresh foi acionado
      }

      // Deduplicação estrita de itens pelo identificador da review
      final existingIds = currentState.reviews.map((r) => r.id).toSet();
      final newItems = feedPage.items.where((r) => !existingIds.contains(r.id)).toList();
      final combined = [...currentState.reviews, ...newItems];

      _state = currentState.copyWith(
        reviews: combined,
        currentPage: feedPage.page,
        totalPages: feedPage.totalPages,
        hasMore: feedPage.hasMore,
        isLoadingMore: false,
      );
      notifyListeners();
    } on ApiException catch (e) {
      if (requestId == _currentRequestId) {
        _state = currentState.copyWith(
          isLoadingMore: false,
          loadMoreError: e.detail,
        );
        notifyListeners();
      }
    } on NetworkException catch (e) {
      if (requestId == _currentRequestId) {
        _state = currentState.copyWith(
          isLoadingMore: false,
          loadMoreError: e.message,
        );
        notifyListeners();
      }
    } catch (_) {
      if (requestId == _currentRequestId) {
        _state = currentState.copyWith(
          isLoadingMore: false,
          loadMoreError: 'Erro ao carregar mais avaliações.',
        );
        notifyListeners();
      }
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

  /// Alterna o voto útil (helpful) de uma avaliação de forma otimista com reversão em caso de erro.
  Future<void> toggleHelpful(String reviewId) async {
    final currentState = _state;
    if (currentState is! FeedSuccess) return;
    if (_pendingHelpfulReviewIds.contains(reviewId)) return;

    final index = currentState.reviews.indexWhere((r) => r.id == reviewId);
    if (index == -1) return;

    final currentReview = currentState.reviews[index];
    final wasHelpful = currentReview.isHelpfulByMe;
    final nextHelpful = !wasHelpful;
    final nextCount = math.max(0, currentReview.helpfulCount + (nextHelpful ? 1 : -1));

    // Atualização otimista imediata
    final optimisticReview = currentReview.copyWith(
      isHelpfulByMe: nextHelpful,
      helpfulCount: nextCount,
    );
    final updatedList = List<FeedReview>.from(currentState.reviews);
    updatedList[index] = optimisticReview;

    _pendingHelpfulReviewIds.add(reviewId);
    _state = currentState.copyWith(reviews: updatedList);
    notifyListeners();

    try {
      final result = await _feedRepository.toggleHelpful(
        reviewId,
        currentlyHelpful: wasHelpful,
      );

      // Reconcilia com a resposta canônica do backend
      final latestState = _state;
      if (latestState is FeedSuccess) {
        final latestIndex = latestState.reviews.indexWhere((r) => r.id == reviewId);
        if (latestIndex != -1) {
          final reconciled = latestState.reviews[latestIndex].copyWith(
            isHelpfulByMe: result.helpful,
            helpfulCount: math.max(0, result.helpfulCount),
          );
          final reconciledList = List<FeedReview>.from(latestState.reviews);
          reconciledList[latestIndex] = reconciled;
          _state = latestState.copyWith(reviews: reconciledList);
          notifyListeners();
        }
      }
    } catch (e) {
      // Reversão em caso de falha de rede/API
      final latestState = _state;
      if (latestState is FeedSuccess) {
        final rollbackIndex = latestState.reviews.indexWhere((r) => r.id == reviewId);
        if (rollbackIndex != -1) {
          final rollbackList = List<FeedReview>.from(latestState.reviews);
          rollbackList[rollbackIndex] = currentReview;
          _state = latestState.copyWith(reviews: rollbackList);
          notifyListeners();
        }
      }
      rethrow;
    } finally {
      _pendingHelpfulReviewIds.remove(reviewId);
      notifyListeners();
    }
  }

  /// Atualiza os dados de uma avaliação localmente sem recarregar o feed.
  void updateReview(FeedReview updatedReview) {
    final currentState = _state;
    if (currentState is! FeedSuccess) return;

    final index = currentState.reviews.indexWhere((r) => r.id == updatedReview.id);
    if (index == -1) return;

    final updatedList = List<FeedReview>.from(currentState.reviews);
    updatedList[index] = updatedReview;
    _state = currentState.copyWith(reviews: updatedList);
    notifyListeners();
  }

  /// Remove uma avaliação localmente sem recarregar o feed inteiro.
  void removeReview(String reviewId) {
    final currentState = _state;
    if (currentState is! FeedSuccess) return;

    final updatedList = currentState.reviews.where((r) => r.id != reviewId).toList();
    if (updatedList.length == currentState.reviews.length) return;

    if (updatedList.isEmpty) {
      _state = const FeedEmpty();
    } else {
      _state = currentState.copyWith(reviews: updatedList);
    }
    notifyListeners();
  }
}
