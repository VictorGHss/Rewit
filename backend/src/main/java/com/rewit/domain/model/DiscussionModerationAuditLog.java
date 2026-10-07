package com.rewit.domain.model;

import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.DiscussionModerationAction;
import com.rewit.domain.enums.DiscussionStatus;
import com.rewit.domain.enums.ModerationDecision;

import java.time.Instant;
import java.util.UUID;

/**
 * Registro imutável (append-only) de uma decisão de moderação sobre uma discussão.
 */
public final class DiscussionModerationAuditLog {

    private final UUID id;
    private final UUID discussionId;
    private final UUID moderatorUserId;
    private final DiscussionModerationAction action;
    private final ModerationDecision decision;
    private final String reasonCode;
    private final String justification;
    private final DiscussionStatus previousStatus;
    private final DiscussionStatus newStatus;
    private final int reportsAffectedCount;
    private final Instant createdAt;

    public DiscussionModerationAuditLog(UUID id, UUID discussionId, UUID moderatorUserId,
                                        DiscussionModerationAction action, ModerationDecision decision,
                                        String reasonCode, String justification,
                                        DiscussionStatus previousStatus, DiscussionStatus newStatus,
                                        int reportsAffectedCount, Instant createdAt) {
        if (discussionId == null) {
            throw new BusinessException("O identificador da discussão auditada é obrigatório", "MISSING_DISCUSSION_ID");
        }
        if (moderatorUserId == null) {
            throw new BusinessException("O identificador do moderador responsável é obrigatório", "MISSING_MODERATOR_ID");
        }
        if (action == null) {
            throw new BusinessException("A ação de moderação é obrigatória", "MISSING_MODERATION_ACTION");
        }
        if (decision == null) {
            throw new BusinessException("A decisão regulatória é obrigatória", "MISSING_MODERATION_DECISION");
        }
        if (reasonCode == null || reasonCode.isBlank()) {
            throw new BusinessException("O código do motivo da decisão é obrigatório", "MISSING_REASON_CODE");
        }
        if (justification == null || justification.isBlank()) {
            throw new BusinessException("A justificativa da decisão é obrigatória", "MISSING_JUSTIFICATION");
        }
        if (previousStatus == null || newStatus == null) {
            throw new BusinessException("Os status anterior e novo da discussão são obrigatórios", "MISSING_DISCUSSION_STATUS");
        }
        if (reportsAffectedCount < 0) {
            throw new BusinessException("A contagem de denúncias afetadas não pode ser negativa", "INVALID_REPORTS_COUNT");
        }
        this.id = id != null ? id : UUID.randomUUID();
        this.discussionId = discussionId;
        this.moderatorUserId = moderatorUserId;
        this.action = action;
        this.decision = decision;
        this.reasonCode = reasonCode.trim();
        this.justification = justification.trim();
        this.previousStatus = previousStatus;
        this.newStatus = newStatus;
        this.reportsAffectedCount = reportsAffectedCount;
        this.createdAt = createdAt != null ? createdAt : Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getDiscussionId() {
        return discussionId;
    }

    public UUID getModeratorUserId() {
        return moderatorUserId;
    }

    public DiscussionModerationAction getAction() {
        return action;
    }

    public ModerationDecision getDecision() {
        return decision;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public String getJustification() {
        return justification;
    }

    public DiscussionStatus getPreviousStatus() {
        return previousStatus;
    }

    public DiscussionStatus getNewStatus() {
        return newStatus;
    }

    public int getReportsAffectedCount() {
        return reportsAffectedCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
