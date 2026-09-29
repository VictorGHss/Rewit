package com.rewit.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Entidade JPA mapeando a tabela review_reactions no PostgreSQL (Step 16.0).
 */
@Entity
@Table(name = "review_reactions")
public class ReviewReactionJpaEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "review_id", nullable = false)
    private UUID reviewId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "reaction_type", nullable = false, length = 32)
    private String reactionType = "HELPFUL";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public ReviewReactionJpaEntity() {}

    public ReviewReactionJpaEntity(UUID id, UUID reviewId, UUID userId, String reactionType, Instant createdAt) {
        this.id = id != null ? id : UUID.randomUUID();
        this.reviewId = Objects.requireNonNull(reviewId, "reviewId must not be null");
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        this.reactionType = reactionType != null ? reactionType : "HELPFUL";
        this.createdAt = createdAt != null ? createdAt : Instant.now();
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

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public String getReactionType() {
        return reactionType;
    }

    public void setReactionType(String reactionType) {
        this.reactionType = reactionType;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
