package com.rewit.domain.model;

import com.rewit.common.exception.BusinessException;

import java.time.Instant;
import java.util.UUID;

/**
 * Entidade de Domínio representando notificações internas do sistema Rewit.
 * Independente e desacoplada de provedores externos de push/e-mail (Seção 28).
 */
public class Notification {

    private final UUID id;
    private final UUID userId;
    private final String notificationType;
    private final String title;
    private final String content;
    private final String actionUrl;
    private final String metadataJson;
    private Instant readAt;
    private final Instant createdAt;

    public Notification(UUID id, UUID userId, String notificationType,
                        String title, String content, String actionUrl, String metadataJson) {
        if (userId == null) {
            throw new BusinessException("O destinatário da notificação é obrigatório", "MISSING_USER_ID");
        }
        if (title == null || title.isBlank()) {
            throw new BusinessException("O título da notificação é obrigatório", "MISSING_TITLE");
        }

        this.id = id != null ? id : UUID.randomUUID();
        this.userId = userId;
        this.notificationType = notificationType != null ? notificationType : "SYSTEM";
        this.title = title.trim();
        this.content = content != null ? content.trim() : "";
        this.actionUrl = actionUrl;
        this.metadataJson = metadataJson;
        this.readAt = null;
        this.createdAt = Instant.now();
    }

    /**
     * Construtor de compatibilidade sem payload de metadados.
     */
    public Notification(UUID id, UUID userId, String notificationType,
                        String title, String content, String actionUrl) {
        this(id, userId, notificationType, title, content, actionUrl, null);
    }

    public void markAsRead() {
        this.readAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getNotificationType() {
        return notificationType;
    }

    public String getTitle() {
        return title;
    }

    public String getContent() {
        return content;
    }

    public String getActionUrl() {
        return actionUrl;
    }

    public String getMetadataJson() {
        return metadataJson;
    }

    public Instant getReadAt() {
        return readAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
