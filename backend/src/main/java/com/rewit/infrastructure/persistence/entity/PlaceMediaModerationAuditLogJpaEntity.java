package com.rewit.infrastructure.persistence.entity;

import com.rewit.domain.enums.PlaceMediaModerationAction;
import com.rewit.domain.model.PlaceMediaModerationAuditLog;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Entidade JPA para mapeamento da tabela place_media_moderation_audit_logs (V32).
 */
@Entity
@Table(name = "place_media_moderation_audit_logs")
public class PlaceMediaModerationAuditLogJpaEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "place_media_id", nullable = false, updatable = false)
    private UUID placeMediaId;

    @Column(name = "place_id", nullable = false, updatable = false)
    private UUID placeId;

    @Column(name = "actor_user_id", nullable = false, updatable = false)
    private UUID actorUserId;

    @Column(name = "action", nullable = false, length = 32, updatable = false)
    private String action;

    @Column(name = "reason", length = 255, updatable = false)
    private String reason;

    @Column(name = "notes", updatable = false)
    private String notes;

    @Column(name = "report_id", updatable = false)
    private UUID reportId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public PlaceMediaModerationAuditLogJpaEntity() {
    }

    public PlaceMediaModerationAuditLogJpaEntity(UUID id, UUID placeMediaId, UUID placeId,
                                                UUID actorUserId, String action, String reason,
                                                String notes, UUID reportId, Instant createdAt) {
        this.id = id;
        this.placeMediaId = placeMediaId;
        this.placeId = placeId;
        this.actorUserId = actorUserId;
        this.action = action;
        this.reason = reason;
        this.notes = notes;
        this.reportId = reportId;
        this.createdAt = createdAt;
    }

    public static PlaceMediaModerationAuditLogJpaEntity fromDomain(PlaceMediaModerationAuditLog domain) {
        return new PlaceMediaModerationAuditLogJpaEntity(
                domain.getId(),
                domain.getPlaceMediaId(),
                domain.getPlaceId(),
                domain.getActorUserId(),
                domain.getAction().name(),
                domain.getReason(),
                domain.getNotes(),
                domain.getReportId(),
                domain.getCreatedAt()
        );
    }

    public PlaceMediaModerationAuditLog toDomain() {
        return new PlaceMediaModerationAuditLog(
                this.id,
                this.placeMediaId,
                this.placeId,
                this.actorUserId,
                PlaceMediaModerationAction.valueOf(this.action),
                this.reason,
                this.notes,
                this.reportId,
                this.createdAt
        );
    }

    public UUID getId() {
        return id;
    }

    public UUID getPlaceMediaId() {
        return placeMediaId;
    }

    public UUID getPlaceId() {
        return placeId;
    }

    public UUID getActorUserId() {
        return actorUserId;
    }

    public String getAction() {
        return action;
    }

    public String getReason() {
        return reason;
    }

    public String getNotes() {
        return notes;
    }

    public UUID getReportId() {
        return reportId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

