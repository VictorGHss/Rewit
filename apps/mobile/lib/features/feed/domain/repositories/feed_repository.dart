import '../entities/feed_entities.dart';

/// Contrato para recuperação de feed social e consultas de avaliações.
abstract class FeedRepository {
  /// Obtém página do Feed V2 (ranqueado e diversificado pelo backend).
  Future<FeedPage> getFeed({int page = 0, int size = 10});

  /// Consulta detalhes públicos de uma avaliação específica pelo seu ID.
  Future<FeedReview> getReviewById(String reviewId);
}
