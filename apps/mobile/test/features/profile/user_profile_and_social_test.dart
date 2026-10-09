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
import 'package:rewit_mobile/features/discussions/domain/entities/discussion_entities.dart';
import 'package:rewit_mobile/features/discussions/presentation/widgets/discussion_item_widget.dart';
import 'package:rewit_mobile/features/feed/domain/entities/feed_entities.dart';
import 'package:rewit_mobile/features/feed/domain/repositories/feed_repository.dart';
import 'package:rewit_mobile/features/feed/presentation/widgets/review_card.dart';
import 'package:rewit_mobile/features/profile/domain/entities/user_reviews_page.dart';
import 'package:rewit_mobile/features/profile/data/models/follow_user_summary_dto.dart';
import 'package:rewit_mobile/features/review_detail/domain/entities/update_review_input.dart';
import 'package:rewit_mobile/features/profile/data/models/user_profile_dto.dart';
import 'package:rewit_mobile/features/profile/data/repositories/user_profile_repository_impl.dart';
import 'package:rewit_mobile/features/profile/domain/entities/follow_user_summary.dart';
import 'package:rewit_mobile/features/profile/domain/entities/update_profile_input.dart';
import 'package:rewit_mobile/features/profile/domain/entities/user_profile.dart';
import 'package:rewit_mobile/features/profile/domain/repositories/user_profile_repository.dart';
import 'package:rewit_mobile/features/profile/presentation/screens/edit_profile_screen.dart';
import 'package:rewit_mobile/features/profile/presentation/screens/follow_list_screen.dart';
import 'package:rewit_mobile/features/profile/presentation/screens/user_profile_screen.dart';
import 'package:rewit_mobile/features/review_detail/domain/entities/helpful_result.dart';
import 'package:rewit_mobile/features/review_detail/domain/entities/review_media.dart';
import 'package:rewit_mobile/features/review_detail/presentation/screens/review_detail_screen.dart';

// ---------------------------------------------------------------------------
// Mocks e Utilitários de Teste
// ---------------------------------------------------------------------------

