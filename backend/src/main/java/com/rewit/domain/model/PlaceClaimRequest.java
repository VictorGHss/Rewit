package com.rewit.domain.model;

import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.PlaceClaimStatus;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Solicitação de uma conta comercial para reivindicar um local existente (C9). Nasce pendente e é decidida uma única
 * vez por um moderador ou administrador; a decisão (quem, quando e por quê) fica registrada na própria solicitação.
 */
public class PlaceClaimRequest {

    public static final int MIN_EVIDENCE_LENGTH = 20;
    public static final int MAX_EVIDENCE_LENGTH = 1000;
    public static final int MIN_DECISION_REASON_LENGTH = 15;
    public static final int MAX_DECISION_REASON_LENGTH = 1000;

    private final UUID id;
    private final UUID businessAccountId;
    private final UUID placeId;
    private final String evidenceDescription;
    private PlaceClaimStatus status;
    private final Instant createdAt;
    private Instant decidedAt;
    private UUID decidedByUserId;
    private String decisionReason;

    /** Nova solicitação, pendente. A evidência é aparada e precisa ter entre 20 e 1000 caracteres. */
    public PlaceClaimRequest(UUID businessAccountId, UUID placeId, String evidenceDescription, Instant now) {
        this(UUID.randomUUID(), businessAccountId, placeId, normalizeEvidence(evidenceDescription), PlaceClaimStatus.PENDING,
                Objects.requireNonNull(now, "now must not be null"), null, null, null);
    }

    /** Reconstituição a partir da persistência. */
    public PlaceClaimRequest(UUID id, UUID businessAccountId, UUID placeId, String evidenceDescription,
                             PlaceClaimStatus status, Instant createdAt, Instant decidedAt, UUID decidedByUserId,
                             String decisionReason) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.businessAccountId = Objects.requireNonNull(businessAccountId, "businessAccountId must not be null");
        this.placeId = Objects.requireNonNull(placeId, "placeId must not be null");
        this.evidenceDescription = Objects.requireNonNull(evidenceDescription, "evidenceDescription must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.decidedAt = decidedAt;
        this.decidedByUserId = decidedByUserId;
        this.decisionReason = decisionReason;
    }

    public void approve(UUID moderatorUserId, String reason, Instant now) {
        decide(PlaceClaimStatus.APPROVED, moderatorUserId, reason, now);
    }

    public void reject(UUID moderatorUserId, String reason, Instant now) {
        decide(PlaceClaimStatus.REJECTED, moderatorUserId, reason, now);
    }

    public boolean isPending() {
        return status == PlaceClaimStatus.PENDING;
    }

    private void decide(PlaceClaimStatus decided, UUID moderatorUserId, String reason, Instant now) {
        if (!isPending()) {
            throw new BusinessException("A solicitação de reivindicação já foi decidida", HttpStatus.CONFLICT,
                    "PLACE_CLAIM_ALREADY_DECIDED");
        }
        // Tudo validado antes de mudar qualquer campo: uma decisão inválida não deixa a solicitação pela metade
        String normalizedReason = normalizeDecisionReason(reason);
        Objects.requireNonNull(moderatorUserId, "moderatorUserId must not be null");
        Objects.requireNonNull(now, "now must not be null");
        this.status = decided;
        this.decidedByUserId = moderatorUserId;
        this.decidedAt = now;
        this.decisionReason = normalizedReason;
    }

    private static String normalizeEvidence(String evidenceDescription) {
        String trimmed = evidenceDescription == null ? "" : evidenceDescription.trim();
        int length = trimmed.codePointCount(0, trimmed.length());
        if (length < MIN_EVIDENCE_LENGTH || length > MAX_EVIDENCE_LENGTH) {
            throw new BusinessException("A descrição da evidência deve ter entre 20 e 1000 caracteres",
                    HttpStatus.BAD_REQUEST, "INVALID_EVIDENCE_DESCRIPTION");
        }
        return trimmed;
    }

    /** Mesma faixa da justificativa da moderação de avaliações (15 a 1000). */
    public static String normalizeDecisionReason(String reason) {
        String trimmed = reason == null ? "" : reason.trim();
        int length = trimmed.codePointCount(0, trimmed.length());
        if (length < MIN_DECISION_REASON_LENGTH || length > MAX_DECISION_REASON_LENGTH) {
            throw new BusinessException("A justificativa da decisão deve ter entre 15 e 1000 caracteres",
                    HttpStatus.BAD_REQUEST, "INVALID_DECISION_REASON");
        }
        return trimmed;
    }

    public UUID getId() {
        return id;
    }

    public UUID getBusinessAccountId() {
        return businessAccountId;
    }

    public UUID getPlaceId() {
        return placeId;
    }

    public String getEvidenceDescription() {
        return evidenceDescription;
    }

    public PlaceClaimStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public UUID getDecidedByUserId() {
        return decidedByUserId;
    }

    public String getDecisionReason() {
        return decisionReason;
    }
}
