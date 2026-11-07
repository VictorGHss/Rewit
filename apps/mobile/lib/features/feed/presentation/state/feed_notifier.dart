import 'dart:math' as math;
import 'package:flutter/foundation.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import '../../domain/entities/feed_entities.dart';
import '../../domain/repositories/feed_repository.dart';
import 'feed_state.dart';

/// Canal de estado isolado para uma fonte específica de feed (forYou ou following).
class _FeedChannel {
  final FeedType type;
  FeedState state = const FeedInitial();
  int currentRequestId = 0;
  final Set<String> removedReviewIds = {};
  bool needsPaginationReset = false;

  _FeedChannel(this.type);

  void reset() {
    currentRequestId++;
    removedReviewIds.clear();
    needsPaginationReset = false;
    state = const FeedInitial();
  }
}

/// Gerenciador reativo de estado dos feeds sociais (Home V2 - ChangeNotifier).
///
/// Suporta dois canais de feed independentes:
/// - [FeedType.forYou]: Feed V2 ranqueado com diversidade (/api/v2/feed).
/// - [FeedType.following]: Feed V1 dos usuários seguidos (/api/v1/feed).
///
/// Mantém estado, paginação, refresh, reset e erros isolados por canal,
/// enquanto centraliza a sincronização de mutações de avaliações (voto útil,
/// edição e remoção) entre todas as instâncias existentes em ambos os feeds.
class FeedNotifier extends ChangeNotifier {
  final FeedRepository _feedRepository;
  FeedType _activeFeedType;
  int _session = 0;
  final Set<String> _pendingHelpfulReviewIds = {};

  final Map<FeedType, _FeedChannel> _channels = {
    FeedType.forYou: _FeedChannel(FeedType.forYou),
    FeedType.following: _FeedChannel(FeedType.following),
  };

  FeedNotifier({
    required FeedRepository feedRepository,
    FeedType initialType = FeedType.forYou,
  })  : _feedRepository = feedRepository,
        _activeFeedType = initialType;

  /// Tipo de feed atualmente ativo na interface (padrão [FeedType.forYou]).
  FeedType get activeFeedType => _activeFeedType;

  /// Altera o feed ativo e notifica os ouvintes somente se o tipo mudou.
  void setActiveFeedType(FeedType type) {
    if (_activeFeedType != type) {
      _activeFeedType = type;
      notifyListeners();
    }
  }

  /// Estado do feed atualmente ativo (compatibilidade retroativa com consumidores existentes).
  FeedState get state => stateOf(_activeFeedType);

  /// Retorna o [FeedState] de um canal específico.
  FeedState stateOf(FeedType type) => _channels[type]!.state;

  /// Alias de conveniência para [stateOf].
  FeedState getState(FeedType type) => stateOf(type);

  /// Verifica se há uma alternância de voto útil em andamento para a avaliação.
  bool isTogglingHelpful(String reviewId) => _pendingHelpfulReviewIds.contains(reviewId);

  /// Carrega a primeira página do feed (página 0) para o tipo especificado (ou feed ativo).
  ///
  /// Ignora chamadas concorrentes duplicadas caso o canal já esteja em carregamento ([FeedLoading]).
  Future<void> loadInitial({FeedType? type, int size = 10}) async {
    final targetType = type ?? _activeFeedType;
    final channel = _channels[targetType]!;

    // Evita requisições redundantes se já estiver em carregamento inicial
    if (channel.state is FeedLoading) return;

    final isFirstLoad = channel.state is FeedInitial;
    await _executeLoadInitial(
      channel,
      size: size,
      clearRemovedReviewIds: isFirstLoad,
    );
  }

