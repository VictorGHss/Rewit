import 'dart:convert';
import 'package:rewit_mobile/core/network/api_endpoints.dart';
import 'package:rewit_mobile/core/network/http_client.dart';
import 'package:rewit_mobile/features/feed/data/models/feed_models.dart';
import 'package:rewit_mobile/features/feed/domain/entities/feed_entities.dart';
import 'package:rewit_mobile/features/feed/domain/repositories/feed_repository.dart';
import 'package:rewit_mobile/features/review_detail/data/models/review_media_dto.dart';
import 'package:rewit_mobile/features/review_detail/domain/entities/helpful_result.dart';
import 'package:rewit_mobile/features/review_detail/domain/entities/review_media.dart';
import 'package:rewit_mobile/features/review_detail/domain/entities/update_review_input.dart';

/// Implementação de [FeedRepository] integrando com a API REST do Rewit.
class FeedRepositoryImpl implements FeedRepository {
  final RewitHttpClient _httpClient;

  FeedRepositoryImpl({required RewitHttpClient httpClient})
      : _httpClient = httpClient;

  @override
  Future<FeedPage> getFeed({int page = 0, int size = 10}) async {
    final response = await _httpClient.get(
      ApiEndpoints.feedV2,
      queryParameters: {
        'page': page,
        'size': size,
      },
      requiresAuth: true,
    );

    final Map<String, dynamic> jsonMap = jsonDecode(response.body) as Map<String, dynamic>;
    final dto = FeedPageDto.fromJson(jsonMap);
    return dto.toEntity();
  }

  @override
  Future<FeedReview> getReviewById(String reviewId) async {
    final response = await _httpClient.get(
      ApiEndpoints.reviewDetail(reviewId),
      requiresAuth: true,
    );

    final Map<String, dynamic> jsonMap = jsonDecode(response.body) as Map<String, dynamic>;
    final dto = FeedReviewDto.fromJson(jsonMap);
    return dto.toEntity();
  }

  @override
  Future<HelpfulResult> toggleHelpful(String reviewId, {required bool currentlyHelpful}) async {
    final response = currentlyHelpful
        ? await _httpClient.delete(
            ApiEndpoints.reviewHelpful(reviewId),
            requiresAuth: true,
          )
        : await _httpClient.post(
            ApiEndpoints.reviewHelpful(reviewId),
            requiresAuth: true,
          );

    final Map<String, dynamic> jsonMap = jsonDecode(response.body) as Map<String, dynamic>;
    return HelpfulResult(
      helpful: jsonMap['helpful'] as bool? ?? !currentlyHelpful,
      helpfulCount: (jsonMap['helpfulCount'] as num?)?.toInt() ?? 0,
    );
  }

  @override
  Future<List<ReviewMediaItem>> getReviewMedia(String reviewId) async {
    final response = await _httpClient.get(
      ApiEndpoints.reviewMedia(reviewId),
      requiresAuth: true,
    );

    final List<dynamic> jsonList = jsonDecode(response.body) as List<dynamic>;
    return jsonList
        .map((item) => ReviewMediaDto.fromJson(item as Map<String, dynamic>).toEntity())
        .toList();
  }

  @override
  Future<FeedReview> updateReview(String reviewId, UpdateReviewInput input) async {
    final response = await _httpClient.patch(
      ApiEndpoints.reviewDetail(reviewId),
      body: input.toJson(),
      requiresAuth: true,
    );

    final Map<String, dynamic> jsonMap = jsonDecode(response.body) as Map<String, dynamic>;
    final dto = FeedReviewDto.fromJson(jsonMap);
    return dto.toEntity();
  }

  @override
  Future<void> deleteReview(String reviewId) async {
    await _httpClient.delete(
      ApiEndpoints.reviewDetail(reviewId),
      requiresAuth: true,
    );
  }
}
