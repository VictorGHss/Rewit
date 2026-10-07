import 'dart:convert';
import 'package:rewit_mobile/core/network/api_endpoints.dart';
import 'package:rewit_mobile/core/network/http_client.dart';
import 'package:rewit_mobile/features/discussions/data/models/discussion_models.dart';
import 'package:rewit_mobile/features/discussions/domain/entities/discussion_entities.dart';
import 'package:rewit_mobile/features/discussions/domain/repositories/discussion_repository.dart';

/// Implementação do repositório de discussões integrado com a API REST Rewit.
class DiscussionRepositoryImpl implements DiscussionRepository {
  final RewitHttpClient _httpClient;

  DiscussionRepositoryImpl({required RewitHttpClient httpClient})
      : _httpClient = httpClient;

  @override
  Future<DiscussionPage> getDiscussions(String reviewId, {int page = 0, int size = 20}) async {
    final response = await _httpClient.get(
      ApiEndpoints.reviewDiscussions(reviewId, page: page, size: size),
      requiresAuth: true,
    );

    final Map<String, dynamic> jsonMap = jsonDecode(response.body) as Map<String, dynamic>;
    final dto = DiscussionPageDto.fromJson(jsonMap);
    return dto.toEntity();
  }

  @override
  Future<DiscussionRepliesPage> getReplies(String discussionId, {int page = 0, int size = 20}) async {
    final response = await _httpClient.get(
      ApiEndpoints.discussionReplies(discussionId, page: page, size: size),
      requiresAuth: true,
    );

    final Map<String, dynamic> jsonMap = jsonDecode(response.body) as Map<String, dynamic>;
    final dto = DiscussionRepliesPageDto.fromJson(jsonMap);
    return dto.toEntity();
  }

  @override
  Future<DiscussionItem> createDiscussion({
    required String reviewId,
    required String content,
    String? parentId,
  }) async {
    final Map<String, dynamic> payload = {
      'content': content,
      if (parentId != null) 'parentId': parentId,
    };

    final response = await _httpClient.post(
      ApiEndpoints.createDiscussion(reviewId),
      body: payload,
      requiresAuth: true,
    );

    final Map<String, dynamic> jsonMap = jsonDecode(response.body) as Map<String, dynamic>;
    final dto = CreatedDiscussionDto.fromJson(jsonMap);
    return dto.toEntity();
  }

  @override
  Future<void> deleteDiscussion(String discussionId) async {
    await _httpClient.delete(
      ApiEndpoints.discussionDetail(discussionId),
      requiresAuth: true,
    );
  }

  @override
  Future<String> reportDiscussion({
    required String discussionId,
    required ReportReason reason,
    String? detail,
  }) async {
    final Map<String, dynamic> payload = {
      'reason': reason.backendValue,
      if (detail != null && detail.trim().isNotEmpty) 'detail': detail.trim(),
    };

    final response = await _httpClient.post(
      ApiEndpoints.reportDiscussion(discussionId),
      body: payload,
      requiresAuth: true,
    );

    final Map<String, dynamic> jsonMap = jsonDecode(response.body) as Map<String, dynamic>;
    final dto = DiscussionReportReceiptDto.fromJson(jsonMap);
    return dto.message;
  }
}
