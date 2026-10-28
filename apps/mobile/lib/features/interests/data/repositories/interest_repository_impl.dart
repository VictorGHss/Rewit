import 'dart:convert';

import 'package:rewit_mobile/core/network/api_endpoints.dart';
import 'package:rewit_mobile/core/network/http_client.dart';
import 'package:rewit_mobile/features/interests/data/models/interest_dtos.dart';
import 'package:rewit_mobile/features/interests/domain/entities/user_interests.dart';
import 'package:rewit_mobile/features/interests/domain/repositories/interest_repository.dart';

/// Implementação do repositório de interesses consumindo a API REST.
class InterestRepositoryImpl implements InterestRepository {
  final RewitHttpClient httpClient;

  InterestRepositoryImpl({required this.httpClient});

  @override
  Future<InterestCatalog> getCatalog() async {
    final response = await httpClient.get(ApiEndpoints.interestCatalog);
    return InterestDtos.catalog(jsonDecode(response.body));
  }

  @override
  Future<UserInterests> getMyInterests() async {
    final response = await httpClient.get(ApiEndpoints.myInterests);
    return InterestDtos.userInterests(jsonDecode(response.body));
  }

  @override
  Future<UserInterests> saveMyInterests({
    required List<String> codes,
    required bool completeOnboarding,
  }) async {
    final response = await httpClient.put(
      ApiEndpoints.myInterests,
      body: InterestDtos.saveRequest(codes, completeOnboarding),
    );
    return InterestDtos.userInterests(jsonDecode(response.body));
  }
}
