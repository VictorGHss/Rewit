import 'dart:convert';
import 'dart:typed_data';
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

  static final RegExp _uuidRegex = RegExp(
    r'^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$',
  );

  PlaceRepositoryImpl({required this.httpClient});

  @override
  Future<PlaceDetail> createPlace(CreatePlaceInput input) async {
    final requestDto = CreatePlaceRequestDto.fromEntity(input);
    final response = await httpClient.post(
      ApiEndpoints.places,
      body: requestDto.toJson(),
    );
    final dynamic decoded = jsonDecode(response.body);
    if (decoded is! Map<String, dynamic>) {
      throw const FormatException('Resposta inválida do servidor ao cadastrar local.');
    }
    final entity = PlaceDto.fromJson(decoded).toEntity();
    if (!_uuidRegex.hasMatch(entity.id)) {
      throw const FormatException('O servidor retornou um identificador de local inválido.');
    }
    return entity;
  }

  @override
  Future<String> uploadPlacePhoto({
    required String placeId,
    required Uint8List fileBytes,
    required String filename,
  }) async {
    final response = await httpClient.postMultipart(
      ApiEndpoints.placeMedia(placeId),
      fieldName: 'file',
      fileBytes: fileBytes,
      filename: filename,
    );
    final dynamic decoded = jsonDecode(response.body);
    if (decoded is! Map<String, dynamic>) {
      throw const FormatException('Resposta inválida do servidor ao enviar fotografia do local.');
    }
    final rawUrl = decoded['url'];
    if (rawUrl is! String || rawUrl.trim().isEmpty) {
      throw const FormatException('O servidor não retornou a URL da fotografia do local.');
    }
    return rawUrl.trim();
  }

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
