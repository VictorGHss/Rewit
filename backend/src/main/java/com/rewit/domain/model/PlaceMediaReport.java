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
    public static final String STATUS_AUTO_RESOLVED = "AUTO_RESOLVED";

    /** Decisão humana com moderador comprovado na trilha de auditoria. */
    public static final String DECISION_ORIGIN_MODERATOR = "MODERATOR";
    /** Resolução automática do sistema, sem decisor humano. */
    public static final String DECISION_ORIGIN_SYSTEM = "SYSTEM";
    /** Decisão histórica sem moderador verificável (fixada pela migração V36). */
    public static final String DECISION_ORIGIN_LEGACY = "LEGACY";

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
    private final boolean legacyDecision;
    private String authenticityMac;

    public PlaceMediaReport(UUID id, UUID placeMediaId, UUID reporterUserId,
                            String reason, String detail) {
        this(id, placeMediaId, reporterUserId, reason, detail, STATUS_PENDING,
                Instant.now(), Instant.now(), null, null, null, false, null);
    }

    public PlaceMediaReport(UUID id, UUID placeMediaId, UUID reporterUserId,
                            String reason, String detail, String status,
                            Instant createdAt, Instant updatedAt,
                            UUID decidedByUserId, Instant decidedAt, String decisionNotes) {
        this(id, placeMediaId, reporterUserId, reason, detail, status,
                createdAt, updatedAt, decidedByUserId, decidedAt, decisionNotes, false, null);
    }

    public PlaceMediaReport(UUID id, UUID placeMediaId, UUID reporterUserId,
                            String reason, String detail, String status,
                            Instant createdAt, Instant updatedAt,
                            UUID decidedByUserId, Instant decidedAt, String decisionNotes,
                            boolean legacyDecision) {
        this(id, placeMediaId, reporterUserId, reason, detail, status,
                createdAt, updatedAt, decidedByUserId, decidedAt, decisionNotes, legacyDecision, null);
    }

    public PlaceMediaReport(UUID id, UUID placeMediaId, UUID reporterUserId,
                            String reason, String detail, String status,
                            Instant createdAt, Instant updatedAt,
                            UUID decidedByUserId, Instant decidedAt, String decisionNotes,
                            boolean legacyDecision, String authenticityMac) {
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
        this.legacyDecision = legacyDecision;
        this.authenticityMac = authenticityMac;
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

    public boolean isAutoResolved() {
        return STATUS_AUTO_RESOLVED.equals(this.status);
    }

    public void autoResolve(String notes) {
        this.status = STATUS_AUTO_RESOLVED;
        this.decidedByUserId = null;
        this.decidedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        this.updatedAt = this.decidedAt;
        this.decisionNotes = notes != null ? notes.trim() : null;
        this.authenticityMac = null;
    }

    public void accept(UUID moderatorUserId, String notes) {
        accept(moderatorUserId, notes, Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS), null);
    }

    public void accept(UUID moderatorUserId, String notes, Instant decidedAt, String authenticityMac) {
        this.status = STATUS_ACCEPTED;
        this.decidedByUserId = Objects.requireNonNull(moderatorUserId, "moderatorUserId must not be null");
        this.decidedAt = (decidedAt != null ? decidedAt : Instant.now()).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        this.updatedAt = this.decidedAt;
        this.decisionNotes = notes != null ? notes.trim() : null;
        this.authenticityMac = authenticityMac;
    }

    public void reject(UUID moderatorUserId, String notes) {
        reject(moderatorUserId, notes, Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS), null);
    }

    public void reject(UUID moderatorUserId, String notes, Instant decidedAt, String authenticityMac) {
        this.status = STATUS_REJECTED;
        this.decidedByUserId = Objects.requireNonNull(moderatorUserId, "moderatorUserId must not be null");
        this.decidedAt = (decidedAt != null ? decidedAt : Instant.now()).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        this.updatedAt = this.decidedAt;
        this.decisionNotes = notes != null ? notes.trim() : null;
        this.authenticityMac = authenticityMac;
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

    public boolean isLegacyDecision() {
        return legacyDecision;
    }

    public String getAuthenticityMac() {
        return authenticityMac;
    }

    /**
     * Proveniência da decisão, ou {@code null} enquanto a denúncia está pendente.
     * Decisões legadas nunca são apresentadas como decisão de moderador.
     */
    public String getDecisionOrigin() {
        if (isPending()) {
            return null;
        }
        if (isAutoResolved()) {
            return DECISION_ORIGIN_SYSTEM;
        }
        return legacyDecision ? DECISION_ORIGIN_LEGACY : DECISION_ORIGIN_MODERATOR;
    }

    /**
     * Moderador responsável somente quando a decisão humana é verificável. Em decisões legadas o
     * valor eventualmente registrado não é confirmado pela trilha de auditoria e não é exposto.
     */
    public UUID getVerifiedDeciderUserId() {
        return DECISION_ORIGIN_MODERATOR.equals(getDecisionOrigin()) ? decidedByUserId : null;
    }
}
