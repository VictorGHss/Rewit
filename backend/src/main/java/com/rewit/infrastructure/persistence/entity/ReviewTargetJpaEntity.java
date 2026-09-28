package com.rewit.infrastructure.persistence.entity;

import com.rewit.domain.model.ReviewTarget;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Entidade JPA mapeando a tabela review_targets no PostgreSQL (ADR-009).
 */
@Entity
@Table(name = "review_targets")
public class ReviewTargetJpaEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "review_id", nullable = false)
    private UUID reviewId;

    @Column(name = "target_id", nullable = false)
    private UUID targetId;

    @Column(name = "rating", nullable = false, precision = 2, scale = 1)
    private BigDecimal rating;

    @Column(name = "specific_comment", columnDefinition = "TEXT")
    private String specificComment;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public ReviewTargetJpaEntity() {}

    public ReviewTargetJpaEntity(UUID id, UUID reviewId, UUID targetId, BigDecimal rating, String specificComment, Instant createdAt) {
        this.id = id;
        this.reviewId = reviewId;
        this.targetId = targetId;
        this.rating = rating;
        this.specificComment = specificComment;
        this.createdAt = createdAt != null ? createdAt : Instant.now();
    }

    public static ReviewTargetJpaEntity fromDomain(ReviewTarget domain) {
        if (domain == null) {
            return null;
        }
        return new ReviewTargetJpaEntity(
                domain.getId(),
                domain.getReviewId(),
                domain.getTargetId(),
                domain.getRating(),
                domain.getSpecificComment(),
                domain.getCreatedAt()
        );
    }

    public ReviewTarget toDomain() {
        return new ReviewTarget(
                this.id,
                this.reviewId,
                this.targetId,
                this.rating,
                this.specificComment,
                this.createdAt
        );
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getReviewId() {
        return reviewId;
    }

    public void setReviewId(UUID reviewId) {
        this.reviewId = reviewId;
    }

    public UUID getTargetId() {
        return targetId;
    }

    public void setTargetId(UUID targetId) {
        this.targetId = targetId;
    }

    public BigDecimal getRating() {
        return rating;
    }

    public void setRating(BigDecimal rating) {
        this.rating = rating;
    }

    public String getSpecificComment() {
        return specificComment;
    }

    public void setSpecificComment(String specificComment) {
        this.specificComment = specificComment;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
