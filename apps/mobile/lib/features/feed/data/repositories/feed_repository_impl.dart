import 'dart:convert';
import 'package:rewit_mobile/core/network/api_endpoints.dart';
import 'package:rewit_mobile/core/network/http_client.dart';
import '../../domain/entities/feed_entities.dart';
import '../../domain/repositories/feed_repository.dart';
import '../models/feed_models.dart';

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
}
