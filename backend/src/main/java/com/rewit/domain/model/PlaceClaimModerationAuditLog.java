package com.rewit.domain.model;

import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.PlaceClaimStatus;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/**
 * Registro append-only desacoplado e criptograficamente autenticado de uma decisão administrativa
 * sobre reivindicação de estabelecimento ({@code place_claim_moderation_audit_logs}, V45 / S3-A13-F2).
 */
public final class PlaceClaimModerationAuditLog {

    public enum Action {
        APPROVE_CLAIM,
        REJECT_CLAIM
    }

    private final UUID id;
    private final UUID claimId;
    private final UUID placeId;
    private final UUID businessAccountId;
    private final UUID moderatorUserId;
    private final Action action;
    private final PlaceClaimStatus previousStatus;
    private final PlaceClaimStatus newStatus;
    private final String decisionReason;
    private final Instant createdAt;
    private final String authenticityMac;

    public PlaceClaimModerationAuditLog(
            UUID id,
            UUID claimId,
            UUID placeId,
            UUID businessAccountId,
            UUID moderatorUserId,
            Action action,
            PlaceClaimStatus previousStatus,
            PlaceClaimStatus newStatus,
            String decisionReason,
            Instant createdAt,
            String authenticityMac
    ) {
        if (claimId == null) {
            throw new BusinessException("O identificador da reivindicação é obrigatório", "MISSING_PLACE_CLAIM_ID");
        }
        if (placeId == null) {
            throw new BusinessException("O identificador do local é obrigatório", "MISSING_PLACE_ID");
        }
        if (businessAccountId == null) {
            throw new BusinessException("O identificador da conta comercial é obrigatório", "MISSING_BUSINESS_ACCOUNT_ID");
        }
        if (moderatorUserId == null) {
            throw new BusinessException("O identificador do moderador é obrigatório", "MISSING_MODERATOR_ID");
        }
        if (action == null) {
            throw new BusinessException("A ação de moderação é obrigatória", "MISSING_MODERATION_ACTION");
        }
        if (previousStatus == null || newStatus == null) {
            throw new BusinessException("Os estados anterior e novo da reivindicação são obrigatórios", "MISSING_CLAIM_STATUS");
        }
        if (decisionReason == null || decisionReason.isBlank()) {
            throw new BusinessException("A justificativa da decisão é obrigatória", "MISSING_DECISION_REASON");
        }

        this.id = id != null ? id : UUID.randomUUID();
        this.claimId = claimId;
        this.placeId = placeId;
        this.businessAccountId = businessAccountId;
        this.moderatorUserId = moderatorUserId;
        this.action = action;
        this.previousStatus = previousStatus;
        this.newStatus = newStatus;
        this.decisionReason = decisionReason.trim();
        this.createdAt = (createdAt != null ? createdAt : Instant.now()).truncatedTo(ChronoUnit.MICROS);
        this.authenticityMac = authenticityMac;
    }

    public UUID getId() {
        return id;
    }

    public UUID getClaimId() {
        return claimId;
    }

    public UUID getPlaceId() {
        return placeId;
    }

    public UUID getBusinessAccountId() {
        return businessAccountId;
    }

    public UUID getModeratorUserId() {
        return moderatorUserId;
    }

    public Action getAction() {
        return action;
    }

    public PlaceClaimStatus getPreviousStatus() {
        return previousStatus;
    }

    public PlaceClaimStatus getNewStatus() {
        return newStatus;
    }

    public String getDecisionReason() {
        return decisionReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getAuthenticityMac() {
        return authenticityMac;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PlaceClaimModerationAuditLog that = (PlaceClaimModerationAuditLog) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
