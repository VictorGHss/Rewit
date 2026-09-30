package com.rewit.domain.model;

import com.rewit.common.exception.BusinessException;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Entidade de Domínio representando respostas ou comentários a uma avaliação (discussões comunitárias).
 * Atende ao Step 20.0 — Discussions e Comments de Reviews.
 */
public class ReviewDiscussion {

    public static final int MAX_CONTENT_LENGTH = 2000;

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
        this(id, reviewId, userId, parentId, content, isFromOwner, "ACTIVE", Instant.now(), Instant.now());
    }

    public ReviewDiscussion(UUID id, UUID reviewId, UUID userId, UUID parentId,
                            String content, boolean isFromOwner, String status,
                            Instant createdAt, Instant updatedAt) {
        if (reviewId == null) {
            throw new BusinessException("A avaliação é obrigatória", HttpStatus.BAD_REQUEST, "MISSING_REVIEW_ID");
        }
        if (userId == null) {
            throw new BusinessException("O autor da resposta é obrigatório", HttpStatus.BAD_REQUEST, "MISSING_USER_ID");
        }
        if (content == null || content.isBlank()) {
            throw new BusinessException("O conteúdo do comentário é obrigatório", HttpStatus.BAD_REQUEST, "EMPTY_CONTENT");
        }

        String normalizedContent = content.trim();
        if (normalizedContent.length() > MAX_CONTENT_LENGTH) {
            throw new BusinessException(
                    "O conteúdo do comentário excede o limite máximo permitido de " + MAX_CONTENT_LENGTH + " caracteres",
                    HttpStatus.BAD_REQUEST,
                    "INVALID_CONTENT_LENGTH"
            );
        }

        this.id = id != null ? id : UUID.randomUUID();
        this.reviewId = reviewId;
        this.userId = userId;
        this.parentId = parentId;
        this.content = normalizedContent;
        this.isFromOwner = isFromOwner;
        this.status = status != null ? status : "ACTIVE";
        this.createdAt = createdAt != null ? createdAt : Instant.now();
        this.updatedAt = updatedAt != null ? updatedAt : Instant.now();
    }

    public void markRemoved() {
        this.status = "REMOVED";
        this.updatedAt = Instant.now();
    }

    public boolean isActive() {
        return "ACTIVE".equalsIgnoreCase(this.status);
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
