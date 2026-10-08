import 'dart:convert';
import 'dart:typed_data';
import 'package:rewit_mobile/core/network/api_endpoints.dart';
import 'package:rewit_mobile/core/network/http_client.dart';
import 'package:rewit_mobile/features/review_detail/data/models/review_media_dto.dart';
import 'package:rewit_mobile/features/review_detail/domain/entities/review_media.dart';
import 'package:rewit_mobile/features/review_detail/domain/repositories/review_media_repository.dart';

/// Implementação padrão do repositório de mídias de avaliação utilizando [RewitHttpClient].
class ReviewMediaRepositoryImpl implements ReviewMediaRepository {
  final RewitHttpClient _client;

  ReviewMediaRepositoryImpl({required RewitHttpClient client}) : _client = client;

  @override
  Future<List<ReviewMediaItem>> getReviewMedia(String reviewId) async {
    final response = await _client.get(ApiEndpoints.reviewMedia(reviewId));
    final List<dynamic> jsonList = jsonDecode(response.body) as List<dynamic>;
    return jsonList
        .map((item) => ReviewMediaDto.fromJson(item as Map<String, dynamic>).toEntity())
        .toList();
  }

  @override
  Future<ReviewMediaItem> uploadMedia({
    required String reviewId,
    required Uint8List bytes,
    required String filename,
    String? mimeType,
  }) async {
    final response = await _client.postMultipart(
      ApiEndpoints.reviewMedia(reviewId),
      fieldName: 'file',
      fileBytes: bytes,
      filename: filename,
      mimeType: mimeType,
    );

    final jsonMap = jsonDecode(response.body) as Map<String, dynamic>;
    return ReviewMediaDto.fromJson(jsonMap).toEntity();
  }

  @override
  Future<void> deleteMedia({
    required String reviewId,
    required String mediaId,
  }) async {
    await _client.delete(ApiEndpoints.reviewMediaItem(reviewId, mediaId));
  }

  @override
  Future<Uint8List> getMediaBytes(String pathOrUrl) async {
    return _client.getBytes(pathOrUrl);
  }
}
