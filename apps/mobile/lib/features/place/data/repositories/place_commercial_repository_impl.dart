import 'dart:convert';
import 'package:rewit_mobile/core/network/api_endpoints.dart';
import 'package:rewit_mobile/core/network/http_client.dart';
import '../../domain/entities/place_commercial_entities.dart';
import '../../domain/repositories/place_commercial_repository.dart';
import '../models/place_commercial_dtos.dart';

/// Implementação de [PlaceCommercialRepository] consumindo a API REST do Rewit via [RewitHttpClient].
class PlaceCommercialRepositoryImpl implements PlaceCommercialRepository {
  final RewitHttpClient httpClient;

  PlaceCommercialRepositoryImpl({required this.httpClient});

  @override
  Future<PlaceOpeningHours> getOpeningHours(String placeId) async {
    final response = await httpClient.get(ApiEndpoints.placeOpeningHours(placeId));
    final dynamic decoded = jsonDecode(response.body);
    if (decoded is! Map<String, dynamic>) {
      throw const FormatException('Resposta inválida do servidor ao obter horários de funcionamento.');
    }
    return PlaceOpeningHoursDto.fromJson(decoded).toEntity();
  }

  @override
  Future<PlaceAmenities> getAmenities(String placeId) async {
    final response = await httpClient.get(ApiEndpoints.placeAmenities(placeId));
    final dynamic decoded = jsonDecode(response.body);
    if (decoded is! Map<String, dynamic>) {
      throw const FormatException('Resposta inválida do servidor ao obter comodidades do estabelecimento.');
    }
    return PlaceAmenitiesDto.fromJson(decoded).toEntity();
  }

  @override
  Future<PlaceOffersPage> getOffers(
    String placeId, {
    int page = 0,
    int size = 50,
  }) async {
    final response = await httpClient.get(
      ApiEndpoints.placeOffers(placeId, page: page, size: size),
    );
    final dynamic decoded = jsonDecode(response.body);
    if (decoded is! Map<String, dynamic>) {
      throw const FormatException('Resposta inválida do servidor ao obter ofertas comerciais do local.');
    }
    return PlaceOffersPageDto.fromJson(decoded).toEntity();
  }
}

