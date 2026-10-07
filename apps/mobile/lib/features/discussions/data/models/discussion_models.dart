import 'package:rewit_mobile/features/discussions/domain/entities/discussion_entities.dart';

/// DTO para deserializar o autor de uma discussão.
class DiscussionAuthorDto {
  final String? id;
  final String? handle;
  final String? displayName;
  final String? avatarUrl;

  const DiscussionAuthorDto({
    this.id,
    this.handle,
    this.displayName,
    this.avatarUrl,
  });

  factory DiscussionAuthorDto.fromJson(Map<String, dynamic> json) {
    return DiscussionAuthorDto(
      id: json['id'] as String?,
      handle: json['handle'] as String?,
      displayName: json['displayName'] as String?,
      avatarUrl: json['avatarUrl'] as String?,
    );
  }

  DiscussionAuthor toEntity() {
    return DiscussionAuthor(
      id: id,
      handle: handle,
      displayName: displayName,
      avatarUrl: avatarUrl,
    );
  }
}

/// DTO para deserializar uma resposta ou item de discussão (DiscussionItemResponse).
class DiscussionItemDto {
  final String id;
  final String reviewId;
  final String? parentId;
  final String state;
  final String? content;
  final DiscussionAuthorDto? author;
  final bool isFromOwner;
  final DateTime createdAt;
  final bool canReply;
  final bool canDelete;

