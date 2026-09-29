package com.rewit.domain.model;

import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.ReactionType;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Entidade de Domínio representando uma reação a uma avaliação (Step 16.0).
 */
public class ReviewReaction {

    private final UUID id;
    private final UUID reviewId;
    private final UUID userId;
    private final ReactionType reactionType;
    private final Instant createdAt;

    public ReviewReaction(UUID id, UUID reviewId, UUID userId, ReactionType reactionType) {
        this(id, reviewId, userId, reactionType, Instant.now());
    }

    public ReviewReaction(UUID id, UUID reviewId, UUID userId, ReactionType reactionType, Instant createdAt) {
        if (reviewId == null) {
            throw new BusinessException("Identificador de avaliação obrigatório", HttpStatus.BAD_REQUEST, "MISSING_REVIEW_ID");
        }
        if (userId == null) {
            throw new BusinessException("Identificador de usuário obrigatório", HttpStatus.BAD_REQUEST, "MISSING_USER_ID");
        }
        this.id = id != null ? id : UUID.randomUUID();
        this.reviewId = reviewId;
        this.userId = userId;
        this.reactionType = Objects.requireNonNull(reactionType, "reactionType cannot be null");
        this.createdAt = createdAt != null ? createdAt : Instant.now();
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

    public ReactionType getReactionType() {
        return reactionType;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