  Future<void> _executeLoadInitial(
    _FeedChannel channel, {
    required int size,
    bool clearRemovedReviewIds = false,
  }) async {
    if (clearRemovedReviewIds) {
      channel.removedReviewIds.clear();
    }
    channel.needsPaginationReset = false;
    final requestId = ++channel.currentRequestId;
    final session = _session;

    channel.state = const FeedLoading();
    notifyListeners();

    try {
      final FeedPage feedPage;
      switch (channel.type) {
        case FeedType.forYou:
          feedPage = await _feedRepository.getFeed(page: 0, size: size);
          break;
        case FeedType.following:
          feedPage = await _feedRepository.getFollowingFeed(page: 0, size: size);
          break;
      }

      // Resposta obsoleta descartada (cancelamento por refresh/reset/nova requisição)
      if (requestId != channel.currentRequestId || session != _session) {
        return;
      }

      final activeReviews = feedPage.items
          .where((r) => !channel.removedReviewIds.contains(r.id))
          .toList();

      if (activeReviews.isEmpty && !feedPage.hasMore) {
        channel.state = const FeedEmpty();
      } else {
        channel.state = FeedSuccess(
          reviews: activeReviews,
          currentPage: feedPage.page,
          totalPages: feedPage.totalPages,
          hasMore: feedPage.hasMore,
        );
      }
      notifyListeners();
    } on ApiException catch (e) {
      if (requestId == channel.currentRequestId && session == _session) {
        channel.state = FeedError(message: e.detail, code: e.errorCode);
        notifyListeners();
      }
    } on NetworkException catch (e) {
      if (requestId == channel.currentRequestId && session == _session) {
        channel.state = FeedError(message: e.message);
        notifyListeners();
      }
    } catch (_) {
      if (requestId == channel.currentRequestId && session == _session) {
        channel.state = const FeedError(message: 'Não foi possível carregar o feed.');
        notifyListeners();
      }
    }
  }

  /// Recarrega o feed desde o início (Pull-to-refresh) de forma independente.
  Future<void> refresh({FeedType? type, int size = 10}) async {
    final targetType = type ?? _activeFeedType;
    final channel = _channels[targetType]!;
    // Incrementa requestId para invalidar imediatamente qualquer loadMore pendente
    channel.currentRequestId++;
    await _executeLoadInitial(channel, size: size, clearRemovedReviewIds: true);
  }

  /// Carrega a próxima página (infinite scroll / paginação) com deduplicação por ID.
  Future<void> loadMore({FeedType? type, int size = 10}) async {
    final targetType = type ?? _activeFeedType;
    final channel = _channels[targetType]!;
    final currentState = channel.state;

    if (currentState is! FeedSuccess) return;
    if (!currentState.hasMore || currentState.isLoadingMore) return;
    if (currentState.loadMoreError != null) return;

    final requestId = channel.currentRequestId;
    final session = _session;

    channel.state = currentState.copyWith(isLoadingMore: true, clearLoadMoreError: true);
    notifyListeners();

    try {
      final nextPage = channel.needsPaginationReset ? 0 : currentState.currentPage + 1;
      channel.needsPaginationReset = false;

      final FeedPage feedPage;
      switch (channel.type) {
        case FeedType.forYou:
          feedPage = await _feedRepository.getFeed(page: nextPage, size: size);
          break;
        case FeedType.following:
          feedPage = await _feedRepository.getFollowingFeed(page: nextPage, size: size);
          break;
      }

      if (requestId != channel.currentRequestId || session != _session) {
        return; // Resposta obsoleta descartada se refresh foi acionado
      }

      if (channel.state is! FeedSuccess) return;
      final liveState = channel.state as FeedSuccess;

      // Deduplicação estrita de itens pelo identificador da review e exclusão de itens removidos
      final existingIds = liveState.reviews.map((r) => r.id).toSet();
      final newItems = feedPage.items
          .where((r) => !existingIds.contains(r.id) && !channel.removedReviewIds.contains(r.id))
          .toList();
      final combined = [...liveState.reviews, ...newItems];

      channel.state = liveState.copyWith(
        reviews: combined,
        currentPage: feedPage.page,
        totalPages: feedPage.totalPages,
        hasMore: feedPage.hasMore,
        isLoadingMore: false,
        clearLoadMoreError: true,
      );
      notifyListeners();
    } on ApiException catch (e) {
      if (requestId == channel.currentRequestId && session == _session && channel.state is FeedSuccess) {
        channel.state = (channel.state as FeedSuccess).copyWith(
          isLoadingMore: false,
          loadMoreError: e.detail,
        );
        notifyListeners();
      }
    } on NetworkException catch (e) {
      if (requestId == channel.currentRequestId && session == _session && channel.state is FeedSuccess) {
        channel.state = (channel.state as FeedSuccess).copyWith(
          isLoadingMore: false,
          loadMoreError: e.message,
        );
        notifyListeners();
      }
    } catch (_) {
      if (requestId == channel.currentRequestId && session == _session && channel.state is FeedSuccess) {
        channel.state = (channel.state as FeedSuccess).copyWith(
          isLoadingMore: false,
          loadMoreError: 'Erro ao carregar mais avaliações.',
        );
        notifyListeners();
      }
    }
  }

