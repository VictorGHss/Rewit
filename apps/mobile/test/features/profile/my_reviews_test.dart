import 'dart:async';
import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:rewit_mobile/app/router/app_router.dart';
import 'package:rewit_mobile/app/theme/app_theme.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/core/network/http_client.dart';
import 'package:rewit_mobile/core/storage/token_storage.dart';
import 'package:rewit_mobile/features/auth/data/models/auth_tokens.dart';
import 'package:rewit_mobile/features/auth/data/models/auth_user_dto.dart';
import 'package:rewit_mobile/features/auth/domain/entities/auth_state.dart';
import 'package:rewit_mobile/features/auth/domain/repositories/auth_repository.dart';
import 'package:rewit_mobile/features/auth/presentation/state/auth_notifier.dart';
import 'package:rewit_mobile/features/feed/domain/entities/feed_entities.dart';
import 'package:rewit_mobile/features/place/domain/entities/target_reviews_page.dart';
import 'package:rewit_mobile/features/profile/data/repositories/user_profile_repository_impl.dart';
import 'package:rewit_mobile/features/profile/domain/entities/follow_user_summary.dart';
import 'package:rewit_mobile/features/profile/domain/entities/update_profile_input.dart';
import 'package:rewit_mobile/features/profile/domain/entities/user_profile.dart';
import 'package:rewit_mobile/features/profile/domain/repositories/user_profile_repository.dart';
import 'package:rewit_mobile/features/profile/presentation/screens/my_reviews_screen.dart';
import 'package:rewit_mobile/features/profile/presentation/screens/user_profile_screen.dart';
import 'package:rewit_mobile/features/profile/presentation/state/my_reviews_notifier.dart';
import 'package:rewit_mobile/features/profile/presentation/state/my_reviews_state.dart';

// ---------------------------------------------------------------------------
// Mocks e Fakes
// ---------------------------------------------------------------------------

class MockHttpBaseClient extends http.BaseClient {
  http.Request? capturedRequest;
  http.Response? responseToReturn;
  Exception? exceptionToThrow;

  @override
  Future<http.StreamedResponse> send(http.BaseRequest request) async {
    capturedRequest = request as http.Request;
    if (exceptionToThrow != null) {
      throw exceptionToThrow!;
    }
    final resp = responseToReturn ??
        http.Response(
          jsonEncode({
            'content': [],
            'pageNumber': 0,
            'pageSize': 10,
            'totalElements': 0,
            'totalPages': 0,
            'isLast': true,
          }),
          200,
          headers: {'content-type': 'application/json; charset=utf-8'},
        );
    return http.StreamedResponse(
      Stream.value(resp.bodyBytes),
      resp.statusCode,
      headers: resp.headers,
    );
  }
}

class FakeUserProfileRepository implements UserProfileRepository {
  FutureOr<TargetReviewsPage> Function({int page, int size})? getMyReviewsHandler;
  int getMyReviewsCallCount = 0;
  int? lastRequestedPage;
  int? lastRequestedSize;

  @override
  Future<TargetReviewsPage> getMyReviews({int page = 0, int size = 10}) async {
    getMyReviewsCallCount++;
    lastRequestedPage = page;
    lastRequestedSize = size;
    if (getMyReviewsHandler != null) {
      return getMyReviewsHandler!(page: page, size: size);
    }
    return const TargetReviewsPage(
      reviews: [],
      pageNumber: 0,
      pageSize: 10,
      totalElements: 0,
      totalPages: 0,
      isLast: true,
    );
  }

  @override
  Future<UserProfile> getUserProfile(String userId) async {
    return UserProfile(
      id: userId,
      handle: 'otheruser',
      displayName: 'Outro Usuário',
      stats: const UserStats(),
    );
  }

  @override
  Future<UserProfile> updateMyProfile(UpdateProfileInput input) async {
    throw UnimplementedError();
  }

  @override
  Future<bool> followUser(String userId) async => true;

  @override
  Future<bool> unfollowUser(String userId) async => false;

  @override
  Future<PagedFollowUsers> getFollowers(String userId, {int page = 0, int size = 10}) async {
    return const PagedFollowUsers(
      pageNumber: 0,
      pageSize: 10,
      totalElements: 0,
      totalPages: 0,
      isLast: true,
      items: [],
    );
  }

  @override
  Future<PagedFollowUsers> getFollowing(String userId, {int page = 0, int size = 10}) async {
    return const PagedFollowUsers(
      pageNumber: 0,
      pageSize: 10,
      totalElements: 0,
      totalPages: 0,
      isLast: true,
      items: [],
    );
  }
}

class FakeAuthRepo implements AuthRepository {
  final AuthUserDto user;

  FakeAuthRepo({
    this.user = const AuthUserDto(
      id: 'my-user-id',
      email: 'eu@rewit.app',
      handle: 'meuhandle',
      displayName: 'Meu Nome',
      isVerified: true,
      reputationScore: 100,
    ),
  });

  @override
  Future<bool> hasStoredSession() async => true;

  @override
  Future<AuthUserDto> getMe() async => user;

  @override
  Future<Authenticated> login({required String email, required String password}) async {
    return Authenticated(
      user: user,
      tokens: const AuthTokens(accessToken: 'token', refreshToken: 'refresh'),
    );
  }

