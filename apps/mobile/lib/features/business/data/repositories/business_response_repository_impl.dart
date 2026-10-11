import 'dart:convert';
import 'package:rewit_mobile/core/network/api_endpoints.dart';
import 'package:rewit_mobile/core/network/http_client.dart';
import '../../domain/entities/business_response_entities.dart';
import '../../domain/repositories/business_response_repository.dart';
import '../models/business_response_dtos.dart';

/// Implementação do repositório de respostas oficiais integrado com a API REST Rewit.
class BusinessResponseRepositoryImpl implements BusinessResponseRepository {
  final RewitHttpClient _httpClient;

  BusinessResponseRepositoryImpl({required RewitHttpClient httpClient})
      : _httpClient = httpClient;

  @override
  Future<BusinessResponsePage> getReviewBusinessResponses(
    String reviewId, {
    int page = 0,
    int size = 20,
  }) async {
    final response = await _httpClient.get(
      ApiEndpoints.reviewBusinessResponses(reviewId, page: page, size: size),
      requiresAuth: true,
    );

    final dynamic decoded = jsonDecode(utf8.decode(response.bodyBytes));
    if (decoded is! Map<String, dynamic>) {
      throw const FormatException('Resposta inválida do servidor ao listar respostas oficiais.');
    }
    return BusinessResponsePageDto.fromJson(decoded).toEntity();
  }

  @override
  Future<BusinessResponseEligibility> getReviewEligibility(String reviewId) async {
    final response = await _httpClient.get(
      ApiEndpoints.reviewBusinessResponsesEligibility(reviewId),
      requiresAuth: true,
    );

    final dynamic decoded = jsonDecode(utf8.decode(response.bodyBytes));
    if (decoded is! Map<String, dynamic>) {
      throw const FormatException('Resposta inválida do servidor ao consultar elegibilidade.');
    }
    return BusinessResponseEligibilityDto.fromJson(decoded).toEntity();
  }

  @override
  Future<BusinessResponse> publishReviewBusinessResponse({
    required String reviewId,
    required String businessAccountId,
    required String placeId,
    required String content,
  }) async {
    final Map<String, dynamic> payload = {
      'businessAccountId': businessAccountId,
      'placeId': placeId,
      'content': content.trim(),
    };

    final response = await _httpClient.post(
      ApiEndpoints.createReviewBusinessResponse(reviewId),
      body: payload,
      requiresAuth: true,
    );

    final dynamic decoded = jsonDecode(utf8.decode(response.bodyBytes));
    if (decoded is! Map<String, dynamic>) {
      throw const FormatException('Resposta inválida do servidor ao publicar resposta oficial.');
    }
    return BusinessResponseDto.fromJson(decoded).toEntity();
  }

  @override
  Future<BusinessResponsePage> getDiscussionBusinessResponses(
    String discussionId, {
    int page = 0,
    int size = 20,
  }) async {
    final response = await _httpClient.get(
      ApiEndpoints.discussionBusinessResponses(discussionId, page: page, size: size),
      requiresAuth: true,
    );

    final dynamic decoded = jsonDecode(utf8.decode(response.bodyBytes));
    if (decoded is! Map<String, dynamic>) {
      throw const FormatException('Resposta inválida do servidor ao listar respostas oficiais da discussão.');
    }
    return BusinessResponsePageDto.fromJson(decoded).toEntity();
  }

  @override
  Future<BusinessResponse> publishDiscussionBusinessResponse({
    required String discussionId,
    required String businessAccountId,
    required String placeId,
    required String content,
  }) async {
    final Map<String, dynamic> payload = {
      'businessAccountId': businessAccountId,
      'placeId': placeId,
      'content': content.trim(),
    };

    final response = await _httpClient.post(
      ApiEndpoints.createDiscussionBusinessResponse(discussionId),
      body: payload,
      requiresAuth: true,
    );

    final dynamic decoded = jsonDecode(utf8.decode(response.bodyBytes));
    if (decoded is! Map<String, dynamic>) {
      throw const FormatException('Resposta inválida do servidor ao responder à discussão.');
    }
    return BusinessResponseDto.fromJson(decoded).toEntity();
  }

  @override
  Future<void> deleteBusinessResponse(String responseId) async {
    await _httpClient.delete(
      ApiEndpoints.businessResponse(responseId),
      requiresAuth: true,
    );
  }
}
