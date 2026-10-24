import 'dart:convert';
import 'package:rewit_mobile/core/network/api_endpoints.dart';
import 'package:rewit_mobile/core/network/http_client.dart';
import 'package:rewit_mobile/features/place/data/models/place_dtos.dart';
import 'package:rewit_mobile/features/place/domain/entities/place_detail.dart';
import '../../domain/entities/external_place_candidate.dart';
import '../../domain/repositories/place_discovery_repository.dart';
import '../models/place_discovery_dtos.dart';

/// Implementação de [PlaceDiscoveryRepository] sobre a API Rewit via [RewitHttpClient].
class PlaceDiscoveryRepositoryImpl implements PlaceDiscoveryRepository {
  final RewitHttpClient httpClient;

  PlaceDiscoveryRepositoryImpl({required this.httpClient});

  @override
  Future<List<ExternalPlaceCandidate>> search({
    required String query,
    double? latitude,
    double? longitude,
    double? radiusMeters,
    int limit = 10,
  }) async {
    final response = await httpClient.get(
      ApiEndpoints.placeDiscoverySearch,
      queryParameters: {
        'query': query,
        'limit': limit,
        if (latitude != null && longitude != null) 'latitude': latitude,
        if (latitude != null && longitude != null) 'longitude': longitude,
        if (latitude != null && longitude != null && radiusMeters != null) 'radius': radiusMeters,
      },
    );
    final dynamic decoded = jsonDecode(response.body);
    if (decoded is! List) {
      throw const FormatException('Resposta inválida da descoberta de locais.');
    }
    return decoded
        .whereType<Map<String, dynamic>>()
        .map(ExternalPlaceCandidateDto.fromJson)
        .whereType<ExternalPlaceCandidate>()
        .toList();
  }

  @override
  Future<PlaceDetail> adopt(ExternalPlaceCandidate candidate) async {
    if (!candidate.isAdoptable) {
      throw const FormatException('Candidato sem endereço completo: cadastre o local manualmente.');
    }
    final response = await httpClient.post(
      ApiEndpoints.placeAdopt,
      body: AdoptPlaceRequestDto.fromCandidate(candidate),
    );
    final dynamic decoded = jsonDecode(response.body);
    if (decoded is! Map<String, dynamic>) {
      throw const FormatException('Resposta inválida do servidor ao adotar o local.');
    }
    return PlaceDto.fromJson(decoded).toEntity();
  }
}
