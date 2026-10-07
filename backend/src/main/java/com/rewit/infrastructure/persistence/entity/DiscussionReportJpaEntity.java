package com.rewit.infrastructure.persistence.entity;

import com.rewit.domain.enums.ReportReason;
import com.rewit.domain.enums.ReportStatus;
import com.rewit.domain.model.DiscussionReport;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Mapeamento JPA de {@code discussion_reports} (V19).
 */
@Entity
@Table(name = "discussion_reports")
public class DiscussionReportJpaEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "discussion_id", nullable = false, updatable = false)
    private UUID discussionId;

    @Column(name = "reporter_user_id", nullable = false, updatable = false)
    private UUID reporterUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, updatable = false, length = 64)
    private ReportReason reason;

    @Column(name = "detail", columnDefinition = "TEXT", updatable = false)
    private String detail;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private ReportStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected DiscussionReportJpaEntity() {
    }

    public static DiscussionReportJpaEntity fromDomain(DiscussionReport report) {
        DiscussionReportJpaEntity entity = new DiscussionReportJpaEntity();
        entity.id = report.getId();
        entity.discussionId = report.getDiscussionId();
        entity.reporterUserId = report.getReporterUserId();
        entity.reason = report.getReason();
        entity.detail = report.getDetail();
        entity.status = report.getStatus();
        entity.createdAt = report.getCreatedAt();
        entity.updatedAt = report.getUpdatedAt();
        return entity;
    }

    public DiscussionReport toDomain() {
        return new DiscussionReport(id, discussionId, reporterUserId, reason, detail, status, createdAt, updatedAt);
    }

    public UUID getId() {
        return id;
    }

    public UUID getDiscussionId() {
        return discussionId;
    }

    public ReportStatus getStatus() {
        return status;
    }
}
