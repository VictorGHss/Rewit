import 'dart:convert';
import 'package:rewit_mobile/core/network/api_endpoints.dart';
import 'package:rewit_mobile/core/network/http_client.dart';
import '../../domain/entities/business_entities.dart';
import '../../domain/repositories/business_repository.dart';
import '../models/business_dtos.dart';

class BusinessRepositoryImpl implements BusinessRepository {
  final RewitHttpClient httpClient;

  BusinessRepositoryImpl({required this.httpClient});

  @override
  Future<List<BusinessAccount>> getMyBusinessAccounts() async {
    final response = await httpClient.get(ApiEndpoints.myBusinessAccounts);
    final dynamic decoded = jsonDecode(response.body);

    if (decoded is List) {
      return decoded
          .whereType<Map<String, dynamic>>()
          .map((json) => BusinessAccountDto.fromJson(json).toEntity())
          .toList();
    } else if (decoded is Map<String, dynamic> && decoded['content'] is List) {
      final list = decoded['content'] as List;
      return list
          .whereType<Map<String, dynamic>>()
          .map((json) => BusinessAccountDto.fromJson(json).toEntity())
          .toList();
    }
    return [];
  }

  @override
  Future<BusinessAccount> createBusinessAccount({
    required String corporateName,
    required String taxId,
  }) async {
    final response = await httpClient.post(
      ApiEndpoints.businessAccounts,
      body: {
        'corporateName': corporateName.trim(),
        'taxId': taxId.trim(),
      },
    );
    final dynamic decoded = jsonDecode(response.body);
    if (decoded is! Map<String, dynamic>) {
      throw const FormatException('Resposta inválida do servidor ao criar conta comercial.');
    }
    return BusinessAccountDto.fromJson(decoded).toEntity();
  }

  @override
  Future<PlaceClaim> requestPlaceClaim({
    required String businessAccountId,
    required String placeId,
    required String evidenceDescription,
  }) async {
    final response = await httpClient.post(
      ApiEndpoints.businessPlaceClaims(businessAccountId),
      body: {
        'placeId': placeId,
        'evidenceDescription': evidenceDescription.trim(),
      },
    );
    final dynamic decoded = jsonDecode(response.body);
    if (decoded is! Map<String, dynamic>) {
      throw const FormatException('Resposta inválida do servidor ao solicitar reivindicação.');
    }
    return PlaceClaimDto.fromJson(decoded).toEntity();
  }

  @override
  Future<List<PlaceClaim>> getBusinessPlaceClaims(
    String businessAccountId, {
    int page = 0,
    int size = 20,
    String? status,
  }) async {
    final response = await httpClient.get(
      ApiEndpoints.businessPlaceClaimsPaged(
        businessAccountId,
        page: page,
        size: size,
        status: status,
      ),
    );
    final dynamic decoded = jsonDecode(response.body);

    if (decoded is Map<String, dynamic> && decoded['content'] is List) {
      final list = decoded['content'] as List;
      return list
          .whereType<Map<String, dynamic>>()
          .map((json) => PlaceClaimDto.fromJson(json).toEntity())
          .toList();
    } else if (decoded is List) {
      return decoded
          .whereType<Map<String, dynamic>>()
          .map((json) => PlaceClaimDto.fromJson(json).toEntity())
          .toList();
    }
    return [];
  }
}
