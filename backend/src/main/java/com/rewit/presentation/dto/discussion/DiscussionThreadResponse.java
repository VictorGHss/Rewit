package com.rewit.presentation.dto.discussion;

import com.rewit.application.dto.discussion.DiscussionThreadDtos.DiscussionThreadView;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Raiz da thread de discussões com as primeiras respostas visíveis embutidas. Os campos da raiz seguem
 * {@link DiscussionItemResponse}; {@code replyCount} é o total de respostas visíveis ao leitor e
 * {@code hasMoreReplies} indica que há respostas além das embutidas.
 */
public record DiscussionThreadResponse(
        UUID id,
        UUID reviewId,
        UUID parentId,
        String state,
        String content,
        DiscussionItemResponse.Author author,
        boolean isFromOwner,
        Instant createdAt,
        boolean canReply,
        boolean canDelete,
        List<DiscussionItemResponse> replies,
        long replyCount,
        boolean hasMoreReplies
) {

    public static DiscussionThreadResponse fromView(DiscussionThreadView view) {
        DiscussionItemResponse root = DiscussionItemResponse.fromView(view.root());
        List<DiscussionItemResponse> replies = view.replies().stream()
                .map(DiscussionItemResponse::fromView)
                .toList();
        return new DiscussionThreadResponse(
                root.id(),
                root.reviewId(),
                root.parentId(),
                root.state(),
                root.content(),
                root.author(),
                root.isFromOwner(),
                root.createdAt(),
                root.canReply(),
                root.canDelete(),
                replies,
                view.replyCount(),
                view.hasMoreReplies()
        );
    }
}
