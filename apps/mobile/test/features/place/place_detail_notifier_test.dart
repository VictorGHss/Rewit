import 'package:flutter_test/flutter_test.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/features/feed/domain/entities/feed_entities.dart';
import 'package:rewit_mobile/features/place/domain/entities/place_detail.dart';
import 'package:rewit_mobile/features/place/domain/entities/target_reviews_page.dart';
import 'package:rewit_mobile/features/place/domain/entities/target_stats.dart';
import 'package:rewit_mobile/features/place/domain/repositories/place_repository.dart';
import 'package:rewit_mobile/features/place/presentation/state/place_detail_notifier.dart';
import 'package:rewit_mobile/features/place/presentation/state/place_detail_state.dart';

class _FakePlaceRepository implements PlaceRepository {
  PlaceDetail? placeToReturn;
  TargetStats? statsToReturn;
  TargetReviewsPage? reviewsPageToReturn;
  Exception? placeException;
  Exception? statsException;
  Exception? reviewsException;

  int getReviewsCallCount = 0;
  int? lastPageRequested;

  @override
  Future<PlaceDetail> getPlaceById(String id) async {
    if (placeException != null) throw placeException!;
    return placeToReturn ??
        const PlaceDetail(
          id: 'place-1',
          name: 'Restaurante Central',
          slug: 'restaurante-central',
          category: 'GASTRONOMIA',
          addressText: 'Rua Principal, 50',
          city: 'Curitiba',
          state: 'PR',
          latitude: -25.4,
          longitude: -49.2,
          validationRadiusMeters: 100,
          origin: 'USER',
          isVerified: true,
          status: 'ACTIVE',
        );
  }

  @override
  Future<TargetStats> getTargetStats(String id) async {
    if (statsException != null) throw statsException!;
    return statsToReturn ??
        const TargetStats(
          targetId: 'place-1',
          averageRating: 4.5,
          reviewsCount: 10,
        );
  }

