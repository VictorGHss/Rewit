package com.rewit.infrastructure.persistence.entity;

import com.rewit.domain.enums.PlaceClaimStatus;
import com.rewit.domain.model.PlaceClaimRequest;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Entidade JPA da tabela place_claim_requests (V23).
 */
@Entity
@Table(name = "place_claim_requests")
public class PlaceClaimRequestJpaEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "business_account_id", nullable = false, updatable = false)
    private UUID businessAccountId;

    @Column(name = "place_id", nullable = false, updatable = false)
    private UUID placeId;

    @Column(name = "evidence_description", nullable = false, updatable = false, columnDefinition = "TEXT")
    private String evidenceDescription;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decided_by_user_id")
    private UUID decidedByUserId;

    @Column(name = "decision_reason", columnDefinition = "TEXT")
    private String decisionReason;

    protected PlaceClaimRequestJpaEntity() {
    }

    public static PlaceClaimRequestJpaEntity fromDomain(PlaceClaimRequest claim) {
        PlaceClaimRequestJpaEntity entity = new PlaceClaimRequestJpaEntity();
        entity.id = claim.getId();
        entity.businessAccountId = claim.getBusinessAccountId();
        entity.placeId = claim.getPlaceId();
        entity.evidenceDescription = claim.getEvidenceDescription();
        entity.createdAt = claim.getCreatedAt();
        entity.updateFromDomain(claim);
        return entity;
    }

    /** Só a decisão muda depois da criação. */
    public void updateFromDomain(PlaceClaimRequest claim) {
        this.status = claim.getStatus().name();
        this.decidedAt = claim.getDecidedAt();
        this.decidedByUserId = claim.getDecidedByUserId();
        this.decisionReason = claim.getDecisionReason();
    }

    public PlaceClaimRequest toDomain() {
        return new PlaceClaimRequest(id, businessAccountId, placeId, evidenceDescription, PlaceClaimStatus.valueOf(status),
                createdAt, decidedAt, decidedByUserId, decisionReason);
    }

    public UUID getId() {
        return id;
    }
}
