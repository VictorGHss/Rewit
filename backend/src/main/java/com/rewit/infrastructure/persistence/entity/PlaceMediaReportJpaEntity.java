package com.rewit.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Entidade JPA para denúncias de fotografias de locais (V31).
 */
@Entity
@Table(name = "place_media_reports")
public class PlaceMediaReportJpaEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "place_media_id", nullable = false, updatable = false)
    private UUID placeMediaId;

    @Column(name = "reporter_user_id", nullable = false, updatable = false)
    private UUID reporterUserId;

    @Column(name = "reason", nullable = false, length = 64)
    private String reason;

    @Column(name = "detail", length = 500)
    private String detail;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "decided_by_user_id")
    private UUID decidedByUserId;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decision_notes", length = 500)
    private String decisionNotes;

    // Condição histórica fixada pela migração V36 (LEG-1): somente leitura para o runtime, que não tem
    // privilégio de INSERT/UPDATE nesta coluna. Novas linhas recebem o DEFAULT FALSE do banco.
    @Column(name = "is_legacy_decision", nullable = false, insertable = false, updatable = false)
    private boolean isLegacyDecision = false;

    public PlaceMediaReportJpaEntity() {
    }

    public PlaceMediaReportJpaEntity(UUID id, UUID placeMediaId, UUID reporterUserId,
                                     String reason, String detail, String status,
                                     Instant createdAt, Instant updatedAt) {
        this(id, placeMediaId, reporterUserId, reason, detail, status, createdAt, updatedAt, null, null, null, false);
    }

    public PlaceMediaReportJpaEntity(UUID id, UUID placeMediaId, UUID reporterUserId,
                                     String reason, String detail, String status,
                                     Instant createdAt, Instant updatedAt,
                                     UUID decidedByUserId, Instant decidedAt, String decisionNotes) {
        this(id, placeMediaId, reporterUserId, reason, detail, status,
                createdAt, updatedAt, decidedByUserId, decidedAt, decisionNotes, false);
    }

    public PlaceMediaReportJpaEntity(UUID id, UUID placeMediaId, UUID reporterUserId,
                                     String reason, String detail, String status,
                                     Instant createdAt, Instant updatedAt,
                                     UUID decidedByUserId, Instant decidedAt, String decisionNotes,
                                     boolean isLegacyDecision) {
        this.id = id != null ? id : UUID.randomUUID();
        this.placeMediaId = placeMediaId;
        this.reporterUserId = reporterUserId;
        this.reason = reason;
        this.detail = detail;
        this.status = status != null ? status : "PENDING";
        this.createdAt = createdAt != null ? createdAt : Instant.now();
        this.updatedAt = updatedAt != null ? updatedAt : Instant.now();
        this.decidedByUserId = decidedByUserId;
        this.decidedAt = decidedAt;
        this.decisionNotes = decisionNotes;
        this.isLegacyDecision = isLegacyDecision;
    }

    public static PlaceMediaReportJpaEntity fromDomain(com.rewit.domain.model.PlaceMediaReport domain) {
        if (domain == null) return null;
        return new PlaceMediaReportJpaEntity(
                domain.getId(),
                domain.getPlaceMediaId(),
                domain.getReporterUserId(),
                domain.getReason(),
                domain.getDetail(),
                domain.getStatus(),
                domain.getCreatedAt(),
                domain.getUpdatedAt(),
                domain.getDecidedByUserId(),
                domain.getDecidedAt(),
                domain.getDecisionNotes(),
                domain.isLegacyDecision()
        );
    }

    public com.rewit.domain.model.PlaceMediaReport toDomain() {
        return new com.rewit.domain.model.PlaceMediaReport(
                this.id,
                this.placeMediaId,
                this.reporterUserId,
                this.reason,
                this.detail,
                this.status,
                this.createdAt,
                this.updatedAt,
                this.decidedByUserId,
                this.decidedAt,
                this.decisionNotes,
                this.isLegacyDecision
        );
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getPlaceMediaId() {
        return placeMediaId;
    }

    public void setPlaceMediaId(UUID placeMediaId) {
        this.placeMediaId = placeMediaId;
    }

    public UUID getReporterUserId() {
        return reporterUserId;
    }

    public void setReporterUserId(UUID reporterUserId) {
        this.reporterUserId = reporterUserId;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public UUID getDecidedByUserId() {
        return decidedByUserId;
    }

    public void setDecidedByUserId(UUID decidedByUserId) {
        this.decidedByUserId = decidedByUserId;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public void setDecidedAt(Instant decidedAt) {
        this.decidedAt = decidedAt;
    }

    public String getDecisionNotes() {
        return decisionNotes;
    }

    public void setDecisionNotes(String decisionNotes) {
        this.decisionNotes = decisionNotes;
    }

    public boolean isLegacyDecision() {
        return isLegacyDecision;
    }

    public void setLegacyDecision(boolean legacyDecision) {
        this.isLegacyDecision = legacyDecision;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PlaceMediaReportJpaEntity that = (PlaceMediaReportJpaEntity) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}

