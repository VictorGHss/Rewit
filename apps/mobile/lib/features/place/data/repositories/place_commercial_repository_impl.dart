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
  Future<PlaceOpeningHours> updateOpeningHours({
    required String placeId,
    required String businessAccountId,
    required List<DaySchedule> days,
  }) async {
    final payload = {
      'businessAccountId': businessAccountId,
      'days': days.map((d) => {
        'dayOfWeek': d.dayOfWeek,
        'closed': d.closed,
        'intervals': d.closed
            ? <Map<String, dynamic>>[]
            : d.intervals.map((i) => {
                'openTime': i.openTime,
                'closeTime': i.closeTime,
              }).toList(),
      }).toList(),
    };

    final response = await httpClient.put(
      ApiEndpoints.placeOpeningHours(placeId),
      body: jsonEncode(payload),
    );
    final dynamic decoded = jsonDecode(response.body);
    if (decoded is! Map<String, dynamic>) {
      throw const FormatException('Resposta inválida do servidor ao atualizar horários de funcionamento.');
    }
    return PlaceOpeningHoursDto.fromJson(decoded).toEntity();
  }

  @override
  Future<List<PlaceAmenityItem>> getAmenityOptions() async {
    final response = await httpClient.get(ApiEndpoints.placeAmenityOptions);
    final dynamic decoded = jsonDecode(response.body);
    if (decoded is! Map<String, dynamic>) {
      throw const FormatException('Resposta inválida do servidor ao listar catálogo de comodidades.');
    }
    final rawOptions = decoded['options'] as List<dynamic>?;
    if (rawOptions == null) {
      return const [];
    }
    return rawOptions
        .whereType<Map<String, dynamic>>()
        .map((item) => AmenityItemDto.fromJson(item).toEntity())
        .toList();
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
  Future<PlaceAmenities> updateAmenities({
    required String placeId,
    required String businessAccountId,
    required List<String> amenityCodes,
  }) async {
    final payload = {
      'businessAccountId': businessAccountId,
      'amenities': amenityCodes,
    };

    final response = await httpClient.put(
      ApiEndpoints.placeAmenities(placeId),
      body: jsonEncode(payload),
    );
    final dynamic decoded = jsonDecode(response.body);
    if (decoded is! Map<String, dynamic>) {
      throw const FormatException('Resposta inválida do servidor ao atualizar comodidades do estabelecimento.');
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
  @override
  Future<PlaceProductOffer> upsertOffer({
    required String placeId,
    required String productId,
    required String businessAccountId,
    required String availability,
    double? price,
    String currency = 'BRL',
  }) async {
    final payload = <String, dynamic>{
      'businessAccountId': businessAccountId,
      'availability': availability,
      'price': price,
      'currency': currency,
    };

    final response = await httpClient.put(
      ApiEndpoints.placeProductOffer(placeId, productId),
      body: jsonEncode(payload),
    );

    final dynamic decoded = jsonDecode(response.body);
    if (decoded is! Map<String, dynamic>) {
      throw const FormatException('Resposta inválida do servidor ao salvar oferta de produto.');
    }
    return PlaceProductOfferDto.fromJson(decoded).toEntity();
  }

  @override
  Future<void> deleteOffer({
    required String placeId,
    required String productId,
    required String businessAccountId,
  }) async {
    await httpClient.delete(
      ApiEndpoints.placeProductOffer(placeId, productId),
      queryParameters: {'businessAccountId': businessAccountId},
    );
  }
}
