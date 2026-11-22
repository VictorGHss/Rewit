package com.rewit.infrastructure.persistence.entity;

import com.rewit.domain.enums.PlaceClaimStatus;
import com.rewit.domain.model.PlaceClaimModerationAuditLog;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Entidade JPA append-only para {@code place_claim_moderation_audit_logs} (V45 / S3-A13-F2).
 */
@Entity
@Table(name = "place_claim_moderation_audit_logs")
public class PlaceClaimModerationAuditLogJpaEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "claim_id", nullable = false, updatable = false)
    private UUID claimId;

    @Column(name = "place_id", nullable = false, updatable = false)
    private UUID placeId;

    @Column(name = "business_account_id", nullable = false, updatable = false)
    private UUID businessAccountId;

    @Column(name = "moderator_user_id", nullable = false, updatable = false)
    private UUID moderatorUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, updatable = false, length = 32)
    private PlaceClaimModerationAuditLog.Action action;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_status", nullable = false, updatable = false, length = 16)
    private PlaceClaimStatus previousStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_status", nullable = false, updatable = false, length = 16)
    private PlaceClaimStatus newStatus;

    @Column(name = "decision_reason", nullable = false, updatable = false, columnDefinition = "TEXT")
    private String decisionReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "authenticity_mac", nullable = false, updatable = false, length = 128)
    private String authenticityMac;

    protected PlaceClaimModerationAuditLogJpaEntity() {
    }

    public static PlaceClaimModerationAuditLogJpaEntity fromDomain(PlaceClaimModerationAuditLog log) {
        PlaceClaimModerationAuditLogJpaEntity entity = new PlaceClaimModerationAuditLogJpaEntity();
        entity.id = log.getId();
        entity.claimId = log.getClaimId();
        entity.placeId = log.getPlaceId();
        entity.businessAccountId = log.getBusinessAccountId();
        entity.moderatorUserId = log.getModeratorUserId();
        entity.action = log.getAction();
        entity.previousStatus = log.getPreviousStatus();
        entity.newStatus = log.getNewStatus();
        entity.decisionReason = log.getDecisionReason();
        entity.createdAt = log.getCreatedAt();
        entity.authenticityMac = log.getAuthenticityMac();
        return entity;
    }

    public PlaceClaimModerationAuditLog toDomain() {
        return new PlaceClaimModerationAuditLog(
                id, claimId, placeId, businessAccountId, moderatorUserId,
                action, previousStatus, newStatus, decisionReason, createdAt, authenticityMac);
    }
}