  /// Repete o carregamento que falhou no canal especificado (ou feed ativo).
  Future<void> retry({FeedType? type}) async {
    final targetType = type ?? _activeFeedType;
    final channel = _channels[targetType]!;
    final currentState = channel.state;

    if (currentState is FeedSuccess && currentState.loadMoreError != null) {
      channel.state = currentState.copyWith(clearLoadMoreError: true);
      await loadMore(type: targetType);
    } else {
      await loadInitial(type: targetType);
    }
  }

  /// Descarta o estado de um feed específico ou de ambos (quando [type] for nulo, ex.: logout).
  void reset({FeedType? type}) {
    if (type != null) {
      _channels[type]!.reset();
    } else {
      _session++;
      for (final channel in _channels.values) {
        channel.reset();
      }
      _pendingHelpfulReviewIds.clear();
    }
    notifyListeners();
  }

  /// Alterna o voto útil (helpful) de uma avaliação de forma otimista com reversão em caso de erro,
  /// sincronizando o estado em todos os feeds em que a avaliação estiver presente.
  Future<void> toggleHelpful(String reviewId) async {
    if (_pendingHelpfulReviewIds.contains(reviewId)) return;

    // Localiza a avaliação em qualquer canal em FeedSuccess
    FeedReview? targetReview;
    for (final channel in _channels.values) {
      final state = channel.state;
      if (state is FeedSuccess) {
        for (final r in state.reviews) {
          if (r.id == reviewId) {
            targetReview = r;
            break;
          }
        }
        if (targetReview != null) break;
      }
    }

    if (targetReview == null) return;

    final wasHelpful = targetReview.isHelpfulByMe;
    final nextHelpful = !wasHelpful;

    // Registra os estados de voto originais por canal para restaurar apenas eles em caso de erro,
    // preservando novas avaliações anexadas via loadMore concorrente e edições em outros campos.
    final previousVoteStates = <FeedType, ({bool isHelpfulByMe, int helpfulCount})>{};

    for (final entry in _channels.entries) {
      final state = entry.value.state;
      if (state is FeedSuccess) {
        final index = state.reviews.indexWhere((r) => r.id == reviewId);
        if (index != -1) {
          final current = state.reviews[index];
          previousVoteStates[entry.key] = (
            isHelpfulByMe: current.isHelpfulByMe,
            helpfulCount: current.helpfulCount,
          );
          final nextCount = math.max(0, current.helpfulCount + (nextHelpful ? 1 : -1));
          final optimisticReview = current.copyWith(
            isHelpfulByMe: nextHelpful,
            helpfulCount: nextCount,
          );
          final updatedReviews = List<FeedReview>.from(state.reviews);
          updatedReviews[index] = optimisticReview;
          entry.value.state = state.copyWith(reviews: updatedReviews);
        }
      }
    }

    final session = _session;
    _pendingHelpfulReviewIds.add(reviewId);
    notifyListeners();

    try {
      final result = await _feedRepository.toggleHelpful(
        reviewId,
        currentlyHelpful: wasHelpful,
      );

      // Voto de uma sessão já encerrada não altera o feed da sessão atual
      if (session != _session) return;

      // Reconcilia com a resposta canônica do backend em todos os feeds
      bool anyUpdated = false;
      for (final channel in _channels.values) {
        final state = channel.state;
        if (state is FeedSuccess) {
          final containsReview = state.reviews.any((r) => r.id == reviewId);
          if (containsReview) {
            final reconciledReviews = state.reviews.map((r) {
              if (r.id == reviewId) {
                return r.copyWith(
                  isHelpfulByMe: result.helpful,
                  helpfulCount: math.max(0, result.helpfulCount),
                );
              }
              return r;
            }).toList();
            channel.state = state.copyWith(reviews: reconciledReviews);
            anyUpdated = true;
          }
        }
      }
      if (anyUpdated) {
        notifyListeners();
      }
    } catch (e) {
      // Reversão precisa em caso de falha de rede/API: restaura apenas o estado de voto da avaliação alvo,
      // preservando novos itens anexados por loadMore concorrente e edições em outras avaliações.
      if (session == _session) {
        for (final entry in previousVoteStates.entries) {
          final channel = _channels[entry.key]!;
          if (channel.state is FeedSuccess) {
            final liveState = channel.state as FeedSuccess;
            final restoredReviews = liveState.reviews.map((r) {
              if (r.id == reviewId) {
                return r.copyWith(
                  isHelpfulByMe: entry.value.isHelpfulByMe,
                  helpfulCount: entry.value.helpfulCount,
                );
              }
              return r;
            }).toList();
            channel.state = liveState.copyWith(reviews: restoredReviews);
          }
        }
        notifyListeners();
      }
      rethrow;
    } finally {
      if (session == _session) {
        _pendingHelpfulReviewIds.remove(reviewId);
        notifyListeners();
      }
    }
  }

