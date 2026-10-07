package com.rewit.presentation.dto.discussion;

import com.rewit.application.dto.discussion.DiscussionThreadDtos.DiscussionAuthorView;
import com.rewit.application.dto.discussion.DiscussionThreadDtos.DiscussionItemView;

import java.time.Instant;
import java.util.UUID;

/**
 * Item da thread de discussões (resposta, ou os campos de uma raiz). {@code state} é o estado de apresentação
 * para o leitor ({@code VISIBLE}, {@code REMOVED}, {@code PENDING_REVIEW}); o status interno de moderação não é
 * exposto. Em {@code REMOVED}, {@code content} e {@code author} são nulos.
 */
public record DiscussionItemResponse(
        UUID id,
        UUID reviewId,
        UUID parentId,
        String state,
        String content,
        Author author,
        boolean isFromOwner,
        Instant createdAt,
        boolean canReply,
        boolean canDelete
) {

    public record Author(UUID id, String handle, String displayName, String avatarUrl) {

        static Author fromView(DiscussionAuthorView view) {
            return view == null ? null : new Author(view.id(), view.handle(), view.displayName(), view.avatarUrl());
        }
    }

    public static DiscussionItemResponse fromView(DiscussionItemView view) {
        return new DiscussionItemResponse(
                view.id(),
                view.reviewId(),
                view.parentId(),
                view.state().name(),
                view.content(),
                Author.fromView(view.author()),
                view.isFromOwner(),
                view.createdAt(),
                view.canReply(),
                view.canDelete()
        );
    }
}
