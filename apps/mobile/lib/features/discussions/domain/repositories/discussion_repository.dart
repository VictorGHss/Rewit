import 'package:rewit_mobile/features/discussions/domain/entities/discussion_entities.dart';

/// Contrato de repositório para discussões, respostas e denúncias comunitárias.
abstract class DiscussionRepository {
  /// Obtém página de discussões raiz de uma avaliação.
  Future<DiscussionPage> getDiscussions(String reviewId, {int page = 0, int size = 20});

  /// Obtém página de respostas de uma discussão raiz específica.
  Future<DiscussionRepliesPage> getReplies(String discussionId, {int page = 0, int size = 20});

  /// Publica um novo comentário raiz ou uma resposta a uma discussão existente.
  Future<DiscussionItem> createDiscussion({
    required String reviewId,
    required String content,
    String? parentId,
  });

  /// Exclui um comentário publicado pelo próprio autor.
  Future<void> deleteDiscussion(String discussionId);

  /// Submete uma denúncia contra um comentário com motivo e justificativa opcional.
  /// Retorna mensagem genérica de confirmação conforme fornecido pelo backend.
  Future<String> reportDiscussion({
    required String discussionId,
    required ReportReason reason,
    String? detail,
  });
}