  @override
  Future<Authenticated> refreshTokens() async => Authenticated(
        user: user,
        tokens: const AuthTokens(accessToken: 'token', refreshToken: 'refresh'),
      );

  @override
  Future<void> logout() async {}

  @override
  Future<void> deactivateAccount() async {}

  @override
  Future<Authenticated> reactivate({required String email, required String password}) async {
    return Authenticated(
      user: user,
      tokens: const AuthTokens(accessToken: 'token', refreshToken: 'refresh'),
    );
  }

  @override
  Future<void> changePassword({
    required String currentPassword,
    required String newPassword,
  }) async {}
}

FeedReview createSampleReview({
  String id = 'rev-1',
  String displayName = 'Meu Nome',
  String? handle = 'meuhandle',
  bool isAnonymous = false,
  String experienceText = 'Excelente experiência!',
  double rating = 4.5,
  int helpfulCount = 3,
}) {
  return FeedReview(
    id: id,
    author: FeedAuthor(
      id: isAnonymous ? null : 'my-user-id',
      displayName: isAnonymous ? 'Anônimo' : displayName,
      handle: isAnonymous ? null : handle,
      isAnonymous: isAnonymous,
    ),
    experienceText: experienceText,
    isAnonymous: isAnonymous,
    isVerifiedOnSite: true,
    visibility: 'PUBLIC',
    status: 'ACTIVE',
    createdAt: DateTime(2026, 10, 8, 12, 0),
    helpfulCount: helpfulCount,
    isMine: true,
    targets: [
      FeedTarget(
        id: 'target-rel-1',
        targetId: 'place-1',
        rating: rating,
        targetType: 'PLACE',
      ),
    ],
  );
}

// ---------------------------------------------------------------------------
// Testes
// ---------------------------------------------------------------------------

