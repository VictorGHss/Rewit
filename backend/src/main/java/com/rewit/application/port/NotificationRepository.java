package com.rewit.application.port;

import com.rewit.application.dto.common.PageResult;
import com.rewit.domain.model.Notification;

import java.util.Optional;
import java.util.UUID;

/**
 * Porta de persistência para notificações internas in-app (Step 22.0).
 */
public interface NotificationRepository {

    Notification save(Notification notification);

    Optional<Notification> findById(UUID id);

    Optional<Notification> findByIdAndUserId(UUID id, UUID userId);

    PageResult<Notification> findByUserId(UUID userId, int page, int size);

    long countUnreadByUserId(UUID userId);

    int markAllAsReadByUserId(UUID userId);
}
