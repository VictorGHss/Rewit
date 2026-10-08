package com.rewit.application.dto.notification;

import java.time.Instant;
import java.util.UUID;

public final class NotificationDtos {

    private NotificationDtos() {}

    /**
     * @param reviewId     avaliação a que a notificação leva (REVIEW_HELPFUL, NEW_DISCUSSION, DISCUSSION_REPLY); null
     *                     nos demais tipos ou quando o metadata não a traz
     * @param discussionId comentário a que a notificação leva (NEW_DISCUSSION: o comentário raiz; DISCUSSION_REPLY: a
     *                     resposta); null nos demais tipos ou quando o metadata não o traz
     */
    public record NotificationView(
            UUID id,
            String type,
            UUID actorId,
            UUID referenceId,
            UUID reviewId,
            UUID discussionId,
            Instant readAt,
            Instant createdAt
    ) {}

    public record UnreadCountView(
            long count
    ) {}
}
