import '../../../feed/domain/entities/feed_entities.dart';
import '../../domain/entities/place_detail.dart';
import '../../domain/entities/target_stats.dart';

/// Estados reativos da tela de Detalhes do Local (PlaceDetailScreen).
sealed class PlaceDetailState {
  const PlaceDetailState();
}

/// Estado inicial antes de qualquer disparo de carregamento.
class PlaceDetailInitial extends PlaceDetailState {
  const PlaceDetailInitial();
}

/// Estado de carregamento inicial das informações do local, estatísticas e avaliações.
class PlaceDetailLoading extends PlaceDetailState {
  const PlaceDetailLoading();
}

/// Estado quando o local não é encontrado ou está inativo (404 / não-ativo).
class PlaceDetailNotFound extends PlaceDetailState {
  final String message;
  const PlaceDetailNotFound({this.message = 'Local indisponível'});
}

/// Estado de erro genérico ao carregar detalhes (falha de rede, 500, etc).
class PlaceDetailError extends PlaceDetailState {
  final String message;
  const PlaceDetailError(this.message);
}

/// Estado de sucesso com dados do local carregados.
class PlaceDetailLoaded extends PlaceDetailState {
  final PlaceDetail place;
  final TargetStats? stats;
  final List<FeedReview> reviews;
  final int currentPage;
  final bool isLastPage;
  final int totalElements;
  final bool isLoadingMore;
  final String? loadMoreError;

  const PlaceDetailLoaded({
    required this.place,
    this.stats,
    this.reviews = const [],
    this.currentPage = 0,
    this.isLastPage = true,
    this.totalElements = 0,
    this.isLoadingMore = false,
    this.loadMoreError,
  });

  PlaceDetailLoaded copyWith({
    PlaceDetail? place,
    TargetStats? stats,
    List<FeedReview>? reviews,
    int? currentPage,
    bool? isLastPage,
    int? totalElements,
    bool? isLoadingMore,
    String? loadMoreError,
    bool clearLoadMoreError = false,
  }) {
    return PlaceDetailLoaded(
      place: place ?? this.place,
      stats: stats ?? this.stats,
      reviews: reviews ?? this.reviews,
      currentPage: currentPage ?? this.currentPage,
      isLastPage: isLastPage ?? this.isLastPage,
      totalElements: totalElements ?? this.totalElements,
      isLoadingMore: isLoadingMore ?? this.isLoadingMore,
      loadMoreError: clearLoadMoreError ? null : (loadMoreError ?? this.loadMoreError),
    );
  }
}
