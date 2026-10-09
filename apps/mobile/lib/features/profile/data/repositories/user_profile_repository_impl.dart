import 'dart:convert';
import 'package:rewit_mobile/core/network/api_endpoints.dart';
import 'package:rewit_mobile/core/network/http_client.dart';
import 'package:rewit_mobile/features/profile/data/models/follow_user_summary_dto.dart';
import 'package:rewit_mobile/features/profile/data/models/user_profile_dto.dart';
import 'package:rewit_mobile/features/profile/data/models/user_reviews_page_dto.dart';
import 'package:rewit_mobile/features/profile/domain/entities/follow_user_summary.dart';
import 'package:rewit_mobile/features/profile/domain/entities/update_profile_input.dart';
import 'package:rewit_mobile/features/profile/domain/entities/user_profile.dart';
import 'package:rewit_mobile/features/profile/domain/entities/user_reviews_page.dart';
import 'package:rewit_mobile/features/profile/domain/repositories/user_profile_repository.dart';

/// Implementação do repositório de perfil público e grafo social consumindo a API REST.
class UserProfileRepositoryImpl implements UserProfileRepository {
  final RewitHttpClient httpClient;

  UserProfileRepositoryImpl({required this.httpClient});

  @override
  Future<UserProfile> getUserProfile(String userId) async {
    final response = await httpClient.get(ApiEndpoints.userProfile(userId));
    final json = jsonDecode(response.body) as Map<String, dynamic>;
    final dto = UserProfileDto.fromJson(json);
    return dto.toEntity();
  }

  @override
  Future<UserProfile> updateMyProfile(UpdateProfileInput input) async {
    final response = await httpClient.patch(
      ApiEndpoints.meProfile,
      body: input.toJson(),
    );
    final json = jsonDecode(response.body) as Map<String, dynamic>;
    final dto = UserProfileDto.fromJson(json);
    return dto.toEntity();
  }

  @override
  Future<bool> followUser(String userId) async {
    final response = await httpClient.post(ApiEndpoints.userFollow(userId));
    final json = jsonDecode(response.body) as Map<String, dynamic>;
    return json['following'] as bool? ?? true;
  }

  @override
  Future<bool> unfollowUser(String userId) async {
    final response = await httpClient.delete(ApiEndpoints.userFollow(userId));
    final json = jsonDecode(response.body) as Map<String, dynamic>;
    return json['following'] as bool? ?? false;
  }

  @override
  Future<PagedFollowUsers> getFollowers(String userId, {int page = 0, int size = 10}) async {
    final response = await httpClient.get(ApiEndpoints.userFollowers(userId, page: page, size: size));
    final json = jsonDecode(response.body) as Map<String, dynamic>;
    final dto = PagedFollowUsersDto.fromJson(json);
    return dto.toEntity();
  }

  @override
  Future<PagedFollowUsers> getFollowing(String userId, {int page = 0, int size = 10}) async {
    final response = await httpClient.get(ApiEndpoints.userFollowing(userId, page: page, size: size));
    final json = jsonDecode(response.body) as Map<String, dynamic>;
    final dto = PagedFollowUsersDto.fromJson(json);
    return dto.toEntity();
  }

  @override
  Future<UserReviewsPage> getMyReviews({int page = 0, int size = 10}) async {
    final response = await httpClient.get(
      ApiEndpoints.myReviewsPath,
      queryParameters: {
        'page': page,
        'size': size,
      },
    );
    final dynamic decoded = jsonDecode(response.body);
    if (decoded is! Map<String, dynamic>) {
      throw const FormatException('Resposta inválida do servidor ao listar minhas avaliações.');
    }
    return UserReviewsPageDto.fromJson(decoded).toEntity();
  }
}