class MockHttpClient extends http.BaseClient {
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
        http.Response('{}', 200, headers: {'content-type': 'application/json'});
    return http.StreamedResponse(
      Stream.value(resp.bodyBytes),
      resp.statusCode,
      headers: resp.headers,
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

class FakeUserProfileRepository implements UserProfileRepository {
  UserProfile? profileToReturn;
  bool shouldThrow404 = false;
  bool shouldThrow500 = false;
  int followCallCount = 0;
  int unfollowCallCount = 0;
  String? lastFollowTargetId;
  String? lastUnfollowTargetId;

  PagedFollowUsers? followersToReturn;
  PagedFollowUsers? followingToReturn;
  bool shouldThrowListError = false;

  @override
  Future<UserProfile> getUserProfile(String userId) async {
    if (shouldThrow404) {
      throw const ApiException(
        ProblemDetail(
          type: 'https://api.rewit.app/errors/not-found',
          title: 'Não Encontrado',
          status: 404,
          detail: 'Usuário não encontrado ou inativo.',
        ),
      );
    }
    if (shouldThrow500) {
      throw const ApiException(
        ProblemDetail(
          type: 'https://api.rewit.app/errors/internal-error',
          title: 'Erro do Servidor',
          status: 500,
          detail: 'Falha interna ao carregar perfil.',
        ),
      );
    }
    return profileToReturn ??
        UserProfile(
          id: userId,
          handle: 'otheruser',
          displayName: 'Outro Usuário',
          bio: 'Entusiasta de gastronomia.',
          isFollowing: false,
          stats: const UserStats(
            totalReviews: 12,
            followersCount: 34,
            followingCount: 56,
            helpfulVotesReceived: 78,
          ),
        );
  }

  UpdateProfileInput? lastUpdateInput;
  UserProfile? profileToReturnOnUpdate;
  bool shouldThrowUpdateError = false;
  ApiException? updateApiExceptionToThrow;

  @override
  Future<UserProfile> updateMyProfile(UpdateProfileInput input) async {
    lastUpdateInput = input;
    if (updateApiExceptionToThrow != null) {
      throw updateApiExceptionToThrow!;
    }
    if (shouldThrowUpdateError) {
      throw const ApiException(
        ProblemDetail(
          type: 'https://api.rewit.app/errors/internal-error',
          title: 'Erro',
          status: 500,
          detail: 'Falha ao atualizar perfil.',
        ),
      );
    }
    return profileToReturnOnUpdate ??
        UserProfile(
          id: 'my-user-id',
          handle: input.handle,
          displayName: input.displayName,
          bio: input.bio,
          isAnonymousDefault: input.isAnonymousDefault,
          stats: const UserStats(),
        );
  }

  @override
  Future<bool> followUser(String userId) async {
    followCallCount++;
    lastFollowTargetId = userId;
    return true;
  }

  @override
  Future<bool> unfollowUser(String userId) async {
    unfollowCallCount++;
    lastUnfollowTargetId = userId;
    return false;
  }

  @override
  Future<PagedFollowUsers> getFollowers(String userId, {int page = 0, int size = 10}) async {
    if (shouldThrowListError) {
      throw const ApiException(
        ProblemDetail(
          type: 'https://api.rewit.app/errors/internal-error',
          title: 'Erro',
          status: 500,
          detail: 'Erro ao carregar seguidores.',
        ),
      );
    }
    return followersToReturn ??
        const PagedFollowUsers(
          pageNumber: 0,
          pageSize: 10,
          totalElements: 1,
          totalPages: 1,
          isLast: true,
          items: [
            FollowUserSummary(
              id: 'follower-1',
              handle: 'seguidor1',
              displayName: 'Seguidor Um',
            ),
          ],
        );
  }

  @override
  Future<PagedFollowUsers> getFollowing(String userId, {int page = 0, int size = 10}) async {
    if (shouldThrowListError) {
      throw const ApiException(
        ProblemDetail(
          type: 'https://api.rewit.app/errors/internal-error',
          title: 'Erro',
          status: 500,
          detail: 'Erro ao carregar lista de quem segue.',
        ),
      );
    }
    return followingToReturn ??
        const PagedFollowUsers(
          pageNumber: 0,
          pageSize: 10,
          totalElements: 1,
          totalPages: 1,
          isLast: true,
          items: [
            FollowUserSummary(
              id: 'following-1',
              handle: 'seguindo1',
              displayName: 'Seguindo Um',
            ),
          ],
        );
  }

  UserReviewsPage? myReviewsToReturn;
  bool shouldThrowMyReviewsError = false;

  @override
  Future<UserReviewsPage> getMyReviews({int page = 0, int size = 10}) async {
    if (shouldThrowMyReviewsError) {
      throw const ApiException(
        ProblemDetail(
          type: 'https://api.rewit.app/errors/internal-error',
          title: 'Erro',
          status: 500,
          detail: 'Erro ao carregar minhas avaliações.',
        ),
      );
    }
    return myReviewsToReturn ??
        const UserReviewsPage(
          pageNumber: 0,
          pageSize: 10,
          totalElements: 0,
          totalPages: 0,
          isLast: true,
          reviews: [],
        );
  }
}

class FakeFeedRepoForSocial implements FeedRepository {
  final FeedReview review;

  FakeFeedRepoForSocial(this.review);

  @override
  Future<FeedPage> getFeed({int page = 0, int size = 10}) async =>
      FeedPage(items: [review], page: 0, size: 10, windowSize: 1, totalPages: 1);

  @override
  Future<FeedReview> getReviewById(String reviewId) async => review;

  @override
  Future<HelpfulResult> toggleHelpful(String reviewId, {required bool currentlyHelpful}) async =>
      HelpfulResult(helpful: !currentlyHelpful, helpfulCount: 1);

  @override
  Future<List<ReviewMediaItem>> getReviewMedia(String reviewId) async => [];

  @override
  Future<FeedReview> updateReview(String reviewId, UpdateReviewInput input) async => review;

  @override
  Future<void> deleteReview(String reviewId) async {}
}


// ---------------------------------------------------------------------------
// TESTES
// ---------------------------------------------------------------------------

void main() {
  group('1. DTOs & Entidades de Perfil e Grafo Social', () {
    test('UserProfileDto faz parsing correto de JSON completo e mapeia para entidade', () {
      final json = {
        'id': 'usr-123',
        'handle': 'alicedias',
        'displayName': 'Alice Dias',
        'bio': 'Avaliadora de cafeterias',
        'avatarUrl': 'https://rewit.app/avatar.jpg',
        'isFollowing': true,
        'stats': {
          'totalReviews': 15,
          'verifiedReviewsCount': 10,
          'followersCount': 42,
          'followingCount': 28,
          'helpfulVotesReceived': 99,
        },
      };

      final dto = UserProfileDto.fromJson(json);
      final entity = dto.toEntity();

      expect(entity.id, 'usr-123');
      expect(entity.handle, 'alicedias');
      expect(entity.displayName, 'Alice Dias');
      expect(entity.bio, 'Avaliadora de cafeterias');
      expect(entity.avatarUrl, 'https://rewit.app/avatar.jpg');
      expect(entity.isFollowing, isTrue);
      expect(entity.stats.totalReviews, 15);
      expect(entity.stats.verifiedReviewsCount, 10);
      expect(entity.stats.followersCount, 42);
      expect(entity.stats.followingCount, 28);
      expect(entity.stats.helpfulVotesReceived, 99);
    });

    test('UserProfileDto trata campos nulos com fallbacks seguros', () {
      final json = {
        'id': 'usr-456',
        'handle': 'bob',
        'displayName': 'Bob',
      };

      final dto = UserProfileDto.fromJson(json);
      final entity = dto.toEntity();

      expect(entity.id, 'usr-456');
      expect(entity.bio, isNull);
      expect(entity.avatarUrl, isNull);
      expect(entity.isFollowing, isFalse);
      expect(entity.stats.totalReviews, 0);
      expect(entity.stats.followersCount, 0);
      expect(entity.stats.followingCount, 0);
      expect(entity.stats.helpfulVotesReceived, 0);
    });

    test('FollowUserSummaryDto e PagedFollowUsersDto fazem parsing correto', () {
      final json = {
        'content': [
          {
            'id': 'f-1',
            'handle': 'user1',
            'displayName': 'User One',
            'avatarUrl': null,
          },
          {
            'id': 'f-2',
            'handle': 'user2',
            'displayName': 'User Two',
            'avatarUrl': 'https://rewit.app/u2.png',
          },
        ],
        'pageNumber': 0,
        'pageSize': 10,
        'totalElements': 2,
        'totalPages': 1,
        'isLast': true,
      };

      final pagedDto = PagedFollowUsersDto.fromJson(json);
      final paged = pagedDto.toEntity();

      expect(paged.items.length, 2);
      expect(paged.items[0].id, 'f-1');
      expect(paged.items[0].handle, 'user1');
      expect(paged.items[0].displayName, 'User One');
      expect(paged.items[1].avatarUrl, 'https://rewit.app/u2.png');
      expect(paged.pageNumber, 0);
      expect(paged.pageSize, 10);
      expect(paged.totalElements, 2);
      expect(paged.totalPages, 1);
      expect(paged.isLast, isTrue);
    });
  });

  group('2. UserProfileRepositoryImpl HTTP Integration Tests', () {
    late MockHttpClient mockHttp;
    late RewitHttpClient httpClient;
    late UserProfileRepositoryImpl repository;

    setUp(() {
      mockHttp = MockHttpClient();
      httpClient = RewitHttpClient(
        baseUrl: 'https://api.rewit.app',
        tokenStorage: InMemoryTokenStorage(),
        client: mockHttp,
      );
      repository = UserProfileRepositoryImpl(httpClient: httpClient);
    });

    test('getUserProfile realiza GET /api/v1/users/{id} e retorna perfil', () async {
      mockHttp.responseToReturn = http.Response(
        jsonEncode({
          'id': 'user-target-1',
          'handle': 'target',
          'displayName': 'Target User',
          'isFollowing': false,
          'stats': {
            'totalReviews': 3,
            'followersCount': 10,
            'followingCount': 5,
            'helpfulVotesReceived': 8,
          },
        }),
        200,
        headers: {'content-type': 'application/json'},
      );

      final profile = await repository.getUserProfile('user-target-1');

      expect(mockHttp.capturedRequest?.method, 'GET');
      expect(mockHttp.capturedRequest?.url.path, '/api/v1/users/user-target-1');
      expect(profile.id, 'user-target-1');
      expect(profile.displayName, 'Target User');
      expect(profile.stats.followersCount, 10);
    });

    test('followUser realiza POST /api/v1/users/{id}/follow', () async {
      mockHttp.responseToReturn = http.Response(
        jsonEncode({'following': true}),
        200,
        headers: {'content-type': 'application/json'},
      );

      final result = await repository.followUser('user-target-1');

      expect(mockHttp.capturedRequest?.method, 'POST');
      expect(mockHttp.capturedRequest?.url.path, '/api/v1/users/user-target-1/follow');
      expect(result, isTrue);
    });

    test('unfollowUser realiza DELETE /api/v1/users/{id}/follow', () async {
      mockHttp.responseToReturn = http.Response(
        jsonEncode({'following': false}),
        200,
        headers: {'content-type': 'application/json'},
      );

      final result = await repository.unfollowUser('user-target-1');

      expect(mockHttp.capturedRequest?.method, 'DELETE');
      expect(mockHttp.capturedRequest?.url.path, '/api/v1/users/user-target-1/follow');
      expect(result, isFalse);
    });

    test('getFollowers realiza GET com paginação e retorna PagedFollowUsers', () async {
      mockHttp.responseToReturn = http.Response(
        jsonEncode({
          'content': [
            {'id': 'f-1', 'handle': 'fan1', 'displayName': 'Fan One'}
          ],
          'pageNumber': 0,
          'pageSize': 10,
          'totalElements': 1,
          'totalPages': 1,
          'isLast': true,
        }),
        200,
        headers: {'content-type': 'application/json'},
      );

      final paged = await repository.getFollowers('user-target-1', page: 0, size: 10);

      expect(mockHttp.capturedRequest?.method, 'GET');
      expect(mockHttp.capturedRequest?.url.path, '/api/v1/users/user-target-1/followers');
      expect(paged.items.length, 1);
      expect(paged.items.first.displayName, 'Fan One');
    });

    test('getFollowing realiza GET com paginação e retorna PagedFollowUsers', () async {
      mockHttp.responseToReturn = http.Response(
        jsonEncode({
          'content': [
            {'id': 'ing-1', 'handle': 'hero1', 'displayName': 'Hero One'}
          ],
          'pageNumber': 1,
          'pageSize': 15,
          'totalElements': 20,
          'totalPages': 2,
          'isLast': false,
        }),
        200,
        headers: {'content-type': 'application/json'},
      );

      final paged = await repository.getFollowing('user-target-1', page: 1, size: 15);

      expect(mockHttp.capturedRequest?.method, 'GET');
      expect(mockHttp.capturedRequest?.url.path, '/api/v1/users/user-target-1/following');
      expect(paged.items.first.displayName, 'Hero One');
    });

    test('404 lança ApiException com isNotFound == true', () async {
      mockHttp.responseToReturn = http.Response(
        jsonEncode({
          'type': 'https://api.rewit.app/errors/not-found',
          'title': 'Usuário Não Encontrado',
          'status': 404,
          'detail': 'Usuário inexistente.',
        }),
        404,
        headers: {'content-type': 'application/json'},
      );

      expect(
        () => repository.getUserProfile('unknown-id'),
        throwsA(isA<ApiException>().having((e) => e.isNotFound, 'isNotFound', isTrue)),
      );
    });

    test('updateMyProfile realiza PATCH /api/v1/me/profile e deserializa sucesso', () async {
      mockHttp.responseToReturn = http.Response(
        jsonEncode({
          'id': 'my-user-id',
          'email': 'eu@rewit.app',
          'handle': 'novohandle',
          'displayName': 'Novo Nome',
          'bio': 'Nova biografia culinária',
          'avatarUrl': 'https://rewit.app/avatar.png',
          'isVerified': true,
          'isAnonymousDefault': true,
          'reputationScore': 120,
          'createdAt': '2026-10-08T10:00:00Z',
        }),
        200,
        headers: {'content-type': 'application/json'},
      );

      const input = UpdateProfileInput(
        handle: 'novohandle',
        displayName: 'Novo Nome',
        bio: 'Nova biografia culinária',
        isAnonymousDefault: true,
      );

      final result = await repository.updateMyProfile(input);

      expect(mockHttp.capturedRequest?.method, 'PATCH');
      expect(mockHttp.capturedRequest?.url.path, '/api/v1/me/profile');
      final sentBody = jsonDecode(mockHttp.capturedRequest!.body) as Map<String, dynamic>;
      expect(sentBody['handle'], 'novohandle');
      expect(sentBody['displayName'], 'Novo Nome');
      expect(sentBody['bio'], 'Nova biografia culinária');
      expect(sentBody['isAnonymousDefault'], isTrue);

      expect(result.handle, 'novohandle');
      expect(result.displayName, 'Novo Nome');
      expect(result.bio, 'Nova biografia culinária');
      expect(result.isAnonymousDefault, isTrue);
    });

    test('updateMyProfile lança ApiException em 400 (dados inválidos)', () async {
      mockHttp.responseToReturn = http.Response(
        jsonEncode({
          'type': 'https://api.rewit.app/errors/validation',
          'title': 'Requisição Inválida',
          'status': 400,
          'detail': 'O nome de exibição deve ter entre 2 e 100 caracteres.',
        }),
        400,
        headers: {'content-type': 'application/json'},
      );

      const input = UpdateProfileInput(
        handle: 'h',
        displayName: '',
      );

      expect(
        () => repository.updateMyProfile(input),
        throwsA(isA<ApiException>().having((e) => e.statusCode, 'statusCode', 400)),
      );
    });

    test('updateMyProfile lança ApiException em 409 (conflito de handle)', () async {
      mockHttp.responseToReturn = http.Response(
        jsonEncode({
          'type': 'https://api.rewit.app/errors/conflict',
          'title': 'Conflito',
          'status': 409,
          'detail': 'Este nome de usuário já está em uso.',
        }),
        409,
        headers: {'content-type': 'application/json'},
      );

      const input = UpdateProfileInput(
        handle: 'existing_handle',
        displayName: 'Meu Nome',
      );

      expect(
        () => repository.updateMyProfile(input),
        throwsA(isA<ApiException>().having((e) => e.statusCode, 'statusCode', 409)),
      );
    });

    test('updateMyProfile lança ApiException em 401 (sessão inválida)', () async {
      mockHttp.responseToReturn = http.Response(
        jsonEncode({
          'type': 'https://api.rewit.app/errors/unauthorized',
          'title': 'Não Autorizado',
          'status': 401,
          'detail': 'Sessão expirada.',
        }),
        401,
        headers: {'content-type': 'application/json'},
      );

      const input = UpdateProfileInput(
        handle: 'novohandle',
        displayName: 'Meu Nome',
      );

      expect(
        () => repository.updateMyProfile(input),
        throwsA(isA<ApiException>().having((e) => e.isUnauthorized, 'isUnauthorized', isTrue)),
      );
    });

    test('updateMyProfile propaga NetworkException em falha de conexão', () async {
      mockHttp.exceptionToThrow = http.ClientException('Sem conexão com o servidor');

      const input = UpdateProfileInput(
        handle: 'novohandle',
        displayName: 'Meu Nome',
      );

      expect(
        () => repository.updateMyProfile(input),
        throwsA(isA<NetworkException>()),
      );
    });
  });

  group('3. UserProfileScreen Widget Tests', () {
    late AuthNotifier authNotifier;
    late FakeUserProfileRepository userProfileRepo;

    setUp(() async {
      final authRepo = FakeAuthRepo();
      authNotifier = AuthNotifier(authRepository: authRepo);
      await authNotifier.checkAuthStatus();
      userProfileRepo = FakeUserProfileRepository();
    });

    testWidgets('exibe "Meu Perfil" quando userId é nulo ou igual ao do usuário logado', (tester) async {
      await tester.pumpWidget(MaterialApp(
        theme: AppTheme.lightTheme,
        home: UserProfileScreen(
          userId: null,
          userProfileRepository: userProfileRepo,
          authNotifier: authNotifier,
        ),
      ));
      await tester.pumpAndSettle();

      expect(find.text('Perfil de Usuário'), findsOneWidget);
      expect(find.widgetWithText(OutlinedButton, 'Configurações da Conta'), findsOneWidget);
      expect(find.widgetWithText(ElevatedButton, 'Seguir'), findsNothing);
      expect(find.widgetWithText(OutlinedButton, 'Seguindo'), findsNothing);
    });

    testWidgets('exibe perfil de terceiro com estatísticas e botão Seguir', (tester) async {
      userProfileRepo.profileToReturn = const UserProfile(
        id: 'other-id',
        handle: 'rodrigo',
        displayName: 'Rodrigo Gastrônomo',
        bio: 'Crítico amador de restaurantes em SP.',
        isFollowing: false,
        stats: UserStats(
          followersCount: 15,
          followingCount: 22,
          totalReviews: 8,
          helpfulVotesReceived: 40,
        ),
      );

      await tester.pumpWidget(MaterialApp(
        theme: AppTheme.lightTheme,
        home: UserProfileScreen(
          userId: 'other-id',
          userProfileRepository: userProfileRepo,
          authNotifier: authNotifier,
        ),
      ));
      await tester.pumpAndSettle();

      expect(find.text('Rodrigo Gastrônomo'), findsWidgets);
      expect(find.text('@rodrigo'), findsOneWidget);
      expect(find.text('Crítico amador de restaurantes em SP.'), findsOneWidget);
      expect(find.text('15'), findsOneWidget); // Seguidores
      expect(find.text('22'), findsOneWidget); // Seguindo
      expect(find.text('8'), findsOneWidget); // Avaliações
      expect(find.text('40'), findsOneWidget); // Votos Úteis
      expect(find.widgetWithText(ElevatedButton, 'Seguir'), findsOneWidget);
    });

    testWidgets('interação Seguir chama repositório, altera botão para Seguindo e incrementa contagem', (tester) async {
      userProfileRepo.profileToReturn = const UserProfile(
        id: 'target-to-follow',
        handle: 'clarice',
        displayName: 'Clarice Lispector',
        isFollowing: false,
        stats: UserStats(followersCount: 10),
      );

      await tester.pumpWidget(MaterialApp(
        theme: AppTheme.lightTheme,
        home: UserProfileScreen(
          userId: 'target-to-follow',
          userProfileRepository: userProfileRepo,
          authNotifier: authNotifier,
        ),
      ));
      await tester.pumpAndSettle();

      expect(find.widgetWithText(ElevatedButton, 'Seguir'), findsOneWidget);
      expect(find.text('10'), findsOneWidget);

      await tester.tap(find.widgetWithText(ElevatedButton, 'Seguir'));
      await tester.pumpAndSettle();

      expect(userProfileRepo.followCallCount, 1);
      expect(userProfileRepo.lastFollowTargetId, 'target-to-follow');
      expect(find.widgetWithText(OutlinedButton, 'Seguindo'), findsOneWidget);
      expect(find.text('11'), findsOneWidget);
    });

    testWidgets('interação Deixar de Seguir chama repositório, altera botão para Seguir e decrementa contagem', (tester) async {
      userProfileRepo.profileToReturn = const UserProfile(
        id: 'target-to-unfollow',
        handle: 'clarice',
        displayName: 'Clarice Lispector',
        isFollowing: true,
        stats: UserStats(followersCount: 5),
      );

      await tester.pumpWidget(MaterialApp(
        theme: AppTheme.lightTheme,
        home: UserProfileScreen(
          userId: 'target-to-unfollow',
          userProfileRepository: userProfileRepo,
          authNotifier: authNotifier,
        ),
      ));
      await tester.pumpAndSettle();

      expect(find.widgetWithText(OutlinedButton, 'Seguindo'), findsOneWidget);
      expect(find.text('5'), findsOneWidget);

      await tester.tap(find.widgetWithText(OutlinedButton, 'Seguindo'));
      await tester.pumpAndSettle();

      expect(userProfileRepo.unfollowCallCount, 1);
      expect(userProfileRepo.lastUnfollowTargetId, 'target-to-unfollow');
      expect(find.widgetWithText(ElevatedButton, 'Seguir'), findsOneWidget);
      expect(find.text('4'), findsOneWidget);
    });

    testWidgets('trata 404 exibindo estado de Perfil Indisponível', (tester) async {
      userProfileRepo.shouldThrow404 = true;

      await tester.pumpWidget(MaterialApp(
        theme: AppTheme.lightTheme,
        home: UserProfileScreen(
          userId: 'inactive-or-missing-user',
          userProfileRepository: userProfileRepo,
          authNotifier: authNotifier,
        ),
      ));
      await tester.pumpAndSettle();

      expect(find.text('Perfil Indisponível'), findsOneWidget);
      expect(find.text('Usuário não encontrado ou inativo.'), findsOneWidget);
    });

    testWidgets('trata erro de rede/servidor com botão Tentar novamente', (tester) async {
      userProfileRepo.shouldThrow500 = true;

      await tester.pumpWidget(MaterialApp(
        theme: AppTheme.lightTheme,
        home: UserProfileScreen(
          userId: 'error-user',
          userProfileRepository: userProfileRepo,
          authNotifier: authNotifier,
        ),
      ));
      await tester.pumpAndSettle();

      expect(find.text('Falha interna ao carregar perfil.'), findsOneWidget);
      expect(find.widgetWithText(ElevatedButton, 'Tentar novamente'), findsOneWidget);

      // Corrige estado do repo e clica em tentar novamente
      userProfileRepo.shouldThrow500 = false;
      await tester.tap(find.widgetWithText(ElevatedButton, 'Tentar novamente'));
      await tester.pumpAndSettle();

      expect(find.text('Outro Usuário'), findsWidgets);
    });

    testWidgets('perfil de Usuário excluído não exibe handle nem botões de seguir', (tester) async {
      userProfileRepo.profileToReturn = const UserProfile(
        id: 'deleted-user-id',
        handle: '',
        displayName: 'Usuário excluído',
        isFollowing: false,
        stats: UserStats(),
      );

      await tester.pumpWidget(MaterialApp(
        theme: AppTheme.lightTheme,
        home: UserProfileScreen(
          userId: 'deleted-user-id',
          userProfileRepository: userProfileRepo,
          authNotifier: authNotifier,
        ),
      ));
      await tester.pumpAndSettle();

      expect(find.text('Usuário excluído'), findsWidgets);
      expect(find.widgetWithText(ElevatedButton, 'Seguir'), findsNothing);
      expect(find.widgetWithText(OutlinedButton, 'Seguindo'), findsNothing);
    });

    testWidgets('botão Editar Perfil aparece apenas no próprio perfil e abre EditProfileScreen', (tester) async {
      await tester.pumpWidget(MaterialApp(
        theme: AppTheme.lightTheme,
        home: UserProfileScreen(
          userId: null,
          userProfileRepository: userProfileRepo,
          authNotifier: authNotifier,
        ),
      ));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('edit_profile_button')), findsOneWidget);
      expect(find.text('Editar Perfil'), findsOneWidget);

      await tester.tap(find.byKey(const Key('edit_profile_button')));
      await tester.pumpAndSettle();

      expect(find.byType(EditProfileScreen), findsOneWidget);
      expect(find.text('Salvar Alterações'), findsOneWidget);
    });

    testWidgets('botão Editar Perfil NÃO aparece para perfil de terceiro', (tester) async {
      await tester.pumpWidget(MaterialApp(
        theme: AppTheme.lightTheme,
        home: UserProfileScreen(
          userId: 'other-user-id',
          userProfileRepository: userProfileRepo,
          authNotifier: authNotifier,
        ),
      ));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('edit_profile_button')), findsNothing);
      expect(find.widgetWithText(ElevatedButton, 'Seguir'), findsOneWidget);
    });

