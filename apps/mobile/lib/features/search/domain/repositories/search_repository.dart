import '../entities/search_entities.dart';

/// Contrato para busca unificada de itens avaliáveis no catálogo (Search V1).
abstract class SearchRepository {
  /// Executa busca textual contra `GET /api/v1/search?q={query}&page={page}&size={size}`.
  ///
  /// Retorna [SearchPage] com os alvos avaliáveis encontrados.
  /// Lança [ApiException] com RFC 7807 em caso de erro retornado pelo servidor.
  /// Lança [NetworkException] em falhas locais de rede.
  Future<SearchPage> search({
    required String query,
    int page = 0,
    int size = 20,
  });
}
