import '../entities/business_response_entities.dart';

/// Contrato de repositório para Respostas Oficiais de Empresas (C11).
abstract class BusinessResponseRepository {
  /// Lista de respostas oficiais ativas a uma avaliação com paginação.
  Future<BusinessResponsePage> getReviewBusinessResponses(
    String reviewId, {
    int page = 0,
    int size = 20,
  });

  /// Consulta as contas e locais elegíveis para responder à avaliação.
  Future<BusinessResponseEligibility> getReviewEligibility(String reviewId);

  /// Publica uma resposta oficial a uma avaliação.
  Future<BusinessResponse> publishReviewBusinessResponse({
    required String reviewId,
    required String businessAccountId,
    required String placeId,
    required String content,
  });

  /// Lista de respostas oficiais ativas a um comentário raiz de discussão com paginação.
  Future<BusinessResponsePage> getDiscussionBusinessResponses(
    String discussionId, {
    int page = 0,
    int size = 20,
  });

  /// Publica uma resposta oficial a um comentário raiz de discussão.
  Future<BusinessResponse> publishDiscussionBusinessResponse({
    required String discussionId,
    required String businessAccountId,
    required String placeId,
    required String content,
  });

  /// Remove a própria resposta oficial publicada pela empresa (HTTP 204).
  Future<void> deleteBusinessResponse(String responseId);
}
