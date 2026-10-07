package com.rewit.application.service;

import com.rewit.application.dto.discussion.DiscussionThreadDtos.DiscussionAuthorView;
import com.rewit.application.dto.discussion.DiscussionThreadDtos.DiscussionItemView;
import com.rewit.application.dto.discussion.DiscussionThreadDtos.DiscussionViewState;
import com.rewit.domain.model.Profile;
import com.rewit.domain.model.ReviewDiscussion;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Projeta uma discussão para um leitor (C3 D1/D2): o status interno vira estado de apresentação e o conteúdo
 * e a autoria são omitidos quando o leitor não pode vê-los. Função pura: não acessa repositórios.
 */
@Component
public class DiscussionPresentationPolicy {

    /**
     * Autoria que pode ser exposta: não removida e não mascarada pelo anonimato da avaliação (resposta do
     * próprio autor de uma avaliação anônima).
     */
    public boolean exposesAuthor(ReviewDiscussion discussion, boolean reviewAnonymous) {
        return !discussion.isRemoved() && !(reviewAnonymous && discussion.isFromOwner());
    }

    /**
     * @throws IllegalStateException se o item estiver UNDER_REVIEW e o leitor não for o autor (a consulta nunca
     *                               deve entregá-lo; projetar seria vazar a quarentena)
     */
    public DiscussionItemView present(ReviewDiscussion discussion, UUID viewerId, boolean reviewAnonymous,
                                      Map<UUID, Profile> profilesByUserId) {
        return present(discussion, viewerId, reviewAnonymous, profilesByUserId, Set.of());
    }

    /**
     * @param deletedUserIds autores com conta {@code DELETED} (C2): o comentário continua, mas o autor é projetado
     *                       sem id, handle nem avatar ({@link DiscussionAuthorView#deleted()})
     */
    public DiscussionItemView present(ReviewDiscussion discussion, UUID viewerId, boolean reviewAnonymous,
                                      Map<UUID, Profile> profilesByUserId, Set<UUID> deletedUserIds) {
        Objects.requireNonNull(discussion, "discussion must not be null");
        Objects.requireNonNull(viewerId, "viewerId must not be null");
        Objects.requireNonNull(profilesByUserId, "profilesByUserId must not be null");
        Objects.requireNonNull(deletedUserIds, "deletedUserIds must not be null");

        boolean viewerIsAuthor = viewerId.equals(discussion.getUserId());
        DiscussionViewState state = stateFor(discussion, viewerIsAuthor);
        boolean removed = state == DiscussionViewState.REMOVED;
        boolean isRoot = discussion.getParentId() == null;

        DiscussionAuthorView author = null;
        if (exposesAuthor(discussion, reviewAnonymous)) {
            author = deletedUserIds.contains(discussion.getUserId())
                    ? DiscussionAuthorView.deleted()
                    : authorView(discussion.getUserId(), profilesByUserId.get(discussion.getUserId()));
        }

        return new DiscussionItemView(
                discussion.getId(),
                discussion.getReviewId(),
                discussion.getParentId(),
                state,
                removed ? null : discussion.getContent(),
                author,
                // No tombstone nem a autoria do dono da avaliação é revelada
                !removed && discussion.isFromOwner(),
                discussion.getCreatedAt(),
                isRoot && state == DiscussionViewState.VISIBLE,
                // Só comentário publicado pode ser excluído pelo autor; em análise depende da moderação
                viewerIsAuthor && state == DiscussionViewState.VISIBLE
        );
    }

    private static DiscussionViewState stateFor(ReviewDiscussion discussion, boolean viewerIsAuthor) {
        return switch (discussion.getStatus()) {
            case ACTIVE -> DiscussionViewState.VISIBLE;
            case REMOVED -> DiscussionViewState.REMOVED;
            case UNDER_REVIEW -> {
                if (!viewerIsAuthor) {
                    throw new IllegalStateException("Discussão em análise não pode ser projetada para terceiros");
                }
                yield DiscussionViewState.PENDING_REVIEW;
            }
        };
    }

    private static DiscussionAuthorView authorView(UUID userId, Profile profile) {
        if (profile == null) {
            return new DiscussionAuthorView(userId, null, null, null);
        }
        return new DiscussionAuthorView(userId, profile.getHandle(), profile.getDisplayName(), profile.getAvatarUrl());
    }
}
