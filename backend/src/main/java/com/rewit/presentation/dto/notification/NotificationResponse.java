package com.rewit.presentation.dto.notification;

import com.rewit.application.dto.notification.NotificationDtos.NotificationView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * DTO de resposta HTTP para itens de notificação in-app (Step 22.0, C5.12).
 */
@Schema(description = "Representação de um item de notificação in-app")
public record NotificationResponse(
        @Schema(description = "Identificador único da notificação")
        UUID id,
        @Schema(description = "Tipo da notificação")
        String type,
        @Schema(description = "Identificador do usuário que realizou a ação (nullable)")
        UUID actorId,
        @Schema(description = "Identificador de referência da notificação (nullable)")
        UUID referenceId,
        @Schema(description = "Identificador da avaliação associada ao contexto de navegação (nullable)")
        UUID reviewId,
        @Schema(description = "Identificador do comentário ou resposta associada ao contexto de navegação (nullable)")
        UUID discussionId,
        @Schema(description = "Momento em que a notificação foi marcada como lida (nullable)")
        Instant readAt,
        @Schema(description = "Momento de criação da notificação")
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
                view.reviewId(),
                view.discussionId(),
                view.readAt(),
                view.createdAt()
        );
    }
}
