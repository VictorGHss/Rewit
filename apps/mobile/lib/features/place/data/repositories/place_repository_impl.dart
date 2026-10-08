import 'dart:convert';
import 'package:rewit_mobile/core/network/api_endpoints.dart';
import 'package:rewit_mobile/core/network/http_client.dart';
import '../../domain/entities/place_detail.dart';
import '../../domain/entities/target_reviews_page.dart';
import '../../domain/entities/target_stats.dart';
import '../../domain/repositories/place_repository.dart';
import '../models/place_dtos.dart';

/// Implementação de [PlaceRepository] consumindo a API REST do Rewit via [RewitHttpClient].
class PlaceRepositoryImpl implements PlaceRepository {
  final RewitHttpClient httpClient;

  PlaceRepositoryImpl({required this.httpClient});

  @override
  Future<PlaceDetail> getPlaceById(String id) async {
    final response = await httpClient.get(ApiEndpoints.placeDetail(id));
    final dynamic decoded = jsonDecode(response.body);
    if (decoded is! Map<String, dynamic>) {
      throw const FormatException('Resposta inválida do servidor ao obter detalhes do local.');
    }
    return PlaceDto.fromJson(decoded).toEntity();
  }

  @override
  Future<TargetStats> getTargetStats(String id) async {
    final response = await httpClient.get(ApiEndpoints.targetStats(id));
    final dynamic decoded = jsonDecode(response.body);
    if (decoded is! Map<String, dynamic>) {
      throw const FormatException('Resposta inválida do servidor ao obter estatísticas do alvo.');
    }
    return TargetStatsDto.fromJson(decoded).toEntity();
  }

  @override
  Future<TargetReviewsPage> getTargetReviews(
    String targetId, {
    int page = 0,
    int size = 10,
    String sort = 'newest',
    bool verifiedOnly = false,
  }) async {
    final response = await httpClient.get(
      ApiEndpoints.targetReviewsPath(targetId),
      queryParameters: {
        'page': page,
        'size': size,
        'sort': sort,
        'verifiedOnly': verifiedOnly,
      },
    );
    final dynamic decoded = jsonDecode(response.body);
    if (decoded is! Map<String, dynamic>) {
      throw const FormatException('Resposta inválida do servidor ao listar avaliações do alvo.');
    }
    return TargetReviewsPageDto.fromJson(decoded).toEntity();
  }
}
