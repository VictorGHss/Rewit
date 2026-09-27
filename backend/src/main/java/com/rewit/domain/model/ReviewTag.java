package com.rewit.domain.model;

import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.TagSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Entidade de Domínio associando uma Tag contextual a uma Review com grau de confiança e origem.
 */
public class ReviewTag {

    private final UUID id;
    private final UUID reviewId;
    private final UUID tagId;
    private final TagSource source;
    private final BigDecimal confidence;
    private final Instant createdAt;

    public ReviewTag(UUID id, UUID reviewId, UUID tagId, TagSource source, BigDecimal confidence) {
        if (reviewId == null) {
            throw new BusinessException("A avaliação é obrigatória", "MISSING_REVIEW_ID");
        }
        if (tagId == null) {
            throw new BusinessException("A tag é obrigatória", "MISSING_TAG_ID");
        }
        if (confidence != null && (confidence.compareTo(BigDecimal.ZERO) < 0 || confidence.compareTo(BigDecimal.ONE) > 0)) {
            throw new BusinessException("O nível de confiança deve estar entre 0.00 e 1.00", "INVALID_CONFIDENCE");
        }

        this.id = id != null ? id : UUID.randomUUID();
        this.reviewId = reviewId;
        this.tagId = tagId;
        this.source = source != null ? source : TagSource.USER;
        this.confidence = confidence != null ? confidence : BigDecimal.ONE;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getReviewId() {
        return reviewId;
    }

    public UUID getTagId() {
        return tagId;
    }

    public TagSource getSource() {
        return source;
    }

    public BigDecimal getConfidence() {
        return confidence;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
