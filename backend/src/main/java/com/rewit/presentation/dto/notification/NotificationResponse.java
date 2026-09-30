package com.rewit.presentation.dto.notification;

import com.rewit.application.dto.notification.NotificationDtos.NotificationView;

import java.time.Instant;
import java.util.UUID;

/**
 * DTO de resposta HTTP para itens de notificação in-app (Step 22.0).
 */
public record NotificationResponse(
        UUID id,
        String type,
        UUID actorId,
        UUID referenceId,
        Instant readAt,
        Instant createdAt
) {
    public static NotificationResponse fromView(NotificationView view) {
        if (view == null) {
            return null;
        }
        return new NotificationResponse(
                view.id(),
                view.type(),
                view.actorId(),
                view.referenceId(),
                view.readAt(),
                view.createdAt()
        );
    }
}