void main() {
  group('UserProfileRepositoryImpl - getMyReviews', () {
    late MockHttpBaseClient mockClient;
    late InMemoryTokenStorage tokenStorage;
    late RewitHttpClient httpClient;
    late UserProfileRepositoryImpl repository;

    setUp(() async {
      mockClient = MockHttpBaseClient();
      tokenStorage = InMemoryTokenStorage();
      await tokenStorage.saveTokens(
        accessToken: 'valid-token',
        refreshToken: 'refresh-token',
      );
      httpClient = RewitHttpClient(
        baseUrl: 'https://api.rewit.app',
        client: mockClient,
        tokenStorage: tokenStorage,
      );
      repository = UserProfileRepositoryImpl(httpClient: httpClient);
    });

    test('primeira página consulta endpoint correto com parâmetros padrão e autenticação', () async {
      mockClient.responseToReturn = http.Response(
        jsonEncode({
          'content': [
            {
              'id': 'rev-1',
              'author': {
                'id': 'my-user-id',
                'handle': 'meuhandle',
                'displayName': 'Meu Nome',
                'avatarUrl': null,
                'isAnonymous': false,
              },
              'experienceText': 'Comida deliciosa!',
              'isAnonymous': false,
              'isVerifiedOnSite': true,
              'visibility': 'PUBLIC',
              'status': 'ACTIVE',
              'createdAt': '2026-10-08T12:00:00Z',
              'targets': [
                {
                  'id': 't-1',
                  'targetId': 'place-1',
                  'rating': 5.0,
                  'targetType': 'PLACE',
                }
              ],
              'helpfulCount': 2,
              'isHelpfulByMe': false,
              'isMine': true,
            }
          ],
          'pageNumber': 0,
          'pageSize': 10,
          'totalElements': 1,
          'totalPages': 1,
          'isLast': true,
        }),
        200,
        headers: {'content-type': 'application/json; charset=utf-8'},
      );

      final result = await repository.getMyReviews(page: 0, size: 10);

      expect(mockClient.capturedRequest, isNotNull);
      expect(mockClient.capturedRequest!.url.path, '/api/v1/me/reviews');
      expect(mockClient.capturedRequest!.url.queryParameters['page'], '0');
      expect(mockClient.capturedRequest!.url.queryParameters['size'], '10');
      expect(mockClient.capturedRequest!.url.queryParameters.containsKey('userId'), isFalse);
      expect(mockClient.capturedRequest!.headers['authorization'], 'Bearer valid-token');

      expect(result.reviews.length, 1);
      expect(result.reviews.first.id, 'rev-1');
      expect(result.reviews.first.experienceText, 'Comida deliciosa!');
      expect(result.reviews.first.isMine, isTrue);
      expect(result.pageNumber, 0);
      expect(result.pageSize, 10);
      expect(result.totalElements, 1);
      expect(result.totalPages, 1);
      expect(result.isLast, isTrue);
    });

    test('paginação com página 1 e tamanho 5 envia query parameters esperados', () async {
      mockClient.responseToReturn = http.Response(
        jsonEncode({
          'content': [],
          'pageNumber': 1,
          'pageSize': 5,
          'totalElements': 6,
          'totalPages': 2,
          'isLast': true,
        }),
        200,
        headers: {'content-type': 'application/json; charset=utf-8'},
      );

      final result = await repository.getMyReviews(page: 1, size: 5);

      expect(mockClient.capturedRequest!.url.queryParameters['page'], '1');
      expect(mockClient.capturedRequest!.url.queryParameters['size'], '5');
      expect(result.pageNumber, 1);
      expect(result.pageSize, 5);
      expect(result.totalElements, 6);
      expect(result.totalPages, 2);
    });

    test('resposta vazia é mapeada para lista vazia com flags coerentes', () async {
      mockClient.responseToReturn = http.Response(
        jsonEncode({
          'content': [],
          'pageNumber': 0,
          'pageSize': 10,
          'totalElements': 0,
          'totalPages': 0,
          'isLast': true,
        }),
        200,
        headers: {'content-type': 'application/json; charset=utf-8'},
      );

      final result = await repository.getMyReviews();

      expect(result.isEmpty, isTrue);
      expect(result.reviews, isEmpty);
      expect(result.totalElements, 0);
    });

    test('erro RFC 7807 propaga ApiException apropriada', () async {
      mockClient.responseToReturn = http.Response(
        jsonEncode({
          'type': 'https://api.rewit.app/errors/unauthorized',
          'title': 'Não Autorizado',
          'status': 401,
          'detail': 'Token inválido ou expirado.',
        }),
        401,
        headers: {'content-type': 'application/problem+json'},
      );

      expect(
        () => repository.getMyReviews(),
        throwsA(isA<ApiException>().having((e) => e.statusCode, 'statusCode', 401)),
      );
    });

    test('erro de rede propaga NetworkException', () async {
      mockClient.exceptionToThrow = http.ClientException('Falha de conexão');

      expect(
        () => repository.getMyReviews(),
        throwsA(isA<NetworkException>()),
      );
    });

    test('review anônima é deserializada com autor protegido sem expor id ou handle', () async {
      mockClient.responseToReturn = http.Response(
        jsonEncode({
          'content': [
            {
              'id': 'rev-anon-1',
              'author': {
                'id': null,
                'handle': null,
                'displayName': 'Anônimo',
                'avatarUrl': null,
                'isAnonymous': true,
              },
              'experienceText': 'Opinião sincera anônima',
              'isAnonymous': true,
              'isVerifiedOnSite': true,
              'visibility': 'PUBLIC',
              'status': 'ACTIVE',
              'createdAt': '2026-10-08T12:00:00Z',
              'targets': [],
              'helpfulCount': 5,
              'isHelpfulByMe': false,
              'isMine': true,
            }
          ],
          'pageNumber': 0,
          'pageSize': 10,
          'totalElements': 1,
          'totalPages': 1,
          'isLast': true,
        }),
        200,
        headers: {'content-type': 'application/json; charset=utf-8'},
      );

      final result = await repository.getMyReviews();
      final rev = result.reviews.first;

      expect(rev.isAnonymous, isTrue);
      expect(rev.author.id, isNull);
      expect(rev.author.handle, isNull);
      expect(rev.author.displayName, 'Anônimo');
      expect(rev.author.displayHandle, 'Anônimo');
    });
  });

  group('MyReviewsNotifier', () {
    late FakeUserProfileRepository repo;
    late MyReviewsNotifier notifier;

    setUp(() {
      repo = FakeUserProfileRepository();
      notifier = MyReviewsNotifier(repository: repo);
    });

    test('loadInitial com sucesso transiciona para MyReviewsLoaded', () async {
      final sample = createSampleReview();
      repo.getMyReviewsHandler = ({page = 0, size = 10}) {
        return TargetReviewsPage(
          reviews: [sample],
          pageNumber: 0,
          pageSize: 10,
          totalElements: 1,
          totalPages: 1,
          isLast: true,
        );
      };

      expect(notifier.state, isA<MyReviewsInitial>());
      final future = notifier.loadInitial();
      expect(notifier.state, isA<MyReviewsLoading>());
      await future;

      expect(notifier.state, isA<MyReviewsLoaded>());
      final loaded = notifier.state as MyReviewsLoaded;
      expect(loaded.reviews.length, 1);
      expect(loaded.reviews.first.id, 'rev-1');
      expect(loaded.currentPage, 0);
      expect(loaded.isLastPage, isTrue);
      expect(loaded.hasMore, isFalse);
    });

    test('loadInitial com lista vazia transiciona para MyReviewsEmpty', () async {
      repo.getMyReviewsHandler = ({page = 0, size = 10}) {
        return const TargetReviewsPage(
          reviews: [],
          pageNumber: 0,
          pageSize: 10,
          totalElements: 0,
          totalPages: 0,
          isLast: true,
        );
      };

      await notifier.loadInitial();
      expect(notifier.state, isA<MyReviewsEmpty>());
    });

    test('loadInitial com ApiException transiciona para MyReviewsError com detalhe', () async {
      repo.getMyReviewsHandler = ({page = 0, size = 10}) {
        throw const ApiException(
          ProblemDetail(
            type: 'https://api.rewit.app/errors/internal-error',
            title: 'Erro',
            status: 500,
            detail: 'Falha no servidor ao buscar avaliações.',
          ),
        );
      };

      await notifier.loadInitial();
      expect(notifier.state, isA<MyReviewsError>());
      final err = notifier.state as MyReviewsError;
      expect(err.message, 'Falha no servidor ao buscar avaliações.');
    });

    test('loadInitial com NetworkException transiciona para MyReviewsError', () async {
      repo.getMyReviewsHandler = ({page = 0, size = 10}) {
        throw const NetworkException('Sem conexão');
      };

      await notifier.loadInitial();
      expect(notifier.state, isA<MyReviewsError>());
      final err = notifier.state as MyReviewsError;
      expect(err.message, 'Sem conexão');
    });

    test('loadMore incrementa página e deduplica itens repetidos', () async {
      final rev1 = createSampleReview(id: 'rev-1');
      final rev2 = createSampleReview(id: 'rev-2');
      repo.getMyReviewsHandler = ({page = 0, size = 10}) {
        if (page == 0) {
          return TargetReviewsPage(
            reviews: [rev1],
            pageNumber: 0,
            pageSize: 1,
            totalElements: 2,
            totalPages: 2,
            isLast: false,
          );
        } else {
          // Retorna rev1 (duplicata acidental) e rev2 (novo)
          return TargetReviewsPage(
            reviews: [rev1, rev2],
            pageNumber: 1,
            pageSize: 1,
            totalElements: 2,
            totalPages: 2,
            isLast: true,
          );
        }
      };

      await notifier.loadInitial();
      expect((notifier.state as MyReviewsLoaded).reviews.length, 1);

      await notifier.loadMore();
      final loaded = notifier.state as MyReviewsLoaded;
      expect(loaded.reviews.length, 2);
      expect(loaded.reviews[0].id, 'rev-1');
      expect(loaded.reviews[1].id, 'rev-2');
      expect(loaded.currentPage, 1);
      expect(loaded.isLastPage, isTrue);
    });

    test('loadMore não executa chamadas quando hasMore é falso ou isLastPage é true', () async {
      final rev1 = createSampleReview(id: 'rev-1');
      repo.getMyReviewsHandler = ({page = 0, size = 10}) {
        return TargetReviewsPage(
          reviews: [rev1],
          pageNumber: 0,
          pageSize: 10,
          totalElements: 1,
          totalPages: 1,
          isLast: true,
        );
      };

      await notifier.loadInitial();
      expect(repo.getMyReviewsCallCount, 1);

      await notifier.loadMore();
      expect(repo.getMyReviewsCallCount, 1);
    });

    test('proteção contra chamadas duplicadas concorrentes de loadInitial e loadMore', () async {
      repo.getMyReviewsHandler = ({page = 0, size = 10}) {
        return TargetReviewsPage(
          reviews: [createSampleReview()],
          pageNumber: 0,
          pageSize: 10,
          totalElements: 10,
          totalPages: 2,
          isLast: false,
        );
      };

      // Dispara loadInitial concorrente duas vezes
      final f1 = notifier.loadInitial();
      final f2 = notifier.loadInitial();
      await Future.wait([f1, f2]);
      expect(repo.getMyReviewsCallCount, 1);

      // Dispara loadMore concorrente duas vezes
      final m1 = notifier.loadMore();
      final m2 = notifier.loadMore();
      await Future.wait([m1, m2]);
      expect(repo.getMyReviewsCallCount, 2);
    });

    test('refresh recarrega da página 0 e descarta respostas obsoletas de paginação', () async {
      final rev1 = createSampleReview(id: 'rev-1');
      repo.getMyReviewsHandler = ({page = 0, size = 10}) {
        return TargetReviewsPage(
          reviews: [rev1],
          pageNumber: 0,
          pageSize: 10,
          totalElements: 1,
          totalPages: 1,
          isLast: true,
        );
      };

      await notifier.loadInitial();
      expect(repo.getMyReviewsCallCount, 1);

      await notifier.refresh();
      expect(repo.getMyReviewsCallCount, 2);
      expect(notifier.state, isA<MyReviewsLoaded>());
    });

    test('retry aciona loadInitial após erro inicial', () async {
      bool shouldFail = true;
      repo.getMyReviewsHandler = ({page = 0, size = 10}) {
        if (shouldFail) {
          throw const NetworkException('Erro de rede inicial');
        }
        return TargetReviewsPage(
          reviews: [createSampleReview()],
          pageNumber: 0,
          pageSize: 10,
          totalElements: 1,
          totalPages: 1,
          isLast: true,
        );
      };

      await notifier.loadInitial();
      expect(notifier.state, isA<MyReviewsError>());

      shouldFail = false;
      await notifier.retry();
      expect(notifier.state, isA<MyReviewsLoaded>());
    });

    test('retry aciona loadMore após falha na paginação incremental', () async {
      bool shouldFail = false;
      repo.getMyReviewsHandler = ({page = 0, size = 10}) {
        if (page == 0) {
          return TargetReviewsPage(
            reviews: [createSampleReview(id: 'rev-1')],
            pageNumber: 0,
            pageSize: 1,
            totalElements: 2,
            totalPages: 2,
            isLast: false,
          );
        }
        if (shouldFail) {
          throw const NetworkException('Erro no load-more');
        }
        return TargetReviewsPage(
          reviews: [createSampleReview(id: 'rev-2')],
          pageNumber: 1,
          pageSize: 1,
          totalElements: 2,
          totalPages: 2,
          isLast: true,
        );
      };

      await notifier.loadInitial();
      shouldFail = true;
      await notifier.loadMore();

      final state = notifier.state as MyReviewsLoaded;
      expect(state.loadMoreError, 'Erro no load-more');

      shouldFail = false;
      await notifier.retry();

      final stateAfterRetry = notifier.state as MyReviewsLoaded;
      expect(stateAfterRetry.reviews.length, 2);
      expect(stateAfterRetry.loadMoreError, isNull);
    });

    test('updateReview atualiza item localmente mantendo os demais intactos', () async {
      final rev1 = createSampleReview(id: 'rev-1', experienceText: 'Texto antigo');
      final rev2 = createSampleReview(id: 'rev-2', experienceText: 'Outro');
      repo.getMyReviewsHandler = ({page = 0, size = 10}) {
        return TargetReviewsPage(
          reviews: [rev1, rev2],
          pageNumber: 0,
          pageSize: 2,
          totalElements: 2,
          totalPages: 1,
          isLast: true,
        );
      };

      await notifier.loadInitial();

      final updatedRev1 = rev1.copyWith(experienceText: 'Texto atualizado com sucesso!');
      notifier.updateReview(updatedRev1);

      final loaded = notifier.state as MyReviewsLoaded;
      expect(loaded.reviews[0].experienceText, 'Texto atualizado com sucesso!');
      expect(loaded.reviews[1].experienceText, 'Outro');
    });

    test('removeReview remove item localmente e transiciona para Empty quando esvaziar', () async {
      final rev1 = createSampleReview(id: 'rev-1');
      final rev2 = createSampleReview(id: 'rev-2');
      repo.getMyReviewsHandler = ({page = 0, size = 10}) {
        return TargetReviewsPage(
          reviews: [rev1, rev2],
          pageNumber: 0,
          pageSize: 2,
          totalElements: 2,
          totalPages: 1,
          isLast: true,
        );
      };

      await notifier.loadInitial();

      notifier.removeReview('rev-1');
      var loaded = notifier.state as MyReviewsLoaded;
      expect(loaded.reviews.length, 1);
      expect(loaded.reviews.first.id, 'rev-2');
      expect(loaded.totalElements, 1);

      notifier.removeReview('rev-2');
      expect(notifier.state, isA<MyReviewsEmpty>());
    });

    test('removeReview não exibe MyReviewsEmpty prematuramente quando hasMore é true e recarrega página 0 (Item 5)', () async {
      final rev1 = createSampleReview(id: 'rev-1');
      final revShifted = createSampleReview(id: 'rev-shifted');

      int page0CallCount = 0;
      repo.getMyReviewsHandler = ({page = 0, size = 10}) {
        if (page == 0) {
          page0CallCount++;
          if (page0CallCount == 1) {
            return TargetReviewsPage(
              reviews: [rev1],
              pageNumber: 0,
              pageSize: 1,
              totalElements: 2,
              totalPages: 2,
              isLast: false,
            );
          } else {
            return TargetReviewsPage(
              reviews: [revShifted],
              pageNumber: 0,
              pageSize: 1,
              totalElements: 1,
              totalPages: 1,
              isLast: true,
            );
          }
        }
        return const TargetReviewsPage(
          reviews: [],
          pageNumber: 1,
          pageSize: 1,
          totalElements: 2,
          totalPages: 2,
          isLast: true,
        );
      };

      await notifier.loadInitial();
      expect(notifier.state, isA<MyReviewsLoaded>());
      expect((notifier.state as MyReviewsLoaded).hasMore, isTrue);

      // Remove o único item carregado localmente
      notifier.removeReview('rev-1');

      // Não deve exibir Empty; deve recarregar a partir da primeira página
      await pumpEventQueue();
      expect(notifier.state, isA<MyReviewsLoaded>());
      final loaded = notifier.state as MyReviewsLoaded;
      expect(loaded.reviews.length, 1);
      expect(loaded.reviews.first.id, 'rev-shifted');
    });

    test('concorrência: removeReview durante loadMore em andamento não ressuscita a avaliação excluída (Item 4)', () async {
      final rev1 = createSampleReview(id: 'rev-1');
      final rev2 = createSampleReview(id: 'rev-2');
      final rev3 = createSampleReview(id: 'rev-3');

      final completer = Completer<TargetReviewsPage>();

      repo.getMyReviewsHandler = ({page = 0, size = 10}) {
        if (page == 0) {
          return TargetReviewsPage(
            reviews: [rev1, rev2],
            pageNumber: 0,
            pageSize: 2,
            totalElements: 3,
            totalPages: 2,
            isLast: false,
          );
        } else {
          return completer.future;
        }
      };

      await notifier.loadInitial();
      expect((notifier.state as MyReviewsLoaded).reviews.map((r) => r.id), contains('rev-1'));

      // Dispara loadMore em segundo plano
      final loadMoreFuture = notifier.loadMore();

      // Durante a chamada, remove 'rev-1'
      notifier.removeReview('rev-1');
      expect((notifier.state as MyReviewsLoaded).reviews.map((r) => r.id), isNot(contains('rev-1')));

      // Conclui loadMore da página 1 (que retorna 'rev-1' repetido e 'rev-3')
      completer.complete(TargetReviewsPage(
        reviews: [rev1, rev3],
        pageNumber: 1,
        pageSize: 2,
        totalElements: 3,
        totalPages: 2,
        isLast: true,
      ));
      await loadMoreFuture;

      // 'rev-1' NÃO foi ressuscitada
      final loaded = notifier.state as MyReviewsLoaded;
      expect(loaded.reviews.map((r) => r.id), isNot(contains('rev-1')));
      expect(loaded.reviews.map((r) => r.id), contains('rev-2'));
      expect(loaded.reviews.map((r) => r.id), contains('rev-3'));
    });

    test('concorrência: updateReview durante loadMore não é sobrescrito por estado obsoleto (Item 4)', () async {
      final rev1 = createSampleReview(id: 'rev-1', experienceText: 'Texto original');
      final rev2 = createSampleReview(id: 'rev-2');
      final rev3 = createSampleReview(id: 'rev-3');

      final completer = Completer<TargetReviewsPage>();

      repo.getMyReviewsHandler = ({page = 0, size = 10}) {
        if (page == 0) {
          return TargetReviewsPage(
            reviews: [rev1, rev2],
            pageNumber: 0,
            pageSize: 2,
            totalElements: 3,
            totalPages: 2,
            isLast: false,
          );
        } else {
          return completer.future;
        }
      };

      await notifier.loadInitial();

      // Dispara loadMore
      final loadMoreFuture = notifier.loadMore();

      // Enquanto a requisição corre, atualiza rev-1 localmente
      notifier.updateReview(rev1.copyWith(experienceText: 'Texto editado localmente'));

      completer.complete(TargetReviewsPage(
        reviews: [rev3],
        pageNumber: 1,
        pageSize: 2,
        totalElements: 3,
        totalPages: 2,
        isLast: true,
      ));
      await loadMoreFuture;

      // O texto editado localmente foi preservado
      final loaded = notifier.state as MyReviewsLoaded;
      expect(loaded.reviews.firstWhere((r) => r.id == 'rev-1').experienceText, 'Texto editado localmente');
    });

    test('paginação após remover item deduplica itens e evita duplicatas (Item 4)', () async {
      final rev1 = createSampleReview(id: 'rev-1');
      final rev2 = createSampleReview(id: 'rev-2');
      final rev3 = createSampleReview(id: 'rev-3');

      repo.getMyReviewsHandler = ({page = 0, size = 10}) {
        if (page == 0) {
          return TargetReviewsPage(
            reviews: [rev1, rev2],
            pageNumber: 0,
            pageSize: 2,
            totalElements: 3,
            totalPages: 2,
            isLast: false,
          );
        } else {
          // Devido ao deslocamento (offset shift), página 1 repete rev-2 e traz rev-3
          return TargetReviewsPage(
            reviews: [rev2, rev3],
            pageNumber: 1,
            pageSize: 2,
            totalElements: 3,
            totalPages: 2,
            isLast: true,
          );
        }
      };

      await notifier.loadInitial();

      // Remove rev-1
      notifier.removeReview('rev-1');

      // Pagina para a página 1
      await notifier.loadMore();

      final loaded = notifier.state as MyReviewsLoaded;
      // Não deve ter duplicata de rev-2
      expect(loaded.reviews.length, 2);
      expect(loaded.reviews[0].id, 'rev-2');
      expect(loaded.reviews[1].id, 'rev-3');
    });

    test('loadMore com erro bloqueia novas chamadas em scroll até chamada deliberada de retry (Item 4)', () async {
      final rev1 = createSampleReview(id: 'rev-1');

      bool shouldFail = true;
      repo.getMyReviewsHandler = ({page = 0, size = 10}) {
        if (page == 0) {
          return TargetReviewsPage(
            reviews: [rev1],
            pageNumber: 0,
            pageSize: 1,
            totalElements: 2,
            totalPages: 2,
            isLast: false,
          );
        } else {
          if (shouldFail) {
            throw const ApiException(ProblemDetail(
              type: 'about:blank',
              title: 'Erro de Servidor',
              status: 500,
              detail: 'Falha temporária ao carregar mais',
            ));
          }
          return TargetReviewsPage(
            reviews: [createSampleReview(id: 'rev-2')],
            pageNumber: 1,
            pageSize: 1,
            totalElements: 2,
            totalPages: 2,
            isLast: true,
          );
        }
      };

      await notifier.loadInitial();
      expect(repo.getMyReviewsCallCount, 1);

      // Primeiro loadMore falha
      await notifier.loadMore();
      expect(repo.getMyReviewsCallCount, 2);
      expect((notifier.state as MyReviewsLoaded).loadMoreError, isNotNull);

      // Chamada subsequente de scroll NÃO incrementa chamadas ao backend
      shouldFail = false;
      await notifier.loadMore();
      expect(repo.getMyReviewsCallCount, 2); // Não aumentou!

      // retry() deliberado tenta novamente com sucesso
      await notifier.retry();
      expect(repo.getMyReviewsCallCount, 3);
      expect((notifier.state as MyReviewsLoaded).currentPage, 1);
      expect((notifier.state as MyReviewsLoaded).loadMoreError, isNull);
    });
  });

  group('MyReviewsScreen Widget Tests', () {
    late FakeUserProfileRepository repo;

    setUp(() {
      repo = FakeUserProfileRepository();
    });

    testWidgets('renderiza estado de loading e em seguida lista com avaliações', (tester) async {
      final rev = createSampleReview(id: 'rev-1', experienceText: 'Pizza fantástica!');
      repo.getMyReviewsHandler = ({page = 0, size = 10}) {
        return TargetReviewsPage(
          reviews: [rev],
          pageNumber: 0,
          pageSize: 10,
          totalElements: 1,
          totalPages: 1,
          isLast: true,
        );
      };

      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.lightTheme,
          home: MyReviewsScreen(repository: repo),
        ),
      );

      // Aguarda carregamento
      await tester.pumpAndSettle();

      expect(find.text('Minhas avaliações'), findsOneWidget);
      expect(find.text('Pizza fantástica!'), findsOneWidget);
      expect(find.byKey(const Key('review_card_rev-1')), findsOneWidget);
    });

    testWidgets('renderiza estado vazio com mensagem apropriada', (tester) async {
      repo.getMyReviewsHandler = ({page = 0, size = 10}) {
        return const TargetReviewsPage(
          reviews: [],
          pageNumber: 0,
          pageSize: 10,
          totalElements: 0,
          totalPages: 0,
          isLast: true,
        );
      };

      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.lightTheme,
          home: MyReviewsScreen(repository: repo),
        ),
      );

      await tester.pumpAndSettle();

      expect(find.byKey(const Key('my_reviews_empty')), findsOneWidget);
      expect(find.text('Nenhuma avaliação encontrada'), findsOneWidget);
      expect(find.text('Você ainda não publicou nenhuma avaliação.'), findsOneWidget);
    });

    testWidgets('renderiza estado de erro e botão Tentar novamente aciona retry', (tester) async {
      bool fail = true;
      repo.getMyReviewsHandler = ({page = 0, size = 10}) {
        if (fail) {
          throw const NetworkException('Sem conexão com a internet');
        }
        return TargetReviewsPage(
          reviews: [createSampleReview(id: 'rev-recuperada')],
          pageNumber: 0,
          pageSize: 10,
          totalElements: 1,
          totalPages: 1,
          isLast: true,
        );
      };

      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.lightTheme,
          home: MyReviewsScreen(repository: repo),
        ),
      );

      await tester.pumpAndSettle();

      expect(find.text('Sem conexão com a internet'), findsOneWidget);
      expect(find.byKey(const Key('my_reviews_retry_button')), findsOneWidget);

      fail = false;
      await tester.tap(find.byKey(const Key('my_reviews_retry_button')));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('review_card_rev-recuperada')), findsOneWidget);
    });

    testWidgets('tocar no card navega para AppRouter.reviewDetail e remove item se excluído', (tester) async {
      final rev = createSampleReview(id: 'rev-to-delete', experienceText: 'Será excluída');
      repo.getMyReviewsHandler = ({page = 0, size = 10}) {
        return TargetReviewsPage(
          reviews: [rev],
          pageNumber: 0,
          pageSize: 10,
          totalElements: 1,
          totalPages: 1,
          isLast: true,
        );
      };

      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.lightTheme,
          onGenerateRoute: (settings) {
            if (settings.name == AppRouter.reviewDetail) {
              return MaterialPageRoute(
                builder: (context) => Scaffold(
                  body: Center(
                    child: ElevatedButton(
                      key: const Key('fake_delete_btn'),
                      onPressed: () {
                        Navigator.of(context).pop({'deleted': true, 'reviewId': 'rev-to-delete'});
                      },
                      child: const Text('Excluir'),
                    ),
                  ),
                ),
              );
            }
            return MaterialPageRoute(
              builder: (context) => MyReviewsScreen(repository: repo),
            );
          },
        ),
      );

      await tester.pumpAndSettle();
      expect(find.byKey(const Key('review_card_rev-to-delete')), findsOneWidget);

      // Toca no card para navegar ao detalhe
      await tester.tap(find.byKey(const Key('review_card_rev-to-delete')));
      await tester.pumpAndSettle();

      // Confirma que está no detalhe e clica em excluir
      expect(find.byKey(const Key('fake_delete_btn')), findsOneWidget);
      await tester.tap(find.byKey(const Key('fake_delete_btn')));
      await tester.pumpAndSettle();

      // De volta a Minhas Avaliações: o item foi removido localmente e agora está vazio
      expect(find.byKey(const Key('review_card_rev-to-delete')), findsNothing);
      expect(find.byKey(const Key('my_reviews_empty')), findsOneWidget);
    });

    testWidgets('tocar no card navega para AppRouter.reviewDetail e atualiza item se editado', (tester) async {
      final rev = createSampleReview(id: 'rev-to-edit', experienceText: 'Texto Original');
      repo.getMyReviewsHandler = ({page = 0, size = 10}) {
        return TargetReviewsPage(
          reviews: [rev],
          pageNumber: 0,
          pageSize: 10,
          totalElements: 1,
          totalPages: 1,
          isLast: true,
        );
      };

      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.lightTheme,
          onGenerateRoute: (settings) {
            if (settings.name == AppRouter.reviewDetail) {
              return MaterialPageRoute(
                builder: (context) => Scaffold(
                  body: Center(
                    child: ElevatedButton(
                      key: const Key('fake_edit_btn'),
                      onPressed: () {
                        final updated = rev.copyWith(experienceText: 'Texto Modificado no Detalhe');
                        Navigator.of(context).pop(updated);
                      },
                      child: const Text('Salvar'),
                    ),
                  ),
                ),
              );
            }
            return MaterialPageRoute(
              builder: (context) => MyReviewsScreen(repository: repo),
            );
          },
        ),
      );

      await tester.pumpAndSettle();
      expect(find.text('Texto Original'), findsOneWidget);

      // Toca no card para navegar ao detalhe
      await tester.tap(find.byKey(const Key('review_card_rev-to-edit')));
      await tester.pumpAndSettle();

      // Clica em salvar modificação
      await tester.tap(find.byKey(const Key('fake_edit_btn')));
      await tester.pumpAndSettle();

      // De volta a Minhas Avaliações: o item foi atualizado localmente
      expect(find.text('Texto Modificado no Detalhe'), findsOneWidget);
      expect(find.text('Texto Original'), findsNothing);
    });
  });

  group('Anonimato em Minhas Avaliações', () {
    late FakeUserProfileRepository repo;

    setUp(() {
      repo = FakeUserProfileRepository();
    });

    testWidgets('review anônima aparece com identificador Anônimo e sem expor userId ou handle', (tester) async {
      final revAnon = createSampleReview(
        id: 'rev-anon-999',
        displayName: 'Anônimo',
        handle: 'meu_handle_secreto', // no objeto interno o author handle para anônimo deve ser omitido
        isAnonymous: true,
        experienceText: 'Avaliação confidencial anônima',
      );

      repo.getMyReviewsHandler = ({page = 0, size = 10}) {
        return TargetReviewsPage(
          reviews: [revAnon],
          pageNumber: 0,
          pageSize: 10,
          totalElements: 1,
          totalPages: 1,
          isLast: true,
        );
      };

      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.lightTheme,
          home: MyReviewsScreen(repository: repo),
        ),
      );

      await tester.pumpAndSettle();

      // Aparece na lista normalmente
      expect(find.text('Avaliação confidencial anônima'), findsOneWidget);
      expect(find.text('Anônimo'), findsWidgets);

      // NUNCA exibe UUID ou dados identificadores do autor
      expect(find.textContaining('my-user-id'), findsNothing);
      expect(find.textContaining('@meu_handle_secreto'), findsNothing);
      expect(find.textContaining('@meuhandle'), findsNothing);
    });
  });

  group('Acesso no Perfil de Usuário (UserProfileScreen)', () {
    late FakeAuthRepo authRepo;
    late AuthNotifier authNotifier;
    late FakeUserProfileRepository profileRepo;

    setUp(() {
      authRepo = FakeAuthRepo();
      authNotifier = AuthNotifier(authRepository: authRepo);
      profileRepo = FakeUserProfileRepository();
    });

    testWidgets('ação "Minhas avaliações" é exibida quando _isMyProfile == true e navega para rota', (tester) async {
      await authNotifier.checkAuthStatus();

      String? pushedRoute;
      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.lightTheme,
          onGenerateRoute: (settings) {
            if (settings.name == AppRouter.myReviews) {
              pushedRoute = settings.name;
              return MaterialPageRoute(
                builder: (context) => const Scaffold(body: Text('Tela Minhas Avaliações')),
              );
            }
            return MaterialPageRoute(
              builder: (context) => UserProfileScreen(
                userId: 'my-user-id',
                userProfileRepository: profileRepo,
                authNotifier: authNotifier,
              ),
            );
          },
        ),
      );

      await tester.pumpAndSettle();

      // Ação está visível no meu perfil
      expect(find.byKey(const Key('my_reviews_button')), findsOneWidget);
      expect(find.text('Minhas avaliações'), findsOneWidget);

      // Clicar no botão navega para AppRouter.myReviews
      await tester.tap(find.byKey(const Key('my_reviews_button')));
      await tester.pumpAndSettle();

      expect(pushedRoute, AppRouter.myReviews);
      expect(find.text('Tela Minhas Avaliações'), findsOneWidget);
    });

    testWidgets('ação "Minhas avaliações" NÃO é exibida no perfil de terceiros', (tester) async {
      await authNotifier.checkAuthStatus();

      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.lightTheme,
          home: UserProfileScreen(
            userId: 'other-user-999',
            userProfileRepository: profileRepo,
            authNotifier: authNotifier,
          ),
        ),
      );

      await tester.pumpAndSettle();

      // Botão NÃO deve existir no perfil de outro usuário
      expect(find.byKey(const Key('my_reviews_button')), findsNothing);
      expect(find.text('Minhas avaliações'), findsNothing);
    });
  });
}
