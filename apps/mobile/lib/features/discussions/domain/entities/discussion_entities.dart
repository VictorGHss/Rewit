/// Estado representacional de uma discussão retornado pelo backend.
enum DiscussionViewState {
  /// Visível publicamente para a comunidade.
  visible,

  /// Removido por moderação ou pelo autor. Conteúdo e autor omitidos pelo backend.
  removed,

  /// Em análise pela moderação (visível exclusivamente para o autor do comentário).
  pendingReview;

  static DiscussionViewState fromString(String? value) {
    switch (value?.toUpperCase()) {
      case 'REMOVED':
        return DiscussionViewState.removed;
      case 'PENDING_REVIEW':
        return DiscussionViewState.pendingReview;
      case 'VISIBLE':
      default:
        return DiscussionViewState.visible;
    }
  }
}

/// Motivos padronizados para denúncia de comentários em conformidade com o backend Rewit.
enum ReportReason {
  spam('SPAM', 'Spam ou conteúdo comercial não solicitado'),
  harassment('HARASSMENT', 'Assédio, ameaça ou intimidação pessoal'),
  hateSpeech('HATE_SPEECH', 'Discurso de ódio ou discriminação'),
  misinformation('MISINFORMATION', 'Desinformação ou conteúdo comprovadamente enganoso'),
  inappropriateContent('INAPPROPRIATE_CONTENT', 'Conteúdo impróprio ou ofensivo à comunidade'),
  fraud('FRAUD', 'Fraude, golpe ou tentativa de enganar os usuários');

  final String backendValue;
  final String label;

  const ReportReason(this.backendValue, this.label);
}

/// Perfil do autor de uma discussão/comentário.
class DiscussionAuthor {
  final String? id;
  final String? handle;
  final String? displayName;
  final String? avatarUrl;

  const DiscussionAuthor({
    this.id,
    this.handle,
    this.displayName,
    this.avatarUrl,
  });
}

/// Item individual de discussão (comentário de resposta ou representação de item).
class DiscussionItem {
  final String id;
  final String reviewId;
  final String? parentId;
  final DiscussionViewState state;
  final String? content;
  final DiscussionAuthor? author;
  final bool isFromOwner;
  final DateTime createdAt;
  final bool canReply;
  final bool canDelete;

  const DiscussionItem({
    required this.id,
    required this.reviewId,
    this.parentId,
    required this.state,
    this.content,
    this.author,
    required this.isFromOwner,
    required this.createdAt,
    required this.canReply,
    required this.canDelete,
  });

  bool get isRemoved => state == DiscussionViewState.removed;
  bool get isPendingReview => state == DiscussionViewState.pendingReview;
  bool get isVisible => state == DiscussionViewState.visible;
}

/// Raiz de uma thread de discussão com suas respostas associadas e contadores.
class DiscussionThread {
  final String id;
  final String reviewId;
  final String? parentId;
  final DiscussionViewState state;
  final String? content;
  final DiscussionAuthor? author;
  final bool isFromOwner;
  final DateTime createdAt;
  final bool canReply;
  final bool canDelete;
  final List<DiscussionItem> replies;
  final int replyCount;
  final bool hasMoreReplies;

  const DiscussionThread({
    required this.id,
    required this.reviewId,
    this.parentId,
    required this.state,
    this.content,
    this.author,
    required this.isFromOwner,
    required this.createdAt,
    required this.canReply,
    required this.canDelete,
    required this.replies,
    required this.replyCount,
    required this.hasMoreReplies,
  });

  bool get isRemoved => state == DiscussionViewState.removed;
  bool get isPendingReview => state == DiscussionViewState.pendingReview;
  bool get isVisible => state == DiscussionViewState.visible;

  DiscussionThread copyWith({
    List<DiscussionItem>? replies,
    int? replyCount,
    bool? hasMoreReplies,
    DiscussionViewState? state,
    bool? canDelete,
    bool? canReply,
  }) {
    return DiscussionThread(
      id: id,
      reviewId: reviewId,
      parentId: parentId,
      state: state ?? this.state,
      content: content,
      author: author,
      isFromOwner: isFromOwner,
      createdAt: createdAt,
      canReply: canReply ?? this.canReply,
      canDelete: canDelete ?? this.canDelete,
      replies: replies ?? this.replies,
      replyCount: replyCount ?? this.replyCount,
      hasMoreReplies: hasMoreReplies ?? this.hasMoreReplies,
    );
  }
}

/// Página de raízes de discussões retornada pela API REST.
class DiscussionPage {
  final List<DiscussionThread> content;
  final int pageNumber;
  final int pageSize;
  final int totalElements;
  final int totalPages;
  final bool isLast;

  const DiscussionPage({
    required this.content,
    required this.pageNumber,
    required this.pageSize,
    required this.totalElements,
    required this.totalPages,
    required this.isLast,
  });
}

/// Página de respostas paginadas de uma raiz.
class DiscussionRepliesPage {
  final List<DiscussionItem> content;
  final int pageNumber;
  final int pageSize;
  final int totalElements;
  final int totalPages;
  final bool isLast;

  const DiscussionRepliesPage({
    required this.content,
    required this.pageNumber,
    required this.pageSize,
    required this.totalElements,
    required this.totalPages,
    required this.isLast,
  });
}
