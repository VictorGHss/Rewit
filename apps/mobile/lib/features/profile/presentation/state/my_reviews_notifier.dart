import 'dart:math' as math;
import 'package:flutter/foundation.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/features/feed/domain/entities/feed_entities.dart';
import 'package:rewit_mobile/features/profile/domain/repositories/user_profile_repository.dart';
import 'my_reviews_state.dart';

/// Gerenciador reativo de estado para Minhas Avaliações (ChangeNotifier).
/// Controla ciclo de vida, paginação com deduplicação, pull-to-refresh e sincronização local.
class MyReviewsNotifier extends ChangeNotifier {
  final UserProfileRepository repository;

  MyReviewsState _state = const MyReviewsInitial();
  MyReviewsState get state => _state;

  int _currentRequestId = 0;
  bool _isLoadingInitial = false;
  final Set<String> _removedReviewIds = {};

  MyReviewsNotifier({required this.repository});

  /// Carrega a primeira página de avaliações do usuário autenticado.
  Future<void> loadInitial({int size = 10}) async {
    if (_isLoadingInitial) return;
    _isLoadingInitial = true;

    _removedReviewIds.clear();
    final requestId = ++_currentRequestId;
    _state = const MyReviewsLoading();
    notifyListeners();

    try {
      final page = await repository.getMyReviews(page: 0, size: size);

      if (requestId != _currentRequestId) {
        return; // Resposta obsoleta descartada
      }

      final activeReviews = page.reviews.where((r) => !_removedReviewIds.contains(r.id)).toList();

      if (activeReviews.isEmpty && page.isLast) {
        _state = const MyReviewsEmpty();
      } else {
        _state = MyReviewsLoaded(
          reviews: activeReviews,
          currentPage: page.pageNumber,
          totalPages: page.totalPages,
          totalElements: math.max(0, page.totalElements - (page.reviews.length - activeReviews.length)),
          isLastPage: page.isLast,
        );
      }
    } on ApiException catch (e) {
      if (requestId == _currentRequestId) {
        _state = MyReviewsError(message: e.detail, code: e.errorCode);
      }
    } on NetworkException catch (e) {
      if (requestId == _currentRequestId) {
        _state = MyReviewsError(message: e.message);
      }
    } catch (_) {
      if (requestId == _currentRequestId) {
        _state = const MyReviewsError(
          message: 'Não foi possível carregar suas avaliações.',
        );
      }
    } finally {
      _isLoadingInitial = false;
      notifyListeners();
    }
  }

  /// Recarrega as avaliações desde a primeira página (Pull-to-refresh).
  Future<void> refresh({int size = 10}) async {
    await loadInitial(size: size);
  }

  /// Carrega a próxima página incremental com proteção contra concorrência e deduplicação estrita.
  Future<void> loadMore({int size = 10}) async {
    if (_isLoadingInitial) return;

    final currentState = _state;
    if (currentState is! MyReviewsLoaded) return;
    if (!currentState.hasMore || currentState.isLoadingMore) return;
    if (currentState.loadMoreError != null) return; // Não dispara requisições repetidas se houver erro não tratado

    final requestId = _currentRequestId;
    _state = currentState.copyWith(isLoadingMore: true, clearLoadMoreError: true);
    notifyListeners();

    try {
      final nextPage = currentState.currentPage + 1;
      final page = await repository.getMyReviews(page: nextPage, size: size);

      if (requestId != _currentRequestId) {
        return; // Descarte de resposta caso um refresh tenha sido disparado
      }

      final liveState = _state;
      if (liveState is! MyReviewsLoaded) {
        return; // Estado mudou (ex: refresh ou logout) durante a requisição
      }

      // Deduplicação estrita de itens pelo identificador único e filtro de avaliações excluídas
      final existingIds = liveState.reviews.map((r) => r.id).toSet();
      final newItems = page.reviews
          .where((r) => !existingIds.contains(r.id) && !_removedReviewIds.contains(r.id))
          .toList();
      final combined = [...liveState.reviews, ...newItems];

      _state = liveState.copyWith(
        reviews: combined,
        currentPage: page.pageNumber,
        totalPages: page.totalPages,
        totalElements: page.totalElements,
        isLastPage: page.isLast,
        isLoadingMore: false,
        clearLoadMoreError: true,
      );
    } on ApiException catch (e) {
      if (requestId == _currentRequestId && _state is MyReviewsLoaded) {
        _state = (_state as MyReviewsLoaded).copyWith(
          isLoadingMore: false,
          loadMoreError: e.detail,
        );
      }
    } on NetworkException catch (e) {
      if (requestId == _currentRequestId && _state is MyReviewsLoaded) {
        _state = (_state as MyReviewsLoaded).copyWith(
          isLoadingMore: false,
          loadMoreError: e.message,
        );
      }
    } catch (_) {
      if (requestId == _currentRequestId && _state is MyReviewsLoaded) {
        _state = (_state as MyReviewsLoaded).copyWith(
          isLoadingMore: false,
          loadMoreError: 'Erro ao carregar mais avaliações.',
        );
      }
    } finally {
      notifyListeners();
    }
  }

  /// Repete o carregamento que falhou (seja carga inicial ou paginação incremental).
  Future<void> retry() async {
    final currentState = _state;
    if (currentState is MyReviewsLoaded && currentState.loadMoreError != null) {
      _state = currentState.copyWith(clearLoadMoreError: true);
      await loadMore();
    } else {
      await loadInitial();
    }
  }

  /// Atualiza os dados de uma avaliação localmente sem recarregar a lista inteira.
  void updateReview(FeedReview updatedReview) {
    final currentState = _state;
    if (currentState is! MyReviewsLoaded) return;

    final index = currentState.reviews.indexWhere((r) => r.id == updatedReview.id);
    if (index == -1) return;

    final updatedList = List<FeedReview>.from(currentState.reviews);
    updatedList[index] = updatedReview;
    _state = currentState.copyWith(reviews: updatedList);
    notifyListeners();
  }

  /// Remove uma avaliação da lista localmente após exclusão confirmada.
  void removeReview(String reviewId) {
    _removedReviewIds.add(reviewId);

    final currentState = _state;
    if (currentState is! MyReviewsLoaded) return;

    final updatedList = currentState.reviews.where((r) => r.id != reviewId).toList();
    if (updatedList.length == currentState.reviews.length) return;

    if (updatedList.isEmpty) {
      if (currentState.hasMore) {
        // Existem mais itens no servidor: recarrega a partir da primeira página
        // para não exibir o estado vazio prematuramente.
        loadInitial();
        return;
      }
      _state = const MyReviewsEmpty();
    } else {
      _state = currentState.copyWith(
        reviews: updatedList,
        totalElements: math.max(0, currentState.totalElements - 1),
      );
    }
    notifyListeners();
  }
}
