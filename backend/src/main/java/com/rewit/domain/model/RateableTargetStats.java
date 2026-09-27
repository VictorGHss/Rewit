package com.rewit.domain.model;

import com.rewit.common.exception.BusinessException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Entidade de Domínio representando as estatísticas e médias calculadas de um RateableTarget (Seção 7).
 * Mantida estritamente isolada e derivada das avaliações, sem poluir tabelas de cadastro.
 */
public class RateableTargetStats {

    private final UUID targetId;
    private BigDecimal averageRating;
    private int reviewsCount;
    private Instant lastCalculatedAt;

    public RateableTargetStats(UUID targetId, BigDecimal averageRating, int reviewsCount) {
        if (targetId == null) {
            throw new BusinessException("O identificador do alvo avaliável é obrigatório", "MISSING_TARGET_ID");
        }
        if (averageRating != null && (averageRating.compareTo(BigDecimal.ZERO) < 0 || averageRating.compareTo(BigDecimal.valueOf(5.0)) > 0)) {
            throw new BusinessException("A nota média agregada deve estar entre 0.00 e 5.00", "INVALID_AVERAGE_RATING");
        }
        if (reviewsCount < 0) {
            throw new BusinessException("A contagem de avaliações não pode ser negativa", "INVALID_REVIEWS_COUNT");
        }

        this.targetId = targetId;
        this.averageRating = averageRating != null ? averageRating : BigDecimal.ZERO;
        this.reviewsCount = reviewsCount;
        this.lastCalculatedAt = Instant.now();
    }

    public void updateStats(BigDecimal newAverageRating, int newReviewsCount) {
        if (newAverageRating != null && (newAverageRating.compareTo(BigDecimal.ZERO) < 0 || newAverageRating.compareTo(BigDecimal.valueOf(5.0)) > 0)) {
            throw new BusinessException("A nota média agregada deve estar entre 0.00 e 5.00", "INVALID_AVERAGE_RATING");
        }
        if (newReviewsCount < 0) {
            throw new BusinessException("A contagem de avaliações não pode ser negativa", "INVALID_REVIEWS_COUNT");
        }
        this.averageRating = newAverageRating != null ? newAverageRating : BigDecimal.ZERO;
        this.reviewsCount = newReviewsCount;
        this.lastCalculatedAt = Instant.now();
    }

    public UUID getTargetId() {
        return targetId;
    }

    public BigDecimal getAverageRating() {
        return averageRating;
    }

    public int getReviewsCount() {
        return reviewsCount;
    }

    public Instant getLastCalculatedAt() {
        return lastCalculatedAt;
    }
}
