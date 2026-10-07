import 'package:rewit_mobile/features/profile/domain/entities/follow_user_summary.dart';

/// DTO para deserialização de usuário em listagens sociais (/followers e /following).
class FollowUserSummaryDto {
  final String id;
  final String handle;
  final String displayName;
  final String? avatarUrl;
  final DateTime? followedAt;

  const FollowUserSummaryDto({
    required this.id,
    required this.handle,
    required this.displayName,
    this.avatarUrl,
    this.followedAt,
  });

  factory FollowUserSummaryDto.fromJson(Map<String, dynamic> json) {
    DateTime? parsedDate;
    if (json['followedAt'] is String) {
      parsedDate = DateTime.tryParse(json['followedAt'] as String);
    }
    return FollowUserSummaryDto(
      id: json['id'] as String? ?? json['userId'] as String? ?? '',
      handle: json['handle'] as String? ?? '',
      displayName: json['displayName'] as String? ?? '',
      avatarUrl: json['avatarUrl'] as String?,
      followedAt: parsedDate,
    );
  }

  FollowUserSummary toEntity() => FollowUserSummary(
        id: id,
        handle: handle,
        displayName: displayName,
        avatarUrl: avatarUrl,
        followedAt: followedAt,
      );
}

/// DTO para deserialização de respostas paginadas de conexões sociais.
class PagedFollowUsersDto {
  final List<FollowUserSummaryDto> content;
  final int pageNumber;
  final int pageSize;
  final int totalElements;
  final int totalPages;
  final bool isLast;

  const PagedFollowUsersDto({
    required this.content,
    required this.pageNumber,
    required this.pageSize,
    required this.totalElements,
    required this.totalPages,
    required this.isLast,
  });

  factory PagedFollowUsersDto.fromJson(Map<String, dynamic> json) {
    final rawContent = json['content'] as List<dynamic>? ?? [];
    final items = rawContent
        .whereType<Map<String, dynamic>>()
        .map(FollowUserSummaryDto.fromJson)
        .toList();

    return PagedFollowUsersDto(
      content: items,
      pageNumber: (json['pageNumber'] as num?)?.toInt() ?? 0,
      pageSize: (json['pageSize'] as num?)?.toInt() ?? 10,
      totalElements: (json['totalElements'] as num?)?.toInt() ?? 0,
      totalPages: (json['totalPages'] as num?)?.toInt() ?? 1,
      isLast: json['isLast'] as bool? ?? true,
    );
  }

  PagedFollowUsers toEntity() => PagedFollowUsers(
        items: content.map((e) => e.toEntity()).toList(),
        pageNumber: pageNumber,
        pageSize: pageSize,
        totalElements: totalElements,
        totalPages: totalPages,
        isLast: isLast,
      );
}
