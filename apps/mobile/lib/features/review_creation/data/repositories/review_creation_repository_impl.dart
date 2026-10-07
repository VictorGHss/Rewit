import 'dart:convert';
import 'package:rewit_mobile/core/network/api_endpoints.dart';
import 'package:rewit_mobile/core/network/http_client.dart';
import 'package:rewit_mobile/features/feed/data/models/feed_models.dart';
import 'package:rewit_mobile/features/feed/domain/entities/feed_entities.dart';
import '../../domain/entities/review_creation_input.dart';
import '../../domain/repositories/review_creation_repository.dart';

/// Implementação do repositório de criação de avaliações via cliente HTTP Rewit.
class ReviewCreationRepositoryImpl implements ReviewCreationRepository {
  final RewitHttpClient httpClient;

  ReviewCreationRepositoryImpl({required this.httpClient});

  @override
  Future<FeedReview> createReview(CreateReviewInput input) async {
    final response = await httpClient.post(
      ApiEndpoints.reviews,
      body: input.toJson(),
    );

    final dynamic decoded = jsonDecode(response.body);
    if (decoded is! Map<String, dynamic>) {
      throw const FormatException('Resposta inválida do servidor ao criar avaliação.');
    }

    final dto = FeedReviewDto.fromJson(decoded);
    return dto.toEntity();
  }
}
