package com.rewit.domain.model;

import com.rewit.common.exception.BusinessException;

import java.time.Instant;
import java.util.UUID;

/**
 * Entidade de Domínio representando reações sociais a publicações (ex: HELPFUL).
 */
public class ReviewReaction {

    private final UUID id;
    private final UUID reviewId;
    private final UUID userId;
    private final String reactionType;
    private final Instant createdAt;

    public ReviewReaction(UUID id, UUID reviewId, UUID userId, String reactionType) {
        if (reviewId == null) {
            throw new BusinessException("A avaliação é obrigatória", "MISSING_REVIEW_ID");
        }
        if (userId == null) {
            throw new BusinessException("O usuário é obrigatório", "MISSING_USER_ID");
        }

        this.id = id != null ? id : UUID.randomUUID();
        this.reviewId = reviewId;
        this.userId = userId;
        this.reactionType = reactionType != null ? reactionType : "HELPFUL";
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getReviewId() {
        return reviewId;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getReactionType() {
        return reactionType;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
