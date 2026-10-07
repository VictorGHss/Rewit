package com.rewit.domain.model;

import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.ModerationAction;
import com.rewit.domain.enums.ModerationDecision;
import com.rewit.domain.enums.ReviewStatus;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Entidade de Domínio imutável representando um registro append-only de decisão de moderação (Step 26.1).
 */
public class ModerationAuditLog {

    /** Tamanho de {@code moderation_audit_logs.reason_code} (V11): validação e persistência usam o mesmo limite. */
    public static final int MAX_REASON_CODE_LENGTH = 64;

    private final UUID id;
    private final UUID reviewId;
    private final UUID moderatorUserId;
    private final ModerationAction action;
    private final ModerationDecision decision;
    private final String reasonCode;
    private final String justification;
    private final ReviewStatus previousReviewStatus;
    private final ReviewStatus newReviewStatus;
    private final int reportsAffectedCount;
    private final Instant createdAt;

    public ModerationAuditLog(UUID id, UUID reviewId, UUID moderatorUserId,
                              ModerationAction action, ModerationDecision decision,
                              String reasonCode, String justification,
                              ReviewStatus previousReviewStatus, ReviewStatus newReviewStatus,
                              int reportsAffectedCount, Instant createdAt) {
        if (reviewId == null) {
            throw new BusinessException("O identificador da avaliação auditada é obrigatório", "MISSING_REVIEW_ID");
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
        if (reasonCode != null && reasonCode.trim().length() > MAX_REASON_CODE_LENGTH) {
            throw new BusinessException("O código do motivo não pode exceder 64 caracteres", "INVALID_REASON_CODE_LENGTH");
        }
        if (reasonCode == null || reasonCode.isBlank()) {
            throw new BusinessException("O código do motivo da decisão é obrigatório", "MISSING_REASON_CODE");
        }
        if (justification == null) {
            throw new BusinessException("A justificativa da decisão é obrigatória", "MISSING_JUSTIFICATION");
        }
        if (justification.isBlank()) {
            throw new BusinessException("A justificativa da decisão não pode ser vazia", "BLANK_JUSTIFICATION");
        }
        if (previousReviewStatus == null) {
            throw new BusinessException("O status anterior da avaliação é obrigatório", "MISSING_PREVIOUS_STATUS");
        }
        if (newReviewStatus == null) {
            throw new BusinessException("O novo status da avaliação é obrigatório", "MISSING_NEW_STATUS");
        }
        if (reportsAffectedCount < 0) {
            throw new BusinessException("A contagem de denúncias afetadas não pode ser negativa", "INVALID_REPORTS_COUNT");
        }

        this.id = id != null ? id : UUID.randomUUID();
        this.reviewId = reviewId;
        this.moderatorUserId = moderatorUserId;
        this.action = action;
        this.decision = decision;
        this.reasonCode = reasonCode.trim();
        this.justification = justification.trim();
        this.previousReviewStatus = previousReviewStatus;
        this.newReviewStatus = newReviewStatus;
        this.reportsAffectedCount = reportsAffectedCount;
        this.createdAt = createdAt != null ? createdAt : Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getReviewId() {
        return reviewId;
    }

    public UUID getModeratorUserId() {
        return moderatorUserId;
    }

    public ModerationAction getAction() {
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

    public ReviewStatus getPreviousReviewStatus() {
        return previousReviewStatus;
    }

    public ReviewStatus getNewReviewStatus() {
        return newReviewStatus;
    }

    public int getReportsAffectedCount() {
        return reportsAffectedCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ModerationAuditLog that = (ModerationAuditLog) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
