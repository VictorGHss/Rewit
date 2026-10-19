package com.rewit.domain.model;

import com.rewit.common.exception.BusinessException;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Entidade de Domínio representando uma denúncia de fotografia de local (Place).
 */
public class PlaceMediaReport {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_ACCEPTED = "ACCEPTED";
    public static final String STATUS_REJECTED = "REJECTED";

    private final UUID id;
    private final UUID placeMediaId;
    private final UUID reporterUserId;
    private final String reason;
    private final String detail;
    private String status;
    private final Instant createdAt;
    private Instant updatedAt;
    private UUID decidedByUserId;
    private Instant decidedAt;
    private String decisionNotes;

    public PlaceMediaReport(UUID id, UUID placeMediaId, UUID reporterUserId,
                            String reason, String detail) {
        this(id, placeMediaId, reporterUserId, reason, detail, STATUS_PENDING,
                Instant.now(), Instant.now(), null, null, null);
    }

    public PlaceMediaReport(UUID id, UUID placeMediaId, UUID reporterUserId,
                            String reason, String detail, String status,
                            Instant createdAt, Instant updatedAt,
                            UUID decidedByUserId, Instant decidedAt, String decisionNotes) {
        this.id = id != null ? id : UUID.randomUUID();
        this.placeMediaId = Objects.requireNonNull(placeMediaId, "placeMediaId must not be null");
        this.reporterUserId = Objects.requireNonNull(reporterUserId, "reporterUserId must not be null");
        if (reason == null || reason.isBlank()) {
            throw new BusinessException("O motivo da denúncia é obrigatório", HttpStatus.BAD_REQUEST, "MISSING_REPORT_REASON");
        }
        this.reason = reason.trim();
        this.detail = detail != null ? detail.trim() : null;
        this.status = status != null ? status : STATUS_PENDING;
        this.createdAt = createdAt != null ? createdAt : Instant.now();
        this.updatedAt = updatedAt != null ? updatedAt : Instant.now();
        this.decidedByUserId = decidedByUserId;
        this.decidedAt = decidedAt;
        this.decisionNotes = decisionNotes != null ? decisionNotes.trim() : null;
    }

    public boolean isPending() {
        return STATUS_PENDING.equals(this.status);
    }

    public boolean isAccepted() {
        return STATUS_ACCEPTED.equals(this.status);
    }

    public boolean isRejected() {
        return STATUS_REJECTED.equals(this.status);
    }

    public void accept(UUID moderatorUserId, String notes) {
        this.status = STATUS_ACCEPTED;
        this.decidedByUserId = Objects.requireNonNull(moderatorUserId, "moderatorUserId must not be null");
        this.decidedAt = Instant.now();
        this.updatedAt = this.decidedAt;
        this.decisionNotes = notes != null ? notes.trim() : null;
    }

    public void reject(UUID moderatorUserId, String notes) {
        this.status = STATUS_REJECTED;
        this.decidedByUserId = Objects.requireNonNull(moderatorUserId, "moderatorUserId must not be null");
        this.decidedAt = Instant.now();
        this.updatedAt = this.decidedAt;
        this.decisionNotes = notes != null ? notes.trim() : null;
    }

    public UUID getId() {
        return id;
    }

    public UUID getPlaceMediaId() {
        return placeMediaId;
    }

    public UUID getReporterUserId() {
        return reporterUserId;
    }

    public String getReason() {
        return reason;
    }

    public String getDetail() {
        return detail;
    }

    public String getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public UUID getDecidedByUserId() {
        return decidedByUserId;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public String getDecisionNotes() {
        return decisionNotes;
    }
}
