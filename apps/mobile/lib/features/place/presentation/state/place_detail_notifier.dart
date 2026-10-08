import 'package:flutter/foundation.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import '../../../feed/domain/entities/feed_entities.dart';
import '../../domain/entities/target_stats.dart';
import '../../domain/repositories/place_repository.dart';
import 'place_detail_state.dart';

/// Gerenciador de estado reativo para os detalhes do local, estatísticas e listagem paginada de avaliações.
class PlaceDetailNotifier extends ChangeNotifier {
  final PlaceRepository repository;
  final String placeId;

  PlaceDetailState _state = const PlaceDetailInitial();
  PlaceDetailState get state => _state;

  PlaceDetailNotifier({
    required this.repository,
    required this.placeId,
  });

  /// Dispara o carregamento completo do local e suas informações associadas.
  Future<void> loadPlace() async {
    _state = const PlaceDetailLoading();
    notifyListeners();

    try {
      final place = await repository.getPlaceById(placeId);

      // Locais inativos, suspensos ou excluídos são apresentados genericamente como indisponíveis
      if (place.status != 'ACTIVE') {
        _state = const PlaceDetailNotFound();
        notifyListeners();
        return;
      }

      // Carregar estatísticas e avaliações associadas
      TargetStats? stats;
      try {
        stats = await repository.getTargetStats(placeId);
      } catch (_) {
        // Estatísticas ausentes ou sem avaliações não devem impedir a renderização do local
      }

      var reviews = <FeedReview>[];
      var isLast = true;
      var totalElements = 0;
      try {
        final reviewsPage = await repository.getTargetReviews(
          placeId,
          page: 0,
          size: 10,
          sort: 'newest',
          verifiedOnly: false,
        );
        reviews = reviewsPage.reviews;
        isLast = reviewsPage.isLast;
        totalElements = reviewsPage.totalElements;
      } catch (_) {
        // Falha transitória na listagem de reviews não quebra a visualização do local
      }

      _state = PlaceDetailLoaded(
        place: place,
        stats: stats,
        reviews: reviews,
        currentPage: 0,
        isLastPage: isLast,
        totalElements: totalElements,
      );
      notifyListeners();
    } on ApiException catch (e) {
      if (e.isNotFound) {
        _state = const PlaceDetailNotFound();
      } else {
        _state = PlaceDetailError(e.detail);
      }
      notifyListeners();
    } on NetworkException catch (e) {
      _state = PlaceDetailError(e.message);
      notifyListeners();
    } catch (e) {
      _state = PlaceDetailError(e.toString());
      notifyListeners();
    }
  }

  /// Recarrega do zero as informações e a primeira página de avaliações.
  Future<void> refresh() async {
    await loadPlace();
  }

  /// Carrega a próxima página de avaliações, preservando as avaliações já exibidas.
  Future<void> loadMoreReviews() async {
    final currentState = _state;
    if (currentState is! PlaceDetailLoaded) return;
    if (currentState.isLastPage || currentState.isLoadingMore) return;

    _state = currentState.copyWith(
      isLoadingMore: true,
      clearLoadMoreError: true,
    );
    notifyListeners();

    final nextPage = currentState.currentPage + 1;
    try {
      final pageResult = await repository.getTargetReviews(
        placeId,
        page: nextPage,
        size: 10,
        sort: 'newest',
        verifiedOnly: false,
      );

      final existingIds = currentState.reviews.map((r) => r.id).toSet();
      final newReviews = pageResult.reviews.where((r) => !existingIds.contains(r.id)).toList();

      _state = currentState.copyWith(
        reviews: [...currentState.reviews, ...newReviews],
        currentPage: nextPage,
        isLastPage: pageResult.isLast,
        totalElements: pageResult.totalElements,
        isLoadingMore: false,
        clearLoadMoreError: true,
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
        loadMoreError: 'Não foi possível carregar mais avaliações.',
      );
      notifyListeners();
    }
  }
}
