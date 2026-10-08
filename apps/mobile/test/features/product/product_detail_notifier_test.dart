import 'dart:typed_data';
import 'package:flutter_test/flutter_test.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/features/feed/domain/entities/feed_entities.dart';
import 'package:rewit_mobile/features/place/domain/entities/place_detail.dart';
import 'package:rewit_mobile/features/place/domain/entities/target_reviews_page.dart';
import 'package:rewit_mobile/features/place/domain/entities/target_stats.dart';
import 'package:rewit_mobile/features/product/domain/entities/product_detail.dart';
import 'package:rewit_mobile/features/product/domain/entities/product_identifier.dart';
import 'package:rewit_mobile/features/product/domain/entities/products_in_place_page.dart';
import 'package:rewit_mobile/features/product/domain/repositories/product_repository.dart';
import 'package:rewit_mobile/features/product/presentation/state/product_detail_notifier.dart';
import 'package:rewit_mobile/features/product/presentation/state/product_detail_state.dart';

class _FakeProductRepository implements ProductRepository {
  ProductDetail? productToReturn;
  List<ProductIdentifier>? identifiersToReturn;
  TargetStats? statsToReturn;
  TargetReviewsPage? reviewsPageToReturn;
  ProductsInPlacePage? productsInPlaceToReturn;

  Exception? productException;
  Exception? identifiersException;
  Exception? statsException;
  Exception? reviewsException;

  int getReviewsCallCount = 0;
  int? lastPageRequested;

  @override
  Future<ProductDetail> getProductById(String id) async {
    if (productException != null) throw productException!;
    return productToReturn ??
        const ProductDetail(
          id: 'prod-1',
          name: 'Café Especial',
          brand: 'Orfeu',
          model: '250g',
          description: 'Café em grãos selecionados.',
          category: 'BEBIDA',
          status: 'ACTIVE',
        );
  }

  @override
  Future<List<ProductIdentifier>> getProductIdentifiers(String id) async {
    if (identifiersException != null) throw identifiersException!;
    return identifiersToReturn ??
        const [
          ProductIdentifier(identifierType: 'EAN', identifierValue: '7891234567890'),
        ];
  }

  @override
  Future<ProductDetail> getProductByIdentifier({
    required String type,
    required String value,
  }) async {
    return getProductById('prod-1');
  }

  @override
  Future<ProductsInPlacePage> getProductsInPlace(
    String placeId, {
    int page = 0,
    int size = 20,
  }) async {
    return productsInPlaceToReturn ??
        const ProductsInPlacePage(
          products: [],
          pageNumber: 0,
          pageSize: 20,
          totalElements: 0,
          totalPages: 0,
          isLast: true,
        );
  }

  @override
  Future<TargetStats> getProductStats(String id) async {
    if (statsException != null) throw statsException!;
    return statsToReturn ??
        const TargetStats(
          targetId: 'prod-1',
          averageRating: 4.8,
          reviewsCount: 15,
        );
  }

  @override
  Future<TargetReviewsPage> getProductReviews(
    String productId, {
    int page = 0,
    int size = 10,
    String sort = 'newest',
    bool verifiedOnly = false,
  }) async {
    getReviewsCallCount++;
    lastPageRequested = page;
    if (reviewsException != null) throw reviewsException!;
    return reviewsPageToReturn ??
        const TargetReviewsPage(
          reviews: [],
          pageNumber: 0,
          pageSize: 10,
          totalElements: 0,
          totalPages: 0,
          isLast: true,
        );
  }

  @override
  Future<Uint8List> getProductImageBytes(String imageUrl) async {
    return Uint8List.fromList([1, 2, 3]);
  }
}

FeedReview _createFakeReview(String id) {
  return FeedReview(
    id: id,
    author: FeedAuthor(
      id: 'author-$id',
      displayName: 'Avaliador $id',
      handle: 'avaliador_$id',
    ),
    experienceText: 'Avaliação excelente $id',
    visibility: 'PUBLIC',
    status: 'ACTIVE',
    createdAt: DateTime(2026, 10, 8),
    targets: [
      FeedTarget(
        id: 'target-item-$id',
        targetId: 'prod-1',
        rating: 5.0,
      )
    ],
  );
}

