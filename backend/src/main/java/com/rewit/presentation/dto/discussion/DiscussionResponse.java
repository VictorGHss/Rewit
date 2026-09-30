package com.rewit.presentation.dto.discussion;

import com.rewit.application.dto.discussion.DiscussionDtos.DiscussionView;

import java.time.Instant;
import java.util.UUID;

/**
 * DTO de resposta pública para comentários e discussões de avaliações (Step 20.0).
 */
public record DiscussionResponse(
        UUID id,
        UUID reviewId,
        UUID authorId,
        UUID parentId,
        String content,
        boolean isFromOwner,
        String status,
        Instant createdAt
) {
    public static DiscussionResponse fromView(DiscussionView view) {
        if (view == null) {
            return null;
        }
        return new DiscussionResponse(
                view.id(),
                view.reviewId(),
                view.authorId(),
                view.parentId(),
                view.content(),
                view.isFromOwner(),
                view.status(),
                view.createdAt()
        );
    }
}
