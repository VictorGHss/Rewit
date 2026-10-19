import 'dart:typed_data';
import '../entities/place_detail.dart';
import '../entities/target_reviews_page.dart';
import '../entities/target_stats.dart';

/// Contrato de repositório para acesso e consulta a locais físicos (Place) e seus alvos avaliáveis associados.
abstract class PlaceRepository {
  /// Cadastra manualmente um novo local no catálogo e retorna a entidade com o UUID canônico do backend.
  Future<PlaceDetail> createPlace(CreatePlaceInput input);

  /// Envia e vincula uma fotografia diretamente ao local (`POST /api/v1/places/{placeId}/media`),
  /// retornando a URL pública da fotografia sanitizada.
  Future<String> uploadPlacePhoto({
    required String placeId,
    required Uint8List fileBytes,
    required String filename,
  });

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
