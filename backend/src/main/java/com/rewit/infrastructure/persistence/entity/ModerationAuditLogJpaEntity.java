package com.rewit.infrastructure.persistence.entity;

import com.rewit.domain.enums.ModerationAction;
import com.rewit.domain.enums.ModerationDecision;
import com.rewit.domain.enums.ReviewStatus;
import com.rewit.domain.model.ModerationAuditLog;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Entidade JPA mapeando a tabela moderation_audit_logs no PostgreSQL (Step 26.1).
 */
@Entity
@Table(name = "moderation_audit_logs")
public class ModerationAuditLogJpaEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "review_id", nullable = false, updatable = false)
    private UUID reviewId;

    @Column(name = "moderator_user_id", nullable = false, updatable = false)
    private UUID moderatorUserId;

    @Column(name = "action", nullable = false, updatable = false, length = 32)
    private String action;

    @Column(name = "decision", nullable = false, updatable = false, length = 32)
    private String decision;

    @Column(name = "reason_code", nullable = false, updatable = false, length = 64)
    private String reasonCode;

    @Column(name = "justification", nullable = false, updatable = false, columnDefinition = "TEXT")
    private String justification;

    @Column(name = "previous_review_status", nullable = false, updatable = false, length = 32)
    private String previousReviewStatus;

    @Column(name = "new_review_status", nullable = false, updatable = false, length = 32)
    private String newReviewStatus;

    @Column(name = "reports_affected_count", nullable = false, updatable = false)
    private int reportsAffectedCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public ModerationAuditLogJpaEntity() {}

    public ModerationAuditLogJpaEntity(UUID id, UUID reviewId, UUID moderatorUserId,
                                       String action, String decision, String reasonCode,
                                       String justification, String previousReviewStatus,
                                       String newReviewStatus, int reportsAffectedCount,
                                       Instant createdAt) {
        this.id = id != null ? id : UUID.randomUUID();
        this.reviewId = reviewId;
        this.moderatorUserId = moderatorUserId;
        this.action = action;
        this.decision = decision;
        this.reasonCode = reasonCode;
        this.justification = justification;
        this.previousReviewStatus = previousReviewStatus;
        this.newReviewStatus = newReviewStatus;
        this.reportsAffectedCount = reportsAffectedCount;
        this.createdAt = createdAt != null ? createdAt : Instant.now();
    }

    public static ModerationAuditLogJpaEntity fromDomain(ModerationAuditLog domain) {
        if (domain == null) {
            return null;
        }
        return new ModerationAuditLogJpaEntity(
                domain.getId(),
                domain.getReviewId(),
                domain.getModeratorUserId(),
                domain.getAction().name(),
                domain.getDecision().name(),
                domain.getReasonCode(),
                domain.getJustification(),
                domain.getPreviousReviewStatus().name(),
                domain.getNewReviewStatus().name(),
                domain.getReportsAffectedCount(),
                domain.getCreatedAt()
        );
    }

    public ModerationAuditLog toDomain() {
        return new ModerationAuditLog(
                this.id,
                this.reviewId,
                this.moderatorUserId,
                ModerationAction.valueOf(this.action),
                ModerationDecision.valueOf(this.decision),
                this.reasonCode,
                this.justification,
                ReviewStatus.valueOf(this.previousReviewStatus),
                ReviewStatus.valueOf(this.newReviewStatus),
                this.reportsAffectedCount,
                this.createdAt
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

    public UUID getModeratorUserId() {
        return moderatorUserId;
    }

    public void setModeratorUserId(UUID moderatorUserId) {
        this.moderatorUserId = moderatorUserId;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getDecision() {
        return decision;
    }

    public void setDecision(String decision) {
        this.decision = decision;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public void setReasonCode(String reasonCode) {
        this.reasonCode = reasonCode;
    }

    public String getJustification() {
        return justification;
    }

    public void setJustification(String justification) {
        this.justification = justification;
    }

    public String getPreviousReviewStatus() {
        return previousReviewStatus;
    }

    public void setPreviousReviewStatus(String previousReviewStatus) {
        this.previousReviewStatus = previousReviewStatus;
    }

    public String getNewReviewStatus() {
        return newReviewStatus;
    }

    public void setNewReviewStatus(String newReviewStatus) {
        this.newReviewStatus = newReviewStatus;
    }

    public int getReportsAffectedCount() {
        return reportsAffectedCount;
    }

    public void setReportsAffectedCount(int reportsAffectedCount) {
        this.reportsAffectedCount = reportsAffectedCount;
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
        ModerationAuditLogJpaEntity that = (ModerationAuditLogJpaEntity) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
