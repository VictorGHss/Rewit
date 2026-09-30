package com.rewit.infrastructure.persistence.entity;

import com.rewit.domain.enums.ReportReason;
import com.rewit.domain.enums.ReportStatus;
import com.rewit.domain.model.Report;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Entidade JPA mapeando a tabela review_reports no PostgreSQL (Step 19.0).
 */
@Entity
@Table(name = "review_reports")
public class ReportJpaEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "review_id", nullable = false, updatable = false)
    private UUID reviewId;

    @Column(name = "reporter_user_id", nullable = false, updatable = false)
    private UUID reporterUserId;

    @Column(name = "reason", nullable = false, length = 64)
    private String reason;

    @Column(name = "detail", length = 500)
    private String detail;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public ReportJpaEntity() {}

    public ReportJpaEntity(UUID id, UUID reviewId, UUID reporterUserId, String reason,
                           String detail, String status, Instant createdAt, Instant updatedAt) {
        this.id = id != null ? id : UUID.randomUUID();
        this.reviewId = reviewId;
        this.reporterUserId = reporterUserId;
        this.reason = reason;
        this.detail = detail;
        this.status = status != null ? status : "PENDING";
        this.createdAt = createdAt != null ? createdAt : Instant.now();
        this.updatedAt = updatedAt != null ? updatedAt : Instant.now();
    }

    public static ReportJpaEntity fromDomain(Report domain) {
        if (domain == null) {
            return null;
        }
        return new ReportJpaEntity(
                domain.getId(),
                domain.getReviewId(),
                domain.getReporterUserId(),
                domain.getReason().name(),
                domain.getDetail(),
                domain.getStatus().name(),
                domain.getCreatedAt(),
                domain.getUpdatedAt()
        );
    }

    public Report toDomain() {
        return new Report(
                this.id,
                this.reviewId,
                this.reporterUserId,
                ReportReason.valueOf(this.reason),
                this.detail,
                ReportStatus.valueOf(this.status),
                this.createdAt,
                this.updatedAt
        );
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getReviewId() {
        return reviewId;
    }

    public void setReviewId(UUID reviewId) {
        this.reviewId = reviewId;
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ReportJpaEntity that = (ReportJpaEntity) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
