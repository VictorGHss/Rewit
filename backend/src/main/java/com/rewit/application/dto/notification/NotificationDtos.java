package com.rewit.application.dto.notification;

import java.time.Instant;
import java.util.UUID;

public final class NotificationDtos {

    private NotificationDtos() {}

    public record NotificationView(
            UUID id,
            String type,
            UUID actorId,
            UUID referenceId,
            Instant readAt,
            Instant createdAt
    ) {}

    public record UnreadCountView(
            long count
    ) {}
}
