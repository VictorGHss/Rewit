package com.rewit.domain.model;

import com.rewit.common.exception.BusinessException;

import java.time.Instant;
import java.util.UUID;

/**
 * Entidade de Domínio representando respostas ou comentários a uma avaliação (discussões comunitárias).
 */
public class ReviewDiscussion {

    private final UUID id;
    private final UUID reviewId;
    private final UUID userId;
    private final UUID parentId;
    private final String content;
    private final boolean isFromOwner;
    private String status;
    private final Instant createdAt;
    private Instant updatedAt;

    public ReviewDiscussion(UUID id, UUID reviewId, UUID userId, UUID parentId,
                            String content, boolean isFromOwner) {
        if (reviewId == null) {
            throw new BusinessException("A avaliação é obrigatória", "MISSING_REVIEW_ID");
        }
        if (userId == null) {
            throw new BusinessException("O autor da resposta é obrigatório", "MISSING_USER_ID");
        }
        if (content == null || content.isBlank()) {
            throw new BusinessException("O conteúdo da resposta não pode ser vazio", "EMPTY_CONTENT");
        }

        this.id = id != null ? id : UUID.randomUUID();
        this.reviewId = reviewId;
        this.userId = userId;
        this.parentId = parentId;
        this.content = content.trim();
        this.isFromOwner = isFromOwner;
        this.status = "ACTIVE";
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
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

    public UUID getParentId() {
        return parentId;
    }

    public String getContent() {
        return content;
    }

    public boolean isFromOwner() {
        return isFromOwner;
    }

    public String getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
