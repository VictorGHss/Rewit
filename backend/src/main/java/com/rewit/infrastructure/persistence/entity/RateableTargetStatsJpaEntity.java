package com.rewit.infrastructure.persistence.entity;

import com.rewit.domain.model.RateableTargetStats;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Entidade JPA mapeando a tabela rateable_target_stats no PostgreSQL (Step 13.0 / ADR-009).
 */
@Entity
@Table(name = "rateable_target_stats")
public class RateableTargetStatsJpaEntity {

    @Id
    @Column(name = "target_id", nullable = false, updatable = false)
    private UUID targetId;

    @Column(name = "average_rating", nullable = false, precision = 3, scale = 2)
    private BigDecimal averageRating;

    @Column(name = "reviews_count", nullable = false)
    private int reviewsCount;

    @Column(name = "last_calculated_at", nullable = false)
    private Instant lastCalculatedAt = Instant.now();

    public RateableTargetStatsJpaEntity() {}

    public RateableTargetStatsJpaEntity(UUID targetId, BigDecimal averageRating, int reviewsCount, Instant lastCalculatedAt) {
        this.targetId = Objects.requireNonNull(targetId, "targetId must not be null");
        this.averageRating = averageRating != null ? averageRating : BigDecimal.ZERO;
        this.reviewsCount = Math.max(0, reviewsCount);
        this.lastCalculatedAt = lastCalculatedAt != null ? lastCalculatedAt : Instant.now();
    }

    public static RateableTargetStatsJpaEntity fromDomain(RateableTargetStats domain) {
        if (domain == null) {
            return null;
        }
        return new RateableTargetStatsJpaEntity(
                domain.getTargetId(),
                domain.getAverageRating(),
                domain.getReviewsCount(),
                domain.getLastCalculatedAt()
        );
    }

    public RateableTargetStats toDomain() {
        return new RateableTargetStats(
                this.targetId,
                this.averageRating,
                this.reviewsCount,
                this.lastCalculatedAt
        );
    }

    public UUID getTargetId() {
        return targetId;
    }

    public void setTargetId(UUID targetId) {
        this.targetId = targetId;
    }

    public BigDecimal getAverageRating() {
        return averageRating;
    }

    public void setAverageRating(BigDecimal averageRating) {
        this.averageRating = averageRating;
    }

    public int getReviewsCount() {
        return reviewsCount;
    }

    public void setReviewsCount(int reviewsCount) {
        this.reviewsCount = reviewsCount;
    }

    public Instant getLastCalculatedAt() {
        return lastCalculatedAt;
    }

    public void setLastCalculatedAt(Instant lastCalculatedAt) {
        this.lastCalculatedAt = lastCalculatedAt;
    }
}
