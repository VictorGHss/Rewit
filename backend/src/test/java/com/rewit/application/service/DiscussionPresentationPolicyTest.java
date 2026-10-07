package com.rewit.application.service;

import com.rewit.application.dto.discussion.DiscussionThreadDtos.DiscussionItemView;
import com.rewit.application.dto.discussion.DiscussionThreadDtos.DiscussionViewState;
import com.rewit.domain.enums.DiscussionStatus;
import com.rewit.domain.model.Profile;
import com.rewit.domain.model.ReviewDiscussion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("DiscussionPresentationPolicy: estado de apresentação por leitor")
class DiscussionPresentationPolicyTest {

    private final DiscussionPresentationPolicy policy = new DiscussionPresentationPolicy();
    private final UUID reviewId = UUID.randomUUID();
    private final UUID author = UUID.randomUUID();
    private final UUID viewer = UUID.randomUUID();
    private final Map<UUID, Profile> profiles = Map.of(author,
            new Profile(UUID.randomUUID(), author, "autor_handle", "Autor", null, "https://cdn/avatar.png"));

    @Test
    @DisplayName("ACTIVE: VISIBLE com conteúdo e autor; raiz aceita resposta; terceiro não exclui")
    void activeIsVisible() {
        DiscussionItemView view = policy.present(discussion(DiscussionStatus.ACTIVE, null, false), viewer, false, profiles);

        assertEquals(DiscussionViewState.VISIBLE, view.state());
        assertEquals("Comentário", view.content());
        assertEquals("autor_handle", view.author().handle());
        assertEquals(author, view.author().id());
        assertTrue(view.canReply());
        assertFalse(view.canDelete());
    }

    @Test
    @DisplayName("REMOVED: tombstone sem conteúdo, sem autor e sem revelar o dono da avaliação")
    void removedIsTombstone() {
        DiscussionItemView view = policy.present(discussion(DiscussionStatus.REMOVED, null, true), author, false, profiles);

        assertEquals(DiscussionViewState.REMOVED, view.state());
        assertNull(view.content());
        assertNull(view.author());
        assertFalse(view.isFromOwner());
        assertFalse(view.canReply());
        assertFalse(view.canDelete(), "nem o próprio autor exclui um tombstone");
    }

    @Test
    @DisplayName("UNDER_REVIEW para o autor: PENDING_REVIEW com o próprio conteúdo, pode excluir, não recebe respostas")
    void underReviewForAuthorIsPendingReview() {
        DiscussionItemView view = policy.present(discussion(DiscussionStatus.UNDER_REVIEW, null, false), author, false, profiles);

        assertEquals(DiscussionViewState.PENDING_REVIEW, view.state());
        assertEquals("Comentário", view.content());
        assertFalse(view.canReply());
        assertTrue(view.canDelete());
    }

    @Test
    @DisplayName("UNDER_REVIEW para terceiro nunca é projetado (seria vazar a quarentena)")
    void underReviewForThirdPartyIsRejected() {
        ReviewDiscussion quarantined = discussion(DiscussionStatus.UNDER_REVIEW, null, false);

        assertThrows(IllegalStateException.class, () -> policy.present(quarantined, viewer, false, profiles));
    }

    @Test
    @DisplayName("Resposta nunca aceita resposta (um nível); anonimato da avaliação mascara o dono")
    void replyAndAnonymity() {
        DiscussionItemView reply = policy.present(discussion(DiscussionStatus.ACTIVE, UUID.randomUUID(), false), viewer, false, profiles);
        assertFalse(reply.canReply());

        ReviewDiscussion ownerComment = discussion(DiscussionStatus.ACTIVE, null, true);
        DiscussionItemView masked = policy.present(ownerComment, viewer, true, profiles);
        assertNull(masked.author());
        assertTrue(masked.isFromOwner());
        assertFalse(policy.exposesAuthor(ownerComment, true));
        assertTrue(policy.exposesAuthor(ownerComment, false));
    }

    @Test
    @DisplayName("Sem perfil carregado, o autor é exposto apenas pelo id")
    void missingProfileKeepsOnlyId() {
        DiscussionItemView view = policy.present(discussion(DiscussionStatus.ACTIVE, null, false), viewer, false, Map.of());

        assertEquals(author, view.author().id());
        assertNull(view.author().handle());
    }

    private ReviewDiscussion discussion(DiscussionStatus status, UUID parentId, boolean fromOwner) {
        return new ReviewDiscussion(UUID.randomUUID(), reviewId, author, parentId, "Comentário", fromOwner, status,
                Instant.now(), Instant.now());
    }
}
