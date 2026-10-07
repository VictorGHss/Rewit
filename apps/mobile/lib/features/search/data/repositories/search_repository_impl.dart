import 'dart:convert';
import 'package:rewit_mobile/core/network/http_client.dart';
import '../../domain/entities/search_entities.dart';
import '../../domain/repositories/search_repository.dart';
import '../models/search_dtos.dart';

/// Implementação do SearchRepository consumindo o endpoint REST real `/api/v1/search`.
class SearchRepositoryImpl implements SearchRepository {
  final RewitHttpClient httpClient;

  SearchRepositoryImpl({required this.httpClient});

  @override
  Future<SearchPage> search({
    required String query,
    int page = 0,
    int size = 20,
  }) async {
    final response = await httpClient.get(
      '/api/v1/search',
      queryParameters: {
        'q': query,
        'page': page,
        'size': size,
      },
    );

    final dynamic decoded = jsonDecode(response.body);
    if (decoded is! Map<String, dynamic>) {
      throw const FormatException('Resposta inválida do servidor de busca.');
    }

    final dto = SearchPagedResponseDto.fromJson(decoded);
    return dto.toEntity();
  }
}
