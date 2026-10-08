import '../../domain/entities/in_app_notification.dart';
import '../../domain/entities/notifications_page.dart';

/// DTO para item de notificação retornado pela API REST.
class NotificationDto {
  final String id;
  final String type;
  final String? actorId;
  final String? referenceId;
  final String? readAt;
  final String createdAt;

  const NotificationDto({
    required this.id,
    required this.type,
    this.actorId,
    this.referenceId,
    this.readAt,
    required this.createdAt,
  });

  factory NotificationDto.fromJson(Map<String, dynamic> json) {
    return NotificationDto(
      id: json['id'] as String,
      type: json['type'] as String,
      actorId: json['actorId'] as String?,
      referenceId: json['referenceId'] as String?,
      readAt: json['readAt'] as String?,
      createdAt: json['createdAt'] as String,
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'id': id,
      'type': type,
      'actorId': actorId,
      'referenceId': referenceId,
      'readAt': readAt,
      'createdAt': createdAt,
    };
  }

  InAppNotification toEntity() {
    return InAppNotification(
      id: id,
      type: NotificationType.fromString(type),
      actorId: actorId,
      referenceId: referenceId,
      readAt: readAt != null ? DateTime.parse(readAt!) : null,
      createdAt: DateTime.parse(createdAt),
    );
  }
}

/// DTO para página de notificações paginada.
class NotificationsPageDto {
  final List<NotificationDto> content;
  final int pageNumber;
  final int pageSize;
  final int totalElements;
  final int totalPages;
  final bool isLast;

  const NotificationsPageDto({
    required this.content,
    required this.pageNumber,
    required this.pageSize,
    required this.totalElements,
    required this.totalPages,
    required this.isLast,
  });

  factory NotificationsPageDto.fromJson(Map<String, dynamic> json) {
    final rawContent = json['content'] as List<dynamic>? ?? [];
    final content = rawContent
        .map((item) => NotificationDto.fromJson(item as Map<String, dynamic>))
        .toList();

    return NotificationsPageDto(
      content: content,
      pageNumber: (json['pageNumber'] as num?)?.toInt() ?? 0,
      pageSize: (json['pageSize'] as num?)?.toInt() ?? 20,
      totalElements: (json['totalElements'] as num?)?.toInt() ?? 0,
      totalPages: (json['totalPages'] as num?)?.toInt() ?? 0,
      isLast: json['isLast'] as bool? ?? true,
    );
  }

  NotificationsPage toEntity() {
    return NotificationsPage(
      items: content.map((dto) => dto.toEntity()).toList(),
      pageNumber: pageNumber,
      pageSize: pageSize,
      totalElements: totalElements,
      totalPages: totalPages,
      isLast: isLast,
    );
  }
}

/// DTO para contagem de notificações não lidas.
class UnreadCountDto {
  final int count;

  const UnreadCountDto({required this.count});

  factory UnreadCountDto.fromJson(Map<String, dynamic> json) {
    return UnreadCountDto(
      count: (json['count'] as num?)?.toInt() ?? 0,
    );
  }
}
