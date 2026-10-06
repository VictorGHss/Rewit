package com.rewit.domain.model;

import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.DiscussionStatus;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Entidade de Domínio representando respostas ou comentários a uma avaliação (discussões comunitárias).
 * Atende ao Step 20.0 — Discussions e Comments de Reviews e Step 32.0 — Tipagem de Status e Fundação de Moderação.
 */
public class ReviewDiscussion {

    public static final int MAX_CONTENT_LENGTH = 2000;

    private final UUID id;
    private final UUID reviewId;
    private final UUID userId;
    private final UUID parentId;
    private final String content;
    private final boolean isFromOwner;
    private DiscussionStatus status;
    private final Instant createdAt;
    private Instant updatedAt;

    public ReviewDiscussion(UUID id, UUID reviewId, UUID userId, UUID parentId,
                            String content, boolean isFromOwner) {
        this(id, reviewId, userId, parentId, content, isFromOwner, DiscussionStatus.ACTIVE, Instant.now(), Instant.now());
    }

    public ReviewDiscussion(UUID id, UUID reviewId, UUID userId, UUID parentId,
                            String content, boolean isFromOwner, DiscussionStatus status,
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
        this.status = status != null ? status : DiscussionStatus.ACTIVE;
        this.createdAt = createdAt != null ? createdAt : Instant.now();
        this.updatedAt = updatedAt != null ? updatedAt : Instant.now();
    }

    public ReviewDiscussion(UUID id, UUID reviewId, UUID userId, UUID parentId,
                            String content, boolean isFromOwner, String status,
                            Instant createdAt, Instant updatedAt) {
        this(id, reviewId, userId, parentId, content, isFromOwner, parseStatus(status), createdAt, updatedAt);
    }

    private static DiscussionStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return DiscussionStatus.ACTIVE;
        }
        try {
            return DiscussionStatus.valueOf(status.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("Status de discussão inválido: " + status, HttpStatus.BAD_REQUEST, "INVALID_DISCUSSION_STATUS");
        }
    }

    private static void validateTimestamp(Instant now) {
        if (now == null) {
            throw new BusinessException("O timestamp da mutação é obrigatório", HttpStatus.BAD_REQUEST, "MISSING_TIMESTAMP");
        }
    }

    /**
     * Marca o comentário como removido pelo próprio autor (soft delete).
     */
    public void markRemoved() {
        markRemoved(Instant.now());
    }

    /**
     * Marca o comentário como removido pelo próprio autor com timestamp explícito.
     */
    public void markRemoved(Instant now) {
        validateTimestamp(now);
        this.status = DiscussionStatus.REMOVED;
        this.updatedAt = now;
    }

    /**
     * Coloca o comentário sob moderação preventiva (quarentena).
     * Permite transição exclusivamente a partir de ACTIVE.
     */
    public void markUnderReview() {
        markUnderReview(Instant.now());
    }

    /**
     * Coloca o comentário sob moderação preventiva com timestamp explícito.
     */
    public void markUnderReview(Instant now) {
        validateTimestamp(now);
        if (this.status == DiscussionStatus.UNDER_REVIEW) {
            throw new BusinessException("O comentário já se encontra sob moderação preventiva", HttpStatus.CONFLICT, "DISCUSSION_ALREADY_UNDER_REVIEW");
        }
        if (this.status != DiscussionStatus.ACTIVE) {
            throw new BusinessException("Transição de estado inválida para moderação preventiva", HttpStatus.CONFLICT, "INVALID_DISCUSSION_STATUS_TRANSITION");
        }
        this.status = DiscussionStatus.UNDER_REVIEW;
        this.updatedAt = now;
    }

    /**
     * Restaura o comentário de UNDER_REVIEW para ACTIVE após deliberação de moderação.
     * Permite transição exclusivamente a partir de UNDER_REVIEW.
     */
    public void restoreFromUnderReview() {
        restoreFromUnderReview(Instant.now());
    }

    /**
     * Restaura o comentário de UNDER_REVIEW para ACTIVE com timestamp explícito.
     */
    public void restoreFromUnderReview(Instant now) {
        validateTimestamp(now);
        if (this.status == DiscussionStatus.ACTIVE) {
            throw new BusinessException("O comentário já se encontra ativo", HttpStatus.CONFLICT, "DISCUSSION_ALREADY_ACTIVE");
        }
        if (this.status != DiscussionStatus.UNDER_REVIEW) {
            throw new BusinessException("Transição de estado inválida para restauração", HttpStatus.CONFLICT, "INVALID_DISCUSSION_STATUS_TRANSITION");
        }
        this.status = DiscussionStatus.ACTIVE;
        this.updatedAt = now;
    }

    /**
     * Remove o comentário por ação de moderação administrativa.
     * Permite transição a partir de ACTIVE ou UNDER_REVIEW.
     */
    public void markRemovedByModerator() {
        markRemovedByModerator(Instant.now());
    }

    /**
     * Remove o comentário por ação de moderação administrativa com timestamp explícito.
     */
    public void markRemovedByModerator(Instant now) {
        validateTimestamp(now);
        if (this.status == DiscussionStatus.REMOVED) {
            throw new BusinessException("O comentário já se encontra removido", HttpStatus.CONFLICT, "DISCUSSION_ALREADY_REMOVED");
        }
        if (this.status != DiscussionStatus.ACTIVE && this.status != DiscussionStatus.UNDER_REVIEW) {
            throw new BusinessException("Transição de estado inválida para remoção", HttpStatus.CONFLICT, "INVALID_DISCUSSION_STATUS_TRANSITION");
        }
        this.status = DiscussionStatus.REMOVED;
        this.updatedAt = now;
    }

    public boolean isActive() {
        return this.status == DiscussionStatus.ACTIVE;
    }

    public boolean isUnderReview() {
        return this.status == DiscussionStatus.UNDER_REVIEW;
    }

    public boolean isRemoved() {
        return this.status == DiscussionStatus.REMOVED;
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

    public DiscussionStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