    testWidgets('retorno da edição atualiza imediatamente os dados exibidos no perfil', (tester) async {
      await tester.pumpWidget(MaterialApp(
        theme: AppTheme.lightTheme,
        home: UserProfileScreen(
          userId: null,
          userProfileRepository: userProfileRepo,
          authNotifier: authNotifier,
        ),
      ));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('edit_profile_button')));
      await tester.pumpAndSettle();

      await tester.enterText(find.byKey(const Key('edit_profile_display_name_field')), 'Chef Renomado');
      await tester.enterText(find.byKey(const Key('edit_profile_handle_field')), 'chef_renomado');
      await tester.enterText(find.byKey(const Key('edit_profile_bio_field')), 'Bio atualizada com sucesso');
      await tester.tap(find.byKey(const Key('edit_profile_anonymous_switch')));
      await tester.pump();

      await tester.tap(find.byKey(const Key('edit_profile_submit_button')));
      await tester.pumpAndSettle();

      expect(find.byType(EditProfileScreen), findsNothing);
      expect(find.text('Chef Renomado'), findsWidgets);
      expect(find.text('@chef_renomado'), findsOneWidget);
      expect(find.text('Bio atualizada com sucesso'), findsOneWidget);
      expect(find.text('Avaliações anônimas por padrão ativado'), findsOneWidget);
    });
  });

  group('4. FollowListScreen Widget Tests', () {
    late FakeUserProfileRepository userProfileRepo;

    setUp(() {
      userProfileRepo = FakeUserProfileRepository();
    });

    testWidgets('renderiza abas Seguidores e Seguindo e exibe usuários', (tester) async {
      await tester.pumpWidget(MaterialApp(
        theme: AppTheme.lightTheme,
        home: FollowListScreen(
          userId: 'test-user',
          userName: 'Ana Costa',
          initialTab: FollowListTab.followers,
          repository: userProfileRepo,
        ),
      ));
      await tester.pumpAndSettle();

      expect(find.text('Ana Costa'), findsOneWidget);
      expect(find.text('Seguidores'), findsOneWidget);
      expect(find.text('Seguindo'), findsOneWidget);
      expect(find.text('Seguidor Um'), findsOneWidget);
      expect(find.text('@seguidor1'), findsOneWidget);

      // Alterna para aba Seguindo
      await tester.tap(find.text('Seguindo'));
      await tester.pumpAndSettle();

      expect(find.text('Seguindo Um'), findsOneWidget);
      expect(find.text('@seguindo1'), findsOneWidget);
    });

    testWidgets('exibe estado vazio quando não há conexões', (tester) async {
      userProfileRepo.followersToReturn = const PagedFollowUsers(
        pageNumber: 0,
        pageSize: 10,
        totalElements: 0,
        totalPages: 0,
        isLast: true,
        items: [],
      );

      await tester.pumpWidget(MaterialApp(
        theme: AppTheme.lightTheme,
        home: FollowListScreen(
          userId: 'lonely-user',
          userName: 'Lonely',
          initialTab: FollowListTab.followers,
          repository: userProfileRepo,
        ),
      ));
      await tester.pumpAndSettle();

      expect(find.text('Nenhum seguidor ainda'), findsOneWidget);
    });

    testWidgets('exibe erro e botão de tentar novamente em caso de falha', (tester) async {
      userProfileRepo.shouldThrowListError = true;

      await tester.pumpWidget(MaterialApp(
        theme: AppTheme.lightTheme,
        home: FollowListScreen(
          userId: 'error-user',
          userName: 'Error',
          initialTab: FollowListTab.followers,
          repository: userProfileRepo,
        ),
      ));
      await tester.pumpAndSettle();

      expect(find.text('Erro ao carregar seguidores.'), findsOneWidget);
      expect(find.widgetWithText(ElevatedButton, 'Tentar novamente'), findsOneWidget);
    });
  });

  group('5. Navegação a partir de Autores (Feed, Review Detail e Discussões)', () {
    testWidgets('ReviewCard permite toque no autor para navegar quando não é anônimo nem excluído', (tester) async {
      String? tappedAuthorId;

      final review = FeedReview(
        id: 'rev-1',
        author: const FeedAuthor(
          id: 'author-abc',
          handle: 'chefalice',
          displayName: 'Alice Gourmet',
          isAnonymous: false,
        ),
        experienceText: 'Excelente prato e sobremesa.',
        visibility: 'PUBLIC',
        status: 'ACTIVE',
        isAnonymous: false,
        isVerifiedOnSite: false,
        helpfulCount: 2,
        isHelpfulByMe: false,
        targets: [],
        createdAt: DateTime.now(),
      );

      await tester.pumpWidget(MaterialApp(
        home: Scaffold(
          body: ReviewCard(
            review: review,
            onAuthorTap: (id) => tappedAuthorId = id,
          ),
        ),
      ));

      expect(find.text('Alice Gourmet'), findsOneWidget);
      await tester.tap(find.text('Alice Gourmet'));
      await tester.pumpAndSettle();

      expect(tappedAuthorId, 'author-abc');
    });

    testWidgets('ReviewCard NÃO dispara navegação quando autor é anônimo', (tester) async {
      String? tappedAuthorId;

      final review = FeedReview(
        id: 'rev-anon',
        author: const FeedAuthor(
          id: null,
          handle: null,
          displayName: 'Anônimo',
          isAnonymous: true,
        ),
        experienceText: 'Avaliação anônima.',
        visibility: 'PUBLIC',
        status: 'ACTIVE',
        isAnonymous: true,
        isVerifiedOnSite: false,
        helpfulCount: 0,
        isHelpfulByMe: false,
        targets: [],
        createdAt: DateTime.now(),
      );

      await tester.pumpWidget(MaterialApp(
        home: Scaffold(
          body: ReviewCard(
            review: review,
            onAuthorTap: (id) => tappedAuthorId = id,
          ),
        ),
      ));

      await tester.tap(find.text('Anônimo').first);
      await tester.pumpAndSettle();

      expect(tappedAuthorId, isNull);
    });

    testWidgets('ReviewCard NÃO dispara navegação quando autor é "Usuário excluído"', (tester) async {
      String? tappedAuthorId;

      final review = FeedReview(
        id: 'rev-del',
        author: const FeedAuthor(
          id: 'del-id',
          handle: null,
          displayName: 'Usuário excluído',
          isAnonymous: false,
        ),
        experienceText: 'Avaliação de conta excluída.',
        visibility: 'PUBLIC',
        status: 'ACTIVE',
        isAnonymous: false,
        isVerifiedOnSite: false,
        helpfulCount: 0,
        isHelpfulByMe: false,
        targets: [],
        createdAt: DateTime.now(),
      );

      await tester.pumpWidget(MaterialApp(
        home: Scaffold(
          body: ReviewCard(
            review: review,
            onAuthorTap: (id) => tappedAuthorId = id,
          ),
        ),
      ));

      await tester.tap(find.text('Usuário excluído'));
      await tester.pumpAndSettle();

      expect(tappedAuthorId, isNull);
    });

    testWidgets('DiscussionItemWidget dispara onAuthorTap quando autor é válido', (tester) async {
      String? tappedAuthorId;

      final item = DiscussionItem(
        id: 'disc-1',
        reviewId: 'rev-1',
        state: DiscussionViewState.visible,
        content: 'Concordo plenamente com a avaliação!',
        author: const DiscussionAuthor(
          id: 'commenter-99',
          handle: 'comentarista',
          displayName: 'Comentarista 99',
        ),
        isFromOwner: false,
        canReply: true,
        canDelete: false,
        createdAt: DateTime.now(),
      );

      await tester.pumpWidget(MaterialApp(
        home: Scaffold(
          body: DiscussionItemWidget(
            item: item,
            onAuthorTap: (id) => tappedAuthorId = id,
          ),
        ),
      ));

      expect(find.text('Comentarista 99'), findsOneWidget);
      await tester.tap(find.text('Comentarista 99'));
      await tester.pumpAndSettle();

      expect(tappedAuthorId, 'commenter-99');
    });

    testWidgets('DiscussionItemWidget NÃO dispara onAuthorTap para "Usuário excluído"', (tester) async {
      String? tappedAuthorId;

      final item = DiscussionItem(
        id: 'disc-del',
        reviewId: 'rev-1',
        state: DiscussionViewState.visible,
        content: 'Comentário antigo.',
        author: const DiscussionAuthor(
          id: 'del-commenter',
          handle: '',
          displayName: 'Usuário excluído',
        ),
        isFromOwner: false,
        canReply: false,
        canDelete: false,
        createdAt: DateTime.now(),
      );

      await tester.pumpWidget(MaterialApp(
        home: Scaffold(
          body: DiscussionItemWidget(
            item: item,
            onAuthorTap: (id) => tappedAuthorId = id,
          ),
        ),
      ));

      await tester.tap(find.text('Usuário excluído'));
      await tester.pumpAndSettle();

      expect(tappedAuthorId, isNull);
    });

    testWidgets('ReviewDetailScreen navega para perfil do autor ao tocar no cabeçalho do autor', (tester) async {
      String? navigatedProfileUserId;

      final review = FeedReview(
        id: 'rev-detail-1',
        author: const FeedAuthor(
          id: 'author-detail-99',
          handle: 'masterchef',
          displayName: 'Master Chef',
          isAnonymous: false,
        ),
        experienceText: 'Ambiente sensacional.',
        visibility: 'PUBLIC',
        status: 'ACTIVE',
        isAnonymous: false,
        isVerifiedOnSite: true,
        helpfulCount: 5,
        isHelpfulByMe: false,
        targets: [],
        createdAt: DateTime.now(),
      );

      final feedRepo = FakeFeedRepoForSocial(review);

      await tester.pumpWidget(MaterialApp(
        onGenerateRoute: (settings) {
          if (settings.name == AppRouter.profile) {
            navigatedProfileUserId = settings.arguments as String?;
            return MaterialPageRoute(builder: (_) => const Scaffold(body: Text('Perfil Screen Mock')));
          }
          return null;
        },
        home: ReviewDetailScreen(
          reviewId: 'rev-detail-1',
          initialReview: review,
          feedRepository: feedRepo,
        ),
      ));
      await tester.pumpAndSettle();

      expect(find.text('Master Chef'), findsOneWidget);
      await tester.tap(find.text('Master Chef'));
      await tester.pumpAndSettle();

      expect(navigatedProfileUserId, 'author-detail-99');
    });

    testWidgets('ReviewDetailScreen NÃO navega para perfil quando autor é anônimo', (tester) async {
      String? navigatedProfileUserId;

      final review = FeedReview(
        id: 'rev-detail-anon',
        author: const FeedAuthor(
          id: null,
          handle: null,
          displayName: 'Anônimo',
          isAnonymous: true,
        ),
        experienceText: 'Avaliação anônima.',
        visibility: 'PUBLIC',
        status: 'ACTIVE',
        isAnonymous: true,
        isVerifiedOnSite: false,
        helpfulCount: 0,
        isHelpfulByMe: false,
        targets: [],
        createdAt: DateTime.now(),
      );

      final feedRepo = FakeFeedRepoForSocial(review);

      await tester.pumpWidget(MaterialApp(
        onGenerateRoute: (settings) {
          if (settings.name == AppRouter.profile) {
            navigatedProfileUserId = settings.arguments as String?;
            return MaterialPageRoute(builder: (_) => const Scaffold(body: Text('Perfil Screen Mock')));
          }
          return null;
        },
        home: ReviewDetailScreen(
          reviewId: 'rev-detail-anon',
          initialReview: review,
          feedRepository: feedRepo,
        ),
      ));
      await tester.pumpAndSettle();

      await tester.tap(find.text('Anônimo').first);
      await tester.pumpAndSettle();

      expect(navigatedProfileUserId, isNull);
    });
  });
}
