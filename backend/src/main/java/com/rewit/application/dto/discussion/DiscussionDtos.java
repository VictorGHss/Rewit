package com.rewit.application.dto.discussion;

import com.rewit.domain.model.ReviewDiscussion;

import java.time.Instant;
import java.util.UUID;

/**
 * DTOs da camada de aplicação para comentários e discussões de avaliações (Step 20.0).
 */
public final class DiscussionDtos {

    private DiscussionDtos() {}

    /**
     * Comando para criação de novo comentário ou resposta.
     */
    public record CreateDiscussionCommand(
            UUID reviewId,
            UUID authorUserId,
            UUID parentId,
            String content
    ) {}

    /**
     * Projeção factual de uma discussão de avaliação para leitura na aplicação.
     */
    public record DiscussionView(
            UUID id,
            UUID reviewId,
            UUID authorId,
            UUID parentId,
            String content,
            boolean isFromOwner,
            String status,
            Instant createdAt,
            Instant updatedAt
    ) {
        public static DiscussionView fromDomain(ReviewDiscussion domain) {
            return fromDomain(domain, false);
        }

        public static DiscussionView fromDomain(ReviewDiscussion domain, boolean isReviewAnonymous) {
            if (domain == null) {
                return null;
            }
            // Se a avaliação for anônima e a resposta for do proprietário, o authorId público é mascarado (null)
            // para não quebrar o anonimato da Review, preservando isFromOwner = true como indicador semântico.
            UUID publicAuthorId = (isReviewAnonymous && domain.isFromOwner()) ? null : domain.getUserId();

            return new DiscussionView(
                    domain.getId(),
                    domain.getReviewId(),
                    publicAuthorId,
                    domain.getParentId(),
                    domain.getContent(),
                    domain.isFromOwner(),
                    domain.getStatus() != null ? domain.getStatus().name() : "ACTIVE",
                    domain.getCreatedAt(),
                    domain.getUpdatedAt()
            );
        }
    }
}
