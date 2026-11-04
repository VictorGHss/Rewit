import 'package:flutter/material.dart';
import 'package:rewit_mobile/app/theme/rewit_semantic_colors.dart';
import '../../domain/entities/in_app_notification.dart';

/// Card para renderização de um item de notificação in-app.
class NotificationItemCard extends StatelessWidget {
  final InAppNotification notification;
  final VoidCallback onTap;

  const NotificationItemCard({
    super.key,
    required this.notification,
    required this.onTap,
  });

  String _formatDate(DateTime date) {
    final now = DateTime.now();
    final difference = now.difference(date);

    if (difference.inMinutes < 1) {
      return 'Agora mesmo';
    } else if (difference.inMinutes < 60) {
      return 'Há ${difference.inMinutes} min';
    } else if (difference.inHours < 24) {
      return 'Há ${difference.inHours} h';
    } else if (difference.inDays < 7) {
      return 'Há ${difference.inDays} d';
    } else {
      return '${date.day.toString().padLeft(2, '0')}/${date.month.toString().padLeft(2, '0')}/${date.year}';
    }
  }

  IconData _getIcon() {
    switch (notification.type) {
      case NotificationType.newFollower:
        return Icons.person_add_rounded;
      case NotificationType.reviewHelpful:
        return Icons.thumb_up_alt_rounded;
      case NotificationType.newDiscussion:
        return Icons.forum_rounded;
      case NotificationType.discussionReply:
        return Icons.reply_rounded;
    }
  }

  RewitSemanticTone _getTone(RewitSemanticColors semantic) {
    switch (notification.type) {
      case NotificationType.newFollower:
        return semantic.info;
      case NotificationType.reviewHelpful:
        return semantic.ratingBadge;
      case NotificationType.newDiscussion:
        return semantic.verified;
      case NotificationType.discussionReply:
        return semantic.placeTarget;
    }
  }

  String _getTitle() {
    switch (notification.type) {
      case NotificationType.newFollower:
        return 'Novo seguidor';
      case NotificationType.reviewHelpful:
        return 'Avaliação útil';
      case NotificationType.newDiscussion:
        return 'Nova discussão';
      case NotificationType.discussionReply:
        return 'Resposta em discussão';
    }
  }

  String _getDescription() {
    switch (notification.type) {
      case NotificationType.newFollower:
        return 'Um membro da comunidade começou a seguir seu perfil.';
      case NotificationType.reviewHelpful:
        return 'Sua avaliação foi marcada como útil pela comunidade.';
      case NotificationType.newDiscussion:
        return 'Iniciaram uma nova discussão na sua avaliação.';
      case NotificationType.discussionReply:
        return 'Responderam a uma discussão em que você participou.';
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colorScheme = theme.colorScheme;
    final semantic = RewitSemanticColors.of(context);
    final tone = _getTone(semantic);
    final isUnread = !notification.isRead;

    return InkWell(
      onTap: onTap,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
        decoration: BoxDecoration(
          color: isUnread ? colorScheme.surfaceContainerLow : null,
          border: Border(
            bottom: BorderSide(
              color: colorScheme.outlineVariant,
              width: 0.8,
            ),
          ),
        ),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // Ícone temático da notificação
            CircleAvatar(
              radius: 20,
              backgroundColor: tone.container,
              child: Icon(
                _getIcon(),
                size: 20,
                color: tone.accent,
              ),
            ),
            const SizedBox(width: 12),

            // Conteúdo textual
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      Expanded(
                        child: Text(
                          _getTitle(),
                          style: theme.textTheme.titleSmall?.copyWith(
                            fontWeight:
                                isUnread ? FontWeight.bold : FontWeight.w600,
                          ),
                        ),
                      ),
                      Text(
                        _formatDate(notification.createdAt),
                        style: theme.textTheme.bodySmall?.copyWith(
                          color: colorScheme.onSurfaceVariant,
                          fontSize: 11,
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 4),
                  Text(
                    _getDescription(),
                    style: theme.textTheme.bodyMedium?.copyWith(
                      color: isUnread
                          ? colorScheme.onSurface
                          : colorScheme.onSurfaceVariant,
                      fontSize: 13,
                    ),
                  ),
                ],
              ),
            ),

            // Indicador de não lida
            if (isUnread) ...[
              const SizedBox(width: 8),
              Padding(
                padding: const EdgeInsets.only(top: 6),
                child: Container(
                  width: 8,
                  height: 8,
                  decoration: BoxDecoration(
                    color: colorScheme.primary,
                    shape: BoxShape.circle,
                  ),
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }
}
