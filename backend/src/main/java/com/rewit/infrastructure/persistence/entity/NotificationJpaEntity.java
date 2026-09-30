package com.rewit.infrastructure.persistence.entity;

import com.rewit.domain.model.Notification;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Entidade JPA mapeando a tabela notifications no PostgreSQL (Step 22.0).
 */
@Entity
@Table(name = "notifications")
public class NotificationJpaEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "notification_type", nullable = false, length = 64)
    private String notificationType;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "action_url", columnDefinition = "TEXT")
    private String actionUrl;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata_json", columnDefinition = "jsonb")
    private String metadataJson;

    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public NotificationJpaEntity() {}

    public NotificationJpaEntity(UUID id, UUID userId, String notificationType,
                                 String title, String content, String actionUrl,
                                 String metadataJson, Instant readAt, Instant createdAt) {
        this.id = id != null ? id : UUID.randomUUID();
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        this.notificationType = Objects.requireNonNull(notificationType, "notificationType must not be null");
        this.title = Objects.requireNonNull(title, "title must not be null");
        this.content = content != null ? content : "";
        this.actionUrl = actionUrl;
        this.metadataJson = metadataJson;
        this.readAt = readAt;
        this.createdAt = createdAt != null ? createdAt : Instant.now();
    }

    public static NotificationJpaEntity fromDomain(Notification domain) {
        if (domain == null) {
            return null;
        }
        return new NotificationJpaEntity(
                domain.getId(),
                domain.getUserId(),
                domain.getNotificationType(),
                domain.getTitle(),
                domain.getContent(),
                domain.getActionUrl(),
                domain.getMetadataJson(),
                domain.getReadAt(),
                domain.getCreatedAt()
        );
    }

    public Notification toDomain() {
        return new Notification(
                this.id,
                this.userId,
                this.notificationType,
                this.title,
                this.content,
                this.actionUrl,
                this.metadataJson,
                this.readAt,
                this.createdAt
        );
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public String getNotificationType() {
        return notificationType;
    }

    public void setNotificationType(String notificationType) {
        this.notificationType = notificationType;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getActionUrl() {
        return actionUrl;
    }

    public void setActionUrl(String actionUrl) {
        this.actionUrl = actionUrl;
    }

    public String getMetadataJson() {
        return metadataJson;
    }

    public void setMetadataJson(String metadataJson) {
        this.metadataJson = metadataJson;
    }

    public Instant getReadAt() {
        return readAt;
    }

    public void setReadAt(Instant readAt) {
        this.readAt = readAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        NotificationJpaEntity that = (NotificationJpaEntity) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
