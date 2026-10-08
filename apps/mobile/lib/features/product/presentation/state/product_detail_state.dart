import 'package:rewit_mobile/features/feed/domain/entities/feed_entities.dart';
import 'package:rewit_mobile/features/place/domain/entities/place_detail.dart';
import 'package:rewit_mobile/features/place/domain/entities/target_stats.dart';
import '../../domain/entities/product_detail.dart';
import '../../domain/entities/product_identifier.dart';

/// Estados reativos da tela de Detalhes do Produto (ProductDetailScreen).
sealed class ProductDetailState {
  const ProductDetailState();
}

/// Estado inicial antes de disparar o carregamento.
class ProductDetailInitial extends ProductDetailState {
  const ProductDetailInitial();
}

/// Estado de carregamento inicial das informações do produto.
class ProductDetailLoading extends ProductDetailState {
  const ProductDetailLoading();
}

/// Estado quando o produto não é encontrado ou não está ativo (404 / não-ativo).
class ProductDetailNotFound extends ProductDetailState {
  final String message;
  const ProductDetailNotFound({this.message = 'Produto indisponível'});
}

/// Estado de erro genérico ao carregar o produto.
class ProductDetailError extends ProductDetailState {
  final String message;
  const ProductDetailError(this.message);
}

/// Estado de sucesso com dados do produto carregados.
class ProductDetailLoaded extends ProductDetailState {
  final ProductDetail product;
  final List<ProductIdentifier> identifiers;
  final TargetStats? stats;
  final List<FeedReview> reviews;
  final int currentPage;
  final bool isLastPage;
  final int totalElements;
  final bool isLoadingMoreReviews;
  final String? loadMoreReviewsError;
  final List<PlaceDetail> places;
  final bool isLoadingPlaces;
  final String? placesError;

  const ProductDetailLoaded({
    required this.product,
    this.identifiers = const [],
    this.stats,
    this.reviews = const [],
    this.currentPage = 0,
    this.isLastPage = true,
    this.totalElements = 0,
    this.isLoadingMoreReviews = false,
    this.loadMoreReviewsError,
    this.places = const [],
    this.isLoadingPlaces = false,
    this.placesError,
  });

  ProductDetailLoaded copyWith({
    ProductDetail? product,
    List<ProductIdentifier>? identifiers,
    TargetStats? stats,
    List<FeedReview>? reviews,
    int? currentPage,
    bool? isLastPage,
    int? totalElements,
    bool? isLoadingMoreReviews,
    String? loadMoreReviewsError,
    bool clearLoadMoreReviewsError = false,
    List<PlaceDetail>? places,
    bool? isLoadingPlaces,
    String? placesError,
    bool clearPlacesError = false,
  }) {
    return ProductDetailLoaded(
      product: product ?? this.product,
      identifiers: identifiers ?? this.identifiers,
      stats: stats ?? this.stats,
      reviews: reviews ?? this.reviews,
      currentPage: currentPage ?? this.currentPage,
      isLastPage: isLastPage ?? this.isLastPage,
      totalElements: totalElements ?? this.totalElements,
      isLoadingMoreReviews: isLoadingMoreReviews ?? this.isLoadingMoreReviews,
      loadMoreReviewsError: clearLoadMoreReviewsError
          ? null
          : (loadMoreReviewsError ?? this.loadMoreReviewsError),
      places: places ?? this.places,
      isLoadingPlaces: isLoadingPlaces ?? this.isLoadingPlaces,
      placesError: clearPlacesError ? null : (placesError ?? this.placesError),
    );
  }
}

