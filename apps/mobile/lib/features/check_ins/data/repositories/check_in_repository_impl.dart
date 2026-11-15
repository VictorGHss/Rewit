import 'dart:convert';

import 'package:rewit_mobile/core/network/api_endpoints.dart';
import 'package:rewit_mobile/core/network/http_client.dart';

import '../../domain/entities/check_in_entities.dart';
import '../../domain/repositories/check_in_repository.dart';

/// Implementação de [CheckInRepository] sobre a API REST do Rewit (sem provedores externos de lugares).
class CheckInRepositoryImpl implements CheckInRepository {
  /// Raio da busca de locais próximos na tela de check-in. É só o alcance da lista: a presença é decidida pelo
  /// backend, com o raio de cada local.
  static const int nearbySearchRadiusMeters = 1000;
  static const int nearbySearchLimit = 20;

  final RewitHttpClient httpClient;

  const CheckInRepositoryImpl({required this.httpClient});

  @override
  Future<List<NearbyPlace>> getNearbyPlaces({required double latitude, required double longitude}) async {
    final response = await httpClient.get(
      ApiEndpoints.nearbyPlaces,
      queryParameters: {
        'latitude': latitude,
        'longitude': longitude,
        'radiusMeters': nearbySearchRadiusMeters,
        'limit': nearbySearchLimit,
      },
    );
    final dynamic decoded = jsonDecode(response.body);
    final items = decoded is Map<String, dynamic> ? decoded['items'] : null;
    if (items is! List) {
      throw const FormatException('Resposta inválida do servidor ao buscar locais próximos.');
    }
    return items.whereType<Map<String, dynamic>>().map(_nearbyPlace).toList();
  }

  @override
  Future<MyCheckInsPage> getMyCheckIns({int page = 0, int size = 20}) async {
    final response = await httpClient.get(
      ApiEndpoints.myCheckIns,
      queryParameters: {'page': page, 'size': size},
    );
    final dynamic decoded = jsonDecode(response.body);
    final content = decoded is Map<String, dynamic> ? decoded['content'] : null;
    if (decoded is! Map<String, dynamic> || content is! List) {
      throw const FormatException('Resposta inválida do servidor ao listar seus check-ins.');
    }
    return MyCheckInsPage(
      items: content.whereType<Map<String, dynamic>>().map(_myCheckIn).toList(),
      pageNumber: (decoded['pageNumber'] as num?)?.toInt() ?? page,
      totalElements: (decoded['totalElements'] as num?)?.toInt() ?? 0,
      isLast: decoded['isLast'] as bool? ?? true,
    );
  }

  static NearbyPlace _nearbyPlace(Map<String, dynamic> json) {
    final id = json['id'];
    final name = json['name'];
    final distance = json['distanceMeters'];
    if (id is! String || name is! String || distance is! num) {
      throw const FormatException('Local próximo inválido na resposta do servidor.');
    }
    return NearbyPlace(
      id: id,
      name: name,
      category: json['category'] as String?,
      addressText: json['addressText'] as String?,
      distanceMeters: distance.toDouble(),
    );
  }

  static MyCheckIn _myCheckIn(Map<String, dynamic> json) {
    final id = json['id'];
    final reviewId = json['reviewId'];
    final placeId = json['placeId'];
    final placeName = json['placeName'];
    final verifiedAt = DateTime.tryParse(json['verifiedAt'] as String? ?? '');
    if (id is! String || reviewId is! String || placeId is! String || placeName is! String || verifiedAt == null) {
      throw const FormatException('Check-in inválido na resposta do servidor.');
    }
    return MyCheckIn(
      id: id,
      reviewId: reviewId,
      placeId: placeId,
      placeName: placeName,
      placeCategory: json['placeCategory'] as String?,
      placeCity: json['placeCity'] as String?,
      placeState: json['placeState'] as String?,
      verifiedAt: verifiedAt,
    );
  }
}
