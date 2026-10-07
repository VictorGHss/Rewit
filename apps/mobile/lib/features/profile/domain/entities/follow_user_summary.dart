/// Resumo de perfil de usuário em listagens sociais (seguidores / seguindo).
class FollowUserSummary {
  final String id;
  final String handle;
  final String displayName;
  final String? avatarUrl;
  final DateTime? followedAt;

  const FollowUserSummary({
    required this.id,
    required this.handle,
    required this.displayName,
    this.avatarUrl,
    this.followedAt,
  });
}

/// Envelope paginado de usuários em listagens sociais.
class PagedFollowUsers {
  final List<FollowUserSummary> items;
  final int pageNumber;
  final int pageSize;
  final int totalElements;
  final int totalPages;
  final bool isLast;

  const PagedFollowUsers({
    required this.items,
    required this.pageNumber,
    required this.pageSize,
    required this.totalElements,
    required this.totalPages,
    required this.isLast,
  });

  bool get isEmpty => items.isEmpty;
  bool get isNotEmpty => items.isNotEmpty;
}
