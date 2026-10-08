import 'package:rewit_mobile/features/feed/domain/entities/feed_entities.dart';
import 'package:rewit_mobile/features/review_detail/domain/entities/helpful_result.dart';
import 'package:rewit_mobile/features/review_detail/domain/entities/review_media.dart';
import 'package:rewit_mobile/features/review_detail/domain/entities/update_review_input.dart';

/// Contrato para recuperação de feed social e consultas de avaliações.
abstract class FeedRepository {
  /// Obtém página do Feed V2 (ranqueado e diversificado pelo backend).
  Future<FeedPage> getFeed({int page = 0, int size = 10});

  /// Consulta detalhes públicos de uma avaliação específica pelo seu ID.
  Future<FeedReview> getReviewById(String reviewId);

  /// Alterna o voto útil (helpful) de uma avaliação.
  Future<HelpfulResult> toggleHelpful(String reviewId, {required bool currentlyHelpful});

  /// Obtém a lista de mídias ativas de uma avaliação.
  Future<List<ReviewMediaItem>> getReviewMedia(String reviewId);

  /// Atualiza os dados de uma avaliação ativa pelo autor (Step 25.3 / C5.7).
  Future<FeedReview> updateReview(String reviewId, UpdateReviewInput input);

  /// Exclui (soft delete) uma avaliação ativa pelo autor (Step 25.3 / C5.7).
  Future<void> deleteReview(String reviewId);
}
