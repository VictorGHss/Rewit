package com.rewit.infrastructure.persistence.entity;

import com.rewit.domain.enums.DiscussionModerationAction;
import com.rewit.domain.enums.DiscussionStatus;
import com.rewit.domain.enums.ModerationDecision;
import com.rewit.domain.model.DiscussionModerationAuditLog;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Mapeamento JPA de {@code discussion_moderation_audit_logs} (V20). Todas as colunas são não atualizáveis;
 * o banco também rejeita UPDATE ({@code trg_prevent_discussion_moderation_audit_update}).
 */
@Entity
@Table(name = "discussion_moderation_audit_logs")
public class DiscussionModerationAuditLogJpaEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "discussion_id", nullable = false, updatable = false)
    private UUID discussionId;

    @Column(name = "moderator_user_id", nullable = false, updatable = false)
    private UUID moderatorUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, updatable = false, length = 32)
    private DiscussionModerationAction action;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision", nullable = false, updatable = false, length = 32)
    private ModerationDecision decision;

    @Column(name = "reason_code", nullable = false, updatable = false, length = 64)
    private String reasonCode;

    @Column(name = "justification", nullable = false, updatable = false, columnDefinition = "TEXT")
    private String justification;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_status", nullable = false, updatable = false, length = 32)
    private DiscussionStatus previousStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_status", nullable = false, updatable = false, length = 32)
    private DiscussionStatus newStatus;

    @Column(name = "reports_affected_count", nullable = false, updatable = false)
    private int reportsAffectedCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected DiscussionModerationAuditLogJpaEntity() {
    }

    public static DiscussionModerationAuditLogJpaEntity fromDomain(DiscussionModerationAuditLog log) {
        DiscussionModerationAuditLogJpaEntity entity = new DiscussionModerationAuditLogJpaEntity();
        entity.id = log.getId();
        entity.discussionId = log.getDiscussionId();
        entity.moderatorUserId = log.getModeratorUserId();
        entity.action = log.getAction();
        entity.decision = log.getDecision();
        entity.reasonCode = log.getReasonCode();
        entity.justification = log.getJustification();
        entity.previousStatus = log.getPreviousStatus();
        entity.newStatus = log.getNewStatus();
        entity.reportsAffectedCount = log.getReportsAffectedCount();
        entity.createdAt = log.getCreatedAt();
        return entity;
    }

    public DiscussionModerationAuditLog toDomain() {
        return new DiscussionModerationAuditLog(id, discussionId, moderatorUserId, action, decision, reasonCode,
                justification, previousStatus, newStatus, reportsAffectedCount, createdAt);
    }
}
