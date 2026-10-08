import '../entities/place_detail.dart';
import '../entities/target_reviews_page.dart';
import '../entities/target_stats.dart';

/// Contrato de repositório para acesso e consulta a locais físicos (Place) e seus alvos avaliáveis associados.
abstract class PlaceRepository {
  /// Obtém os detalhes completos de um local pelo seu identificador único.
  Future<PlaceDetail> getPlaceById(String id);

  /// Obtém as estatísticas agregadas calculadas para o alvo avaliável.
  Future<TargetStats> getTargetStats(String id);

  /// Obtém a listagem paginada de avaliações vinculadas ao alvo avaliável.
  Future<TargetReviewsPage> getTargetReviews(
    String targetId, {
    int page = 0,
    int size = 10,
    String sort = 'newest',
    bool verifiedOnly = false,
  });
}
