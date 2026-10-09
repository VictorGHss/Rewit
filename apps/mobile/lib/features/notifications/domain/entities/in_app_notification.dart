/// Tipos de notificação in-app suportadas pelo Rewit (Step 22.0 / C5.4).
enum NotificationType {
  newFollower,
  reviewHelpful,
  newDiscussion,
  discussionReply;

  static NotificationType fromString(String raw) {
    switch (raw.toUpperCase()) {
      case 'NEW_FOLLOWER':
        return NotificationType.newFollower;
      case 'REVIEW_HELPFUL':
        return NotificationType.reviewHelpful;
      case 'NEW_DISCUSSION':
        return NotificationType.newDiscussion;
      case 'DISCUSSION_REPLY':
        return NotificationType.discussionReply;
      default:
        throw ArgumentError('Tipo de notificação desconhecido: $raw');
    }
  }

  String toServerValue() {
    switch (this) {
      case NotificationType.newFollower:
        return 'NEW_FOLLOWER';
      case NotificationType.reviewHelpful:
        return 'REVIEW_HELPFUL';
      case NotificationType.newDiscussion:
        return 'NEW_DISCUSSION';
      case NotificationType.discussionReply:
        return 'DISCUSSION_REPLY';
    }
  }
}

/// Entidade de domínio representando uma notificação in-app.
class InAppNotification {
  final String id;
  final NotificationType type;
  final String? actorId;
  final String? referenceId;
  final String? reviewId;
  final String? discussionId;
  final DateTime? readAt;
  final DateTime createdAt;

  const InAppNotification({
    required this.id,
    required this.type,
    this.actorId,
    this.referenceId,
    this.reviewId,
    this.discussionId,
    this.readAt,
    required this.createdAt,
  });

  bool get isRead => readAt != null;

  InAppNotification copyWith({
    String? id,
    NotificationType? type,
    String? actorId,
    String? referenceId,
    String? reviewId,
    String? discussionId,
    DateTime? readAt,
    DateTime? createdAt,
    bool clearReadAt = false,
  }) {
    return InAppNotification(
      id: id ?? this.id,
      type: type ?? this.type,
      actorId: actorId ?? this.actorId,
      referenceId: referenceId ?? this.referenceId,
      reviewId: reviewId ?? this.reviewId,
      discussionId: discussionId ?? this.discussionId,
      readAt: clearReadAt ? null : (readAt ?? this.readAt),
      createdAt: createdAt ?? this.createdAt,
    );
  }

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is InAppNotification &&
          runtimeType == other.runtimeType &&
          id == other.id &&
          type == other.type &&
          actorId == other.actorId &&
          referenceId == other.referenceId &&
          reviewId == other.reviewId &&
          discussionId == other.discussionId &&
          readAt == other.readAt &&
          createdAt == other.createdAt;

  @override
  int get hashCode =>
      id.hashCode ^
      type.hashCode ^
      actorId.hashCode ^
      referenceId.hashCode ^
      reviewId.hashCode ^
      discussionId.hashCode ^
      readAt.hashCode ^
      createdAt.hashCode;
}