  @override
  Future<TargetReviewsPage> getTargetReviews(
    String targetId, {
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
}

FeedReview _createFakeReview(String id) {
  return FeedReview(
    id: id,
    author: FeedAuthor(
      id: 'author-$id',
      displayName: 'Avaliador $id',
      handle: 'avaliador_$id',
    ),
    experienceText: 'Experiência $id',
    visibility: 'PUBLIC',
    status: 'ACTIVE',
    createdAt: DateTime(2026, 10, 8),
    targets: [
      FeedTarget(
        id: 'target-item-$id',
        targetId: 'place-1',
        rating: 5.0,
      )
    ],
  );
}

void main() {
  group('PlaceDetailNotifier Tests', () {
    late _FakePlaceRepository repository;
    late PlaceDetailNotifier notifier;

    setUp(() {
      repository = _FakePlaceRepository();
      notifier = PlaceDetailNotifier(
        repository: repository,
        placeId: 'place-1',
      );
    });

    test('estado inicial é PlaceDetailInitial', () {
      expect(notifier.state, isA<PlaceDetailInitial>());
    });

    test('loadPlace carrega place, stats e reviews com sucesso', () async {
      repository.reviewsPageToReturn = TargetReviewsPage(
        reviews: [_createFakeReview('rev-1')],
        pageNumber: 0,
        pageSize: 10,
        totalElements: 1,
        totalPages: 1,
        isLast: true,
      );

      await notifier.loadPlace();

      expect(notifier.state, isA<PlaceDetailLoaded>());
      final loaded = notifier.state as PlaceDetailLoaded;
      expect(loaded.place.name, 'Restaurante Central');
      expect(loaded.stats?.averageRating, 4.5);
      expect(loaded.reviews.length, 1);
      expect(loaded.reviews.first.id, 'rev-1');
      expect(loaded.isLastPage, isTrue);
    });

    test('loadPlace transiciona para PlaceDetailNotFound quando recebe 404', () async {
      repository.placeException = const ApiException(
        ProblemDetail(
          type: 'https://api.rewit.com/errors/place-not-found',
          status: 404,
          title: 'Not Found',
          detail: 'Local não encontrado',
          code: 'PLACE_NOT_FOUND',
        ),
      );

      await notifier.loadPlace();

      expect(notifier.state, isA<PlaceDetailNotFound>());
      final notFound = notifier.state as PlaceDetailNotFound;
      expect(notFound.message, 'Local indisponível');
    });

    test('loadPlace transiciona para PlaceDetailNotFound se status do local não for ACTIVE', () async {
      repository.placeToReturn = const PlaceDetail(
        id: 'place-1',
        name: 'Restaurante Inativo',
        slug: 'restaurante-inativo',
        category: 'GASTRONOMIA',
        addressText: 'Rua Principal',
        city: 'Curitiba',
        state: 'PR',
        latitude: -25.4,
        longitude: -49.2,
        validationRadiusMeters: 100,
        origin: 'USER',
        isVerified: false,
        status: 'DEACTIVATED',
      );

      await notifier.loadPlace();

      expect(notifier.state, isA<PlaceDetailNotFound>());
    });

    test('loadPlace transiciona para PlaceDetailError em falha de conexão', () async {
      repository.placeException = const NetworkException('Sem conexão com o servidor');

      await notifier.loadPlace();

      expect(notifier.state, isA<PlaceDetailError>());
      final error = notifier.state as PlaceDetailError;
      expect(error.message, 'Sem conexão com o servidor');
    });

    test('loadPlace tolera falha em stats mantendo os dados do local', () async {
      repository.statsException = const NetworkException('Falha ao obter stats');

      await notifier.loadPlace();

      expect(notifier.state, isA<PlaceDetailLoaded>());
      final loaded = notifier.state as PlaceDetailLoaded;
      expect(loaded.place.name, 'Restaurante Central');
      expect(loaded.stats, isNull);
    });

    test('loadMoreReviews incrementa página e anexa novos reviews evitando duplicatas', () async {
      repository.reviewsPageToReturn = TargetReviewsPage(
        reviews: [_createFakeReview('rev-1')],
        pageNumber: 0,
        pageSize: 10,
        totalElements: 2,
        totalPages: 2,
        isLast: false,
      );

      await notifier.loadPlace();
      final loadedBefore = notifier.state as PlaceDetailLoaded;
      expect(loadedBefore.reviews.length, 1);
      expect(loadedBefore.currentPage, 0);

      // Próxima página
      repository.reviewsPageToReturn = TargetReviewsPage(
        reviews: [_createFakeReview('rev-1'), _createFakeReview('rev-2')],
        pageNumber: 1,
        pageSize: 10,
        totalElements: 2,
        totalPages: 2,
        isLast: true,
      );

      await notifier.loadMoreReviews();

      expect(repository.lastPageRequested, 1);
      final loadedAfter = notifier.state as PlaceDetailLoaded;
      expect(loadedAfter.reviews.length, 2);
      expect(loadedAfter.reviews.map((r) => r.id).toList(), ['rev-1', 'rev-2']);
      expect(loadedAfter.currentPage, 1);
      expect(loadedAfter.isLastPage, isTrue);
      expect(loadedAfter.loadMoreError, isNull);
    });

    test('loadMoreReviews com erro preserva reviews anteriores e preenche loadMoreError', () async {
      repository.reviewsPageToReturn = TargetReviewsPage(
        reviews: [_createFakeReview('rev-1')],
        pageNumber: 0,
        pageSize: 10,
        totalElements: 5,
        totalPages: 2,
        isLast: false,
      );

      await notifier.loadPlace();

      repository.reviewsException = const NetworkException('Falha ao carregar página 2');

      await notifier.loadMoreReviews();

      final loaded = notifier.state as PlaceDetailLoaded;
      expect(loaded.reviews.length, 1);
      expect(loaded.loadMoreError, 'Falha ao carregar página 2');
      expect(loaded.isLoadingMore, isFalse);
    });
  });
}
