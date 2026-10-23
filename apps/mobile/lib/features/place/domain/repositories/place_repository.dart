import 'dart:typed_data';
import '../entities/place_detail.dart';
import '../entities/place_media_item.dart';
export '../entities/place_media_item.dart';
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
    String? idempotencyKey,
  });

  /// Lista as fotografias ativas vinculadas a um local físico (`GET /api/v1/places/{placeId}/media`).
  Future<List<PlaceMediaItem>> getPlaceMedia(String placeId);

  /// Realiza o upload de uma nova fotografia para o local (`POST /api/v1/places/{placeId}/media`),
  /// retornando o [PlaceMediaItem] criado.
  Future<PlaceMediaItem> uploadPlaceMedia({
    required String placeId,
    required Uint8List fileBytes,
    required String filename,
    String? mimeType,
    String? idempotencyKey,
  });

  /// Define a fotografia indicada como foto principal/capa do local (`PUT /api/v1/places/{placeId}/media/{mediaId}/primary`).
  Future<PlaceMediaItem> setPrimaryPlaceMedia({
    required String placeId,
    required String mediaId,
  });

  /// Remove uma fotografia ativa do local (`DELETE /api/v1/places/{placeId}/media/{mediaId}`).
  Future<void> deletePlaceMedia({
    required String placeId,
    required String mediaId,
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