  /// Atualiza os dados de uma avaliação localmente sem recarregar os feeds,
  /// sincronizando in-place em todos os canais em que a avaliação estiver presente.
  void updateReview(FeedReview updatedReview) {
    bool anyUpdated = false;

    for (final channel in _channels.values) {
      final state = channel.state;
      if (state is FeedSuccess) {
        final contains = state.reviews.any((r) => r.id == updatedReview.id);
        if (contains) {
          final updatedList = state.reviews.map((r) {
            return r.id == updatedReview.id ? updatedReview : r;
          }).toList();
          channel.state = state.copyWith(reviews: updatedList);
          anyUpdated = true;
        }
      }
    }

    if (anyUpdated) {
      notifyListeners();
    }
  }

  /// Remove uma avaliação localmente sem recarregar o feed inteiro,
  /// sincronizando a exclusão em todos os canais em que a avaliação estiver presente.
  void removeReview(String reviewId) {
    bool anyChanged = false;

    for (final channel in _channels.values) {
      channel.removedReviewIds.add(reviewId);
      final state = channel.state;
      if (state is FeedSuccess) {
        final filtered = state.reviews.where((r) => r.id != reviewId).toList();
        if (filtered.length != state.reviews.length) {
          channel.needsPaginationReset = true;
          anyChanged = true;

          if (filtered.isEmpty) {
            if (state.hasMore) {
              // Recarrega canal específico caso tenha mais páginas para evitar estado vazio prematuro,
              // preservando os IDs removidos para não ressuscitar itens excluídos.
              _executeLoadInitial(channel, size: 10, clearRemovedReviewIds: false);
            } else {
              channel.state = const FeedEmpty();
            }
          } else {
            channel.state = state.copyWith(reviews: filtered);
          }
        }
      }
    }

    if (anyChanged) {
      notifyListeners();
    }
  }
}
