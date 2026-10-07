import 'package:rewit_mobile/features/feed/domain/entities/feed_entities.dart';
import '../entities/review_creation_input.dart';

/// Contrato para criação de publicações de avaliação no backend Rewit.
abstract class ReviewCreationRepository {
  /// Envia a criação de uma nova avaliação para `POST /api/v1/reviews`.
  ///
  /// Retorna a entidade [FeedReview] representacional criada.
  /// Lança [ApiException] com RFC 7807 em erros de validação ou de negócio.
  /// Lança [NetworkException] em falhas locais de conectividade.
  Future<FeedReview> createReview(CreateReviewInput input);
}
