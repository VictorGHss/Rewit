import 'package:flutter/foundation.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/features/feed/domain/entities/feed_entities.dart';
import 'package:rewit_mobile/features/place/domain/entities/place_detail.dart';
import 'package:rewit_mobile/features/place/domain/entities/target_stats.dart';
import '../../domain/entities/product_identifier.dart';
import '../../domain/repositories/product_repository.dart';
import 'product_detail_state.dart';

/// Gerenciador de estado reativo para a tela de Detalhes do Produto (ProductDetailScreen).
class ProductDetailNotifier extends ChangeNotifier {
  final ProductRepository repository;
  final String productId;
  final PlaceDetail? contextPlace;

  ProductDetailState _state = const ProductDetailInitial();
  ProductDetailState get state => _state;

  ProductDetailNotifier({
    required this.repository,
    required this.productId,
    this.contextPlace,
  });

  /// Dispara o carregamento inicial do produto, identificadores, estatísticas e avaliações.
  Future<void> loadProduct() async {
    _state = const ProductDetailLoading();
    notifyListeners();

    try {
      final product = await repository.getProductById(productId);

      // Produtos fora de ACTIVE são apresentados indistinguivelmente como indisponíveis
      if (product.status != 'ACTIVE') {
        _state = const ProductDetailNotFound();
        notifyListeners();
        return;
      }

      // Identificadores públicos
      var identifiers = <ProductIdentifier>[];
      try {
        identifiers = await repository.getProductIdentifiers(productId);
      } catch (_) {
        // Falha transitória nos identificadores não impede a visualização do produto
      }

      // Estatísticas agregadas
      TargetStats? stats;
      try {
        stats = await repository.getProductStats(productId);
      } catch (_) {
        // Estatísticas podem falhar ou estar ausentes sem quebrar o produto
      }

      // Avaliações paginadas (página 0)
      var reviews = <FeedReview>[];
      var isLast = true;
      var totalElements = 0;
      try {
        final reviewsPage = await repository.getProductReviews(
          productId,
          page: 0,
          size: 10,
          sort: 'newest',
          verifiedOnly: false,
        );
        reviews = reviewsPage.reviews;
        isLast = reviewsPage.isLast;
        totalElements = reviewsPage.totalElements;
      } catch (_) {
        // Falha transitória na listagem de reviews não quebra a visualização do produto
      }

      _state = ProductDetailLoaded(
        product: product,
        identifiers: identifiers,
        stats: stats,
        reviews: reviews,
        currentPage: 0,
        isLastPage: isLast,
        totalElements: totalElements,
        places: contextPlace != null ? [contextPlace!] : const [],
      );
      notifyListeners();
    } on ApiException catch (e) {
      if (e.isNotFound) {
        _state = const ProductDetailNotFound();
      } else {
        _state = ProductDetailError(e.detail);
      }
      notifyListeners();
    } on NetworkException catch (e) {
      _state = ProductDetailError(e.message);
      notifyListeners();
    } catch (e) {
      _state = ProductDetailError(e.toString());
      notifyListeners();
    }
  }

  /// Recarrega as informações completas do produto do zero.
  Future<void> refresh() async {
    await loadProduct();
  }

  /// Carrega a próxima página de avaliações preservando os dados já carregados.
  Future<void> loadMoreReviews() async {
    final currentState = _state;
    if (currentState is! ProductDetailLoaded) return;
    if (currentState.isLastPage || currentState.isLoadingMoreReviews) return;

    _state = currentState.copyWith(
      isLoadingMoreReviews: true,
      clearLoadMoreReviewsError: true,
    );
    notifyListeners();

    final nextPage = currentState.currentPage + 1;
    try {
      final pageResult = await repository.getProductReviews(
        productId,
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
        isLoadingMoreReviews: false,
        clearLoadMoreReviewsError: true,
      );
      notifyListeners();
    } on ApiException catch (e) {
      _state = currentState.copyWith(
        isLoadingMoreReviews: false,
        loadMoreReviewsError: e.detail,
      );
      notifyListeners();
    } on NetworkException catch (e) {
      _state = currentState.copyWith(
        isLoadingMoreReviews: false,
        loadMoreReviewsError: e.message,
      );
      notifyListeners();
    } catch (_) {
      _state = currentState.copyWith(
        isLoadingMoreReviews: false,
        loadMoreReviewsError: 'Não foi possível carregar mais avaliações.',
      );
      notifyListeners();
    }
  }
}

