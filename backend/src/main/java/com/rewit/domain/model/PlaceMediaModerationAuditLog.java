package com.rewit.domain.model;

import com.rewit.domain.enums.PlaceMediaModerationAction;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Registro append-only da trilha de auditoria para ações administrativas sobre fotografias de locais.
 */
public class PlaceMediaModerationAuditLog {

    public enum ActorType {
        USER,
        SYSTEM
    }

    private final UUID id;
    private final UUID placeMediaId;
    private final UUID placeId;
    private final UUID actorUserId;
    private final ActorType actorType;
    private final PlaceMediaModerationAction action;
    private final String reason;
    private final String notes;
    private final UUID reportId;
    private final Instant createdAt;

    public PlaceMediaModerationAuditLog(UUID id, UUID placeMediaId, UUID placeId, UUID actorUserId,
                                        PlaceMediaModerationAction action, String reason, String notes,
                                        Instant createdAt) {
        this(id, placeMediaId, placeId, actorUserId, actorUserId != null ? ActorType.USER : ActorType.SYSTEM, action, reason, notes, null, createdAt);
    }

    public PlaceMediaModerationAuditLog(UUID id, UUID placeMediaId, UUID placeId, UUID actorUserId,
                                        PlaceMediaModerationAction action, String reason, String notes,
                                        UUID reportId, Instant createdAt) {
        this(id, placeMediaId, placeId, actorUserId, actorUserId != null ? ActorType.USER : ActorType.SYSTEM, action, reason, notes, reportId, createdAt);
    }

    public PlaceMediaModerationAuditLog(UUID id, UUID placeMediaId, UUID placeId, UUID actorUserId,
                                        ActorType actorType,
                                        PlaceMediaModerationAction action, String reason, String notes,
                                        UUID reportId, Instant createdAt) {
        this.id = id != null ? id : UUID.randomUUID();
        this.placeMediaId = Objects.requireNonNull(placeMediaId, "placeMediaId must not be null");
        this.placeId = Objects.requireNonNull(placeId, "placeId must not be null");
        this.actorType = actorType != null ? actorType : (actorUserId != null ? ActorType.USER : ActorType.SYSTEM);
        if (this.actorType == ActorType.USER) {
            this.actorUserId = Objects.requireNonNull(actorUserId, "actorUserId must not be null for USER actor type");
        } else {
            this.actorUserId = null;
        }
        this.action = Objects.requireNonNull(action, "action must not be null");
        this.reason = reason != null && !reason.isBlank() ? reason.trim() : null;
        this.notes = notes != null && !notes.isBlank() ? notes.trim() : null;
        this.reportId = reportId;
        this.createdAt = createdAt != null ? createdAt : Instant.now();
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

    public ActorType getActorType() {
        return actorType;
    }

    public boolean isSystemAction() {
        return actorType == ActorType.SYSTEM;
    }

    public PlaceMediaModerationAction getAction() {
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