  const DiscussionItemDto({
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

  factory DiscussionItemDto.fromJson(Map<String, dynamic> json) {
    return DiscussionItemDto(
      id: json['id'] as String? ?? '',
      reviewId: json['reviewId'] as String? ?? '',
      parentId: json['parentId'] as String?,
      state: json['state'] as String? ?? 'VISIBLE',
      content: json['content'] as String?,
      author: json['author'] != null
          ? DiscussionAuthorDto.fromJson(json['author'] as Map<String, dynamic>)
          : null,
      isFromOwner: json['isFromOwner'] as bool? ?? false,
      createdAt: json['createdAt'] != null
          ? DateTime.tryParse(json['createdAt'] as String) ?? DateTime.now()
          : DateTime.now(),
      canReply: json['canReply'] as bool? ?? false,
      canDelete: json['canDelete'] as bool? ?? false,
    );
  }

  DiscussionItem toEntity() {
    return DiscussionItem(
      id: id,
      reviewId: reviewId,
      parentId: parentId,
      state: DiscussionViewState.fromString(state),
      content: content,
      author: author?.toEntity(),
      isFromOwner: isFromOwner,
      createdAt: createdAt,
      canReply: canReply,
      canDelete: canDelete,
    );
  }
}

/// DTO para deserializar a raiz de uma thread com respostas embutidas (DiscussionThreadResponse).
class DiscussionThreadDto {
  final String id;
  final String reviewId;
  final String? parentId;
  final String state;
  final String? content;
  final DiscussionAuthorDto? author;
  final bool isFromOwner;
  final DateTime createdAt;
  final bool canReply;
  final bool canDelete;
  final List<DiscussionItemDto> replies;
  final int replyCount;
  final bool hasMoreReplies;

  const DiscussionThreadDto({
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

  factory DiscussionThreadDto.fromJson(Map<String, dynamic> json) {
    final repliesList = (json['replies'] as List<dynamic>?)
            ?.map((e) => DiscussionItemDto.fromJson(e as Map<String, dynamic>))
            .toList() ??
        [];

    return DiscussionThreadDto(
      id: json['id'] as String? ?? '',
      reviewId: json['reviewId'] as String? ?? '',
      parentId: json['parentId'] as String?,
      state: json['state'] as String? ?? 'VISIBLE',
      content: json['content'] as String?,
      author: json['author'] != null
          ? DiscussionAuthorDto.fromJson(json['author'] as Map<String, dynamic>)
          : null,
      isFromOwner: json['isFromOwner'] as bool? ?? false,
      createdAt: json['createdAt'] != null
          ? DateTime.tryParse(json['createdAt'] as String) ?? DateTime.now()
          : DateTime.now(),
      canReply: json['canReply'] as bool? ?? false,
      canDelete: json['canDelete'] as bool? ?? false,
      replies: repliesList,
      replyCount: (json['replyCount'] as num?)?.toInt() ?? 0,
      hasMoreReplies: json['hasMoreReplies'] as bool? ?? false,
    );
  }

  DiscussionThread toEntity() {
    return DiscussionThread(
      id: id,
      reviewId: reviewId,
      parentId: parentId,
      state: DiscussionViewState.fromString(state),
      content: content,
      author: author?.toEntity(),
      isFromOwner: isFromOwner,
      createdAt: createdAt,
      canReply: canReply,
      canDelete: canDelete,
      replies: replies.map((r) => r.toEntity()).toList(),
      replyCount: replyCount,
      hasMoreReplies: hasMoreReplies,
    );
  }
}

/// DTO para deserializar a resposta paginada de threads de discussão.
class DiscussionPageDto {
  final List<DiscussionThreadDto> content;
  final int pageNumber;
  final int pageSize;
  final int totalElements;
  final int totalPages;
  final bool isLast;

  const DiscussionPageDto({
    required this.content,
    required this.pageNumber,
    required this.pageSize,
    required this.totalElements,
    required this.totalPages,
    required this.isLast,
  });

  factory DiscussionPageDto.fromJson(Map<String, dynamic> json) {
    final contentList = (json['content'] as List<dynamic>?)
            ?.map((e) => DiscussionThreadDto.fromJson(e as Map<String, dynamic>))
            .toList() ??
        [];

    return DiscussionPageDto(
      content: contentList,
      pageNumber: (json['pageNumber'] as num?)?.toInt() ?? 0,
      pageSize: (json['pageSize'] as num?)?.toInt() ?? 20,
      totalElements: (json['totalElements'] as num?)?.toInt() ?? 0,
      totalPages: (json['totalPages'] as num?)?.toInt() ?? 0,
      isLast: json['isLast'] as bool? ?? true,
    );
  }

  DiscussionPage toEntity() {
    return DiscussionPage(
      content: content.map((c) => c.toEntity()).toList(),
      pageNumber: pageNumber,
      pageSize: pageSize,
      totalElements: totalElements,
      totalPages: totalPages,
      isLast: isLast,
    );
  }
}

/// DTO para deserializar a resposta paginada de respostas de uma raiz.
class DiscussionRepliesPageDto {
  final List<DiscussionItemDto> content;
  final int pageNumber;
  final int pageSize;
  final int totalElements;
  final int totalPages;
  final bool isLast;

  const DiscussionRepliesPageDto({
    required this.content,
    required this.pageNumber,
    required this.pageSize,
    required this.totalElements,
    required this.totalPages,
    required this.isLast,
  });

  factory DiscussionRepliesPageDto.fromJson(Map<String, dynamic> json) {
    final contentList = (json['content'] as List<dynamic>?)
            ?.map((e) => DiscussionItemDto.fromJson(e as Map<String, dynamic>))
            .toList() ??
        [];

    return DiscussionRepliesPageDto(
      content: contentList,
      pageNumber: (json['pageNumber'] as num?)?.toInt() ?? 0,
      pageSize: (json['pageSize'] as num?)?.toInt() ?? 20,
      totalElements: (json['totalElements'] as num?)?.toInt() ?? 0,
      totalPages: (json['totalPages'] as num?)?.toInt() ?? 0,
      isLast: json['isLast'] as bool? ?? true,
    );
  }

  DiscussionRepliesPage toEntity() {
    return DiscussionRepliesPage(
      content: content.map((c) => c.toEntity()).toList(),
      pageNumber: pageNumber,
      pageSize: pageSize,
      totalElements: totalElements,
      totalPages: totalPages,
      isLast: isLast,
    );
  }
}

/// DTO de resposta para confirmação de denúncia (DiscussionReportReceiptResponse).
class DiscussionReportReceiptDto {
  final String status;
  final String message;

  const DiscussionReportReceiptDto({
    required this.status,
    required this.message,
  });

  factory DiscussionReportReceiptDto.fromJson(Map<String, dynamic> json) {
    return DiscussionReportReceiptDto(
      status: json['status'] as String? ?? 'RECEIVED',
      message: json['message'] as String? ??
          'Denúncia recebida. Obrigado por ajudar a manter a comunidade segura.',
    );
  }
}

/// DTO de resposta à criação de comentário (DiscussionResponse).
class CreatedDiscussionDto {
  final String id;
  final String reviewId;
  final String? parentId;
  final String? content;
  final bool isFromOwner;
  final String status;
  final DateTime createdAt;

  const CreatedDiscussionDto({
    required this.id,
    required this.reviewId,
    this.parentId,
    this.content,
    required this.isFromOwner,
    required this.status,
    required this.createdAt,
  });

  factory CreatedDiscussionDto.fromJson(Map<String, dynamic> json) {
    return CreatedDiscussionDto(
      id: json['id'] as String? ?? '',
      reviewId: json['reviewId'] as String? ?? '',
      parentId: json['parentId'] as String?,
      content: json['content'] as String?,
      isFromOwner: json['isFromOwner'] as bool? ?? false,
      status: json['status'] as String? ?? 'ACTIVE',
      createdAt: json['createdAt'] != null
          ? DateTime.tryParse(json['createdAt'] as String) ?? DateTime.now()
          : DateTime.now(),
    );
  }

  DiscussionItem toEntity() {
    return DiscussionItem(
      id: id,
      reviewId: reviewId,
      parentId: parentId,
      state: DiscussionViewState.visible,
      content: content,
      author: null,
      isFromOwner: isFromOwner,
      createdAt: createdAt,
      canReply: parentId == null,
      canDelete: true,
    );
  }
}