void main() {
  group('ProductDetailNotifier Tests', () {
    late _FakeProductRepository repository;
    late ProductDetailNotifier notifier;

    setUp(() {
      repository = _FakeProductRepository();
      notifier = ProductDetailNotifier(
        repository: repository,
        productId: 'prod-1',
      );
    });

    test('estado inicial é ProductDetailInitial', () {
      expect(notifier.state, isA<ProductDetailInitial>());
    });

    test('loadProduct com sucesso carrega produto, identificadores, stats e reviews', () async {
      await notifier.loadProduct();

      expect(notifier.state, isA<ProductDetailLoaded>());
      final loaded = notifier.state as ProductDetailLoaded;
      expect(loaded.product.id, 'prod-1');
      expect(loaded.product.name, 'Café Especial');
      expect(loaded.identifiers.length, 1);
      expect(loaded.identifiers.first.identifierValue, '7891234567890');
      expect(loaded.stats?.averageRating, 4.8);
      expect(loaded.reviews, isEmpty);
      expect(loaded.places, isEmpty);
    });

    test('loadProduct com contextPlace preserva local vinculado em places', () async {
      const place = PlaceDetail(
        id: 'place-origin',
        name: 'Empório Gourmet',
        slug: 'emporio-gourmet',
        category: 'MERCADO',
        addressText: 'Av Central, 100',
        city: 'Curitiba',
        state: 'PR',
        latitude: -25.4,
        longitude: -49.2,
        validationRadiusMeters: 50,
        origin: 'USER',
        isVerified: true,
        status: 'ACTIVE',
      );

      final notifierWithContext = ProductDetailNotifier(
        repository: repository,
        productId: 'prod-1',
        contextPlace: place,
      );

      await notifierWithContext.loadProduct();

      expect(notifierWithContext.state, isA<ProductDetailLoaded>());
      final loaded = notifierWithContext.state as ProductDetailLoaded;
      expect(loaded.places.length, 1);
      expect(loaded.places.first.name, 'Empório Gourmet');
    });

    test('loadProduct quando status do produto não é ACTIVE define ProductDetailNotFound', () async {
      repository.productToReturn = const ProductDetail(
        id: 'prod-inactive',
        name: 'Produto Descontinuado',
        brand: 'Marca',
        category: 'GERAL',
        status: 'DISCONTINUED',
      );

      await notifier.loadProduct();

      expect(notifier.state, isA<ProductDetailNotFound>());
      final notFound = notifier.state as ProductDetailNotFound;
      expect(notFound.message, 'Produto indisponível');
    });

    test('loadProduct quando API retorna 404 define ProductDetailNotFound', () async {
      repository.productException = const ApiException(
        ProblemDetail(
          type: 'about:blank',
          title: 'Not Found',
          status: 404,
          detail: 'Produto indisponível ou inexistente.',
          code: 'PRODUCT_NOT_FOUND',
        ),
      );

      await notifier.loadProduct();

      expect(notifier.state, isA<ProductDetailNotFound>());
      final notFound = notifier.state as ProductDetailNotFound;
      expect(notFound.message, 'Produto indisponível');
    });

    test('loadProduct quando API retorna 500 define ProductDetailError', () async {
      repository.productException = const ApiException(
        ProblemDetail(
          type: 'about:blank',
          title: 'Internal Server Error',
          status: 500,
          detail: 'Erro interno ao processar requisição.',
          code: 'INTERNAL_ERROR',
        ),
      );

      await notifier.loadProduct();

      expect(notifier.state, isA<ProductDetailError>());
      final err = notifier.state as ProductDetailError;
      expect(err.message, 'Erro interno ao processar requisição.');
    });

    test('loadProduct tolera falhas isoladas em stats e identificadores', () async {
      repository.identifiersException = const NetworkException('Falha temporária');
      repository.statsException = const NetworkException('Falha temporária');

      await notifier.loadProduct();

      expect(notifier.state, isA<ProductDetailLoaded>());
      final loaded = notifier.state as ProductDetailLoaded;
      expect(loaded.product.id, 'prod-1');
      expect(loaded.identifiers, isEmpty);
      expect(loaded.stats, isNull);
    });

    test('loadMoreReviews pagina avaliações e deduplica por id', () async {
      final rev1 = _createFakeReview('rev-1');
      final rev2 = _createFakeReview('rev-2');
      final rev3 = _createFakeReview('rev-3');

      repository.reviewsPageToReturn = TargetReviewsPage(
        reviews: [rev1, rev2],
        pageNumber: 0,
        pageSize: 2,
        totalElements: 3,
        totalPages: 2,
        isLast: false,
      );

      await notifier.loadProduct();

      var loaded = notifier.state as ProductDetailLoaded;
      expect(loaded.reviews.length, 2);
      expect(loaded.isLastPage, isFalse);

      repository.reviewsPageToReturn = TargetReviewsPage(
        reviews: [rev2, rev3], // rev2 duplicada propositalmente
        pageNumber: 1,
        pageSize: 2,
        totalElements: 3,
        totalPages: 2,
        isLast: true,
      );

      await notifier.loadMoreReviews();

      loaded = notifier.state as ProductDetailLoaded;
      expect(loaded.reviews.length, 3);
      expect(loaded.reviews.map((r) => r.id).toList(), ['rev-1', 'rev-2', 'rev-3']);
      expect(loaded.currentPage, 1);
      expect(loaded.isLastPage, isTrue);
    });

    test('loadMoreReviews ignora chamada quando já está na última página', () async {
      repository.reviewsPageToReturn = const TargetReviewsPage(
        reviews: [],
        pageNumber: 0,
        pageSize: 10,
        totalElements: 0,
        totalPages: 1,
        isLast: true,
      );

      await notifier.loadProduct();
      expect(repository.getReviewsCallCount, 1);

      await notifier.loadMoreReviews();
      expect(repository.getReviewsCallCount, 1);
    });

    test('loadMoreReviews registra erro em loadMoreReviewsError sem quebrar loaded state', () async {
      repository.reviewsPageToReturn = TargetReviewsPage(
        reviews: [_createFakeReview('rev-1')],
        pageNumber: 0,
        pageSize: 1,
        totalElements: 2,
        totalPages: 2,
        isLast: false,
      );

      await notifier.loadProduct();

      repository.reviewsException = const NetworkException('Sem conexão com internet');

      await notifier.loadMoreReviews();

      final loaded = notifier.state as ProductDetailLoaded;
      expect(loaded.isLoadingMoreReviews, isFalse);
      expect(loaded.loadMoreReviewsError, 'Sem conexão com internet');
      expect(loaded.reviews.length, 1);
    });
  });
}
