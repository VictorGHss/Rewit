import 'dart:convert';
import 'dart:typed_data';
import 'package:rewit_mobile/core/network/api_endpoints.dart';
import 'package:rewit_mobile/core/network/http_client.dart';
import '../../domain/entities/place_detail.dart';
import '../../domain/entities/target_reviews_page.dart';
import '../../domain/entities/target_stats.dart';
import '../../domain/repositories/place_repository.dart';
import '../models/place_dtos.dart';
import '../models/place_media_dto.dart';

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
    String? idempotencyKey,
  }) async {
    final media = await uploadPlaceMedia(
      placeId: placeId,
      fileBytes: fileBytes,
      filename: filename,
      idempotencyKey: idempotencyKey,
    );
    return media.url;
  }

  @override
  Future<List<PlaceMediaItem>> getPlaceMedia(String placeId) async {
    final response = await httpClient.get(ApiEndpoints.placeMedia(placeId));
    final dynamic decoded = jsonDecode(response.body);
    if (decoded is List) {
      return decoded
          .whereType<Map<String, dynamic>>()
          .map((json) => PlaceMediaDto.fromJson(json).toEntity())
          .toList();
    }
    return [];
  }

  @override
  Future<PlaceMediaItem> uploadPlaceMedia({
    required String placeId,
    required Uint8List fileBytes,
    required String filename,
    String? mimeType,
    String? idempotencyKey,
  }) async {
    final Map<String, String>? headers = (idempotencyKey != null && idempotencyKey.trim().isNotEmpty)
        ? {'Idempotency-Key': idempotencyKey.trim()}
        : null;

    final response = await httpClient.postMultipart(
      ApiEndpoints.placeMedia(placeId),
      fieldName: 'file',
      fileBytes: fileBytes,
      filename: filename,
      mimeType: mimeType,
      headers: headers,
    );
    final dynamic decoded = jsonDecode(response.body);
    if (decoded is! Map<String, dynamic>) {
      throw const FormatException('Resposta inválida do servidor ao enviar fotografia do local.');
    }
    return PlaceMediaDto.fromJson(decoded).toEntity();
  }

  @override
  Future<PlaceMediaItem> setPrimaryPlaceMedia({
    required String placeId,
    required String mediaId,
  }) async {
    final response = await httpClient.put(
      ApiEndpoints.placeMediaPrimary(placeId, mediaId),
    );
    final dynamic decoded = jsonDecode(response.body);
    if (decoded is! Map<String, dynamic>) {
      throw const FormatException('Resposta inválida do servidor ao definir foto principal.');
    }
    return PlaceMediaDto.fromJson(decoded).toEntity();
  }

  @override
  Future<void> deletePlaceMedia({
    required String placeId,
    required String mediaId,
  }) async {
    await httpClient.delete(ApiEndpoints.placeMediaItem(placeId, mediaId));
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
