package com.rewit.domain.model;

import com.rewit.common.exception.BusinessException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Entidade de Domínio representando um alvo específico avaliado dentro de uma publicação multi-alvo.
 * Aponta para um RateableTarget (ADR-009).
 */
public class ReviewTarget {

    private final UUID id;
    private final UUID reviewId;
    private final UUID targetId;
    private final BigDecimal rating;
    private final String specificComment;
    private final Instant createdAt;

    public ReviewTarget(UUID id, UUID reviewId, UUID targetId, BigDecimal rating, String specificComment) {
        if (reviewId == null) {
            throw new BusinessException("A publicação de avaliação vinculada é obrigatória", "MISSING_REVIEW_ID");
        }
        if (targetId == null) {
            throw new BusinessException("O alvo avaliado (RateableTarget) é obrigatório", "MISSING_TARGET_ID");
        }
        if (rating == null || rating.compareTo(BigDecimal.valueOf(1.0)) < 0 || rating.compareTo(BigDecimal.valueOf(5.0)) > 0) {
            throw new BusinessException("A nota de avaliação deve estar rigorosamente entre 1.0 e 5.0", "INVALID_RATING_RANGE");
        }

        this.id = id != null ? id : UUID.randomUUID();
        this.reviewId = reviewId;
        this.targetId = targetId;
        this.rating = rating;
        this.specificComment = specificComment;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getReviewId() {
        return reviewId;
    }

    public UUID getTargetId() {
        return targetId;
    }

    public BigDecimal getRating() {
        return rating;
    }

    public String getSpecificComment() {
        return specificComment;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
