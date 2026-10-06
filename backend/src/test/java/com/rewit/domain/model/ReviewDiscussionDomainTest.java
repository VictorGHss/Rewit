package com.rewit.domain.model;

import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.DiscussionStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Testes de Domínio: Ciclo de Vida e Transições de Status de ReviewDiscussion (Step 32.0)")
class ReviewDiscussionDomainTest {

    private final UUID reviewId = UUID.randomUUID();
    private final UUID authorId = UUID.randomUUID();

    @Test
    @DisplayName("Deve instanciar discussão com status padrão ACTIVE")
    void shouldInstantiateWithDefaultActiveStatus() {
        ReviewDiscussion discussion = new ReviewDiscussion(
                UUID.randomUUID(), reviewId, authorId, null, "Comentário de teste", false
        );

        assertEquals(DiscussionStatus.ACTIVE, discussion.getStatus());
        assertTrue(discussion.isActive());
        assertFalse(discussion.isUnderReview());
        assertFalse(discussion.isRemoved());
    }

    @Test
    @DisplayName("Deve instanciar discussão com status explícito via enum")
    void shouldInstantiateWithExplicitStatus() {
        ReviewDiscussion underReview = new ReviewDiscussion(
                UUID.randomUUID(), reviewId, authorId, null, "Sob moderação", false,
                DiscussionStatus.UNDER_REVIEW, Instant.now(), Instant.now()
        );
        assertEquals(DiscussionStatus.UNDER_REVIEW, underReview.getStatus());
        assertTrue(underReview.isUnderReview());

        ReviewDiscussion removed = new ReviewDiscussion(
                UUID.randomUUID(), reviewId, authorId, null, "Removido", false,
                DiscussionStatus.REMOVED, Instant.now(), Instant.now()
        );
        assertEquals(DiscussionStatus.REMOVED, removed.getStatus());
        assertTrue(removed.isRemoved());
    }

    @Test
    @DisplayName("Deve instanciar discussão a partir de String com conversão segura")
    void shouldInstantiateWithStringStatusAndParseCorrectly() {
        ReviewDiscussion d1 = new ReviewDiscussion(
                UUID.randomUUID(), reviewId, authorId, null, "Texto", false,
                "ACTIVE", Instant.now(), Instant.now()
        );
        assertEquals(DiscussionStatus.ACTIVE, d1.getStatus());

        ReviewDiscussion d2 = new ReviewDiscussion(
                UUID.randomUUID(), reviewId, authorId, null, "Texto", false,
                "under_review", Instant.now(), Instant.now()
        );
        assertEquals(DiscussionStatus.UNDER_REVIEW, d2.getStatus());

        ReviewDiscussion d3 = new ReviewDiscussion(
                UUID.randomUUID(), reviewId, authorId, null, "Texto", false,
                "REMOVED", Instant.now(), Instant.now()
        );
        assertEquals(DiscussionStatus.REMOVED, d3.getStatus());
    }

    @Test
    @DisplayName("Deve rejeitar String de status inválida no construtor com INVALID_DISCUSSION_STATUS")
    void shouldRejectInvalidStringStatusInConstructor() {
        BusinessException ex = assertThrows(BusinessException.class, () ->
                new ReviewDiscussion(
                        UUID.randomUUID(), reviewId, authorId, null, "Texto", false,
                        "STATUS_INEXISTENTE", Instant.now(), Instant.now()
                ));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals("INVALID_DISCUSSION_STATUS", ex.getErrorCode());
    }

    @Test
    @DisplayName("Deve transicionar de ACTIVE para UNDER_REVIEW com sucesso")
    void shouldTransitionToUnderReviewFromActive() {
        Instant before = Instant.now().minusSeconds(10);
        ReviewDiscussion discussion = new ReviewDiscussion(
                UUID.randomUUID(), reviewId, authorId, null, "Texto", false,
                DiscussionStatus.ACTIVE, before, before
        );

        Instant mutationTime = Instant.now();
        discussion.markUnderReview(mutationTime);

        assertEquals(DiscussionStatus.UNDER_REVIEW, discussion.getStatus());
        assertTrue(discussion.isUnderReview());
        assertFalse(discussion.isActive());
        assertEquals(mutationTime, discussion.getUpdatedAt());
    }

    @Test
    @DisplayName("Deve rejeitar markUnderReview quando já estiver UNDER_REVIEW com DISCUSSION_ALREADY_UNDER_REVIEW")
    void shouldRejectUnderReviewWhenAlreadyUnderReview() {
        ReviewDiscussion discussion = new ReviewDiscussion(
                UUID.randomUUID(), reviewId, authorId, null, "Texto", false,
                DiscussionStatus.UNDER_REVIEW, Instant.now(), Instant.now()
        );

        BusinessException ex = assertThrows(BusinessException.class, discussion::markUnderReview);

        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals("DISCUSSION_ALREADY_UNDER_REVIEW", ex.getErrorCode());
    }

    @Test
    @DisplayName("Deve rejeitar markUnderReview a partir de REMOVED com INVALID_DISCUSSION_STATUS_TRANSITION")
    void shouldRejectUnderReviewWhenRemoved() {
        ReviewDiscussion discussion = new ReviewDiscussion(
                UUID.randomUUID(), reviewId, authorId, null, "Texto", false,
                DiscussionStatus.REMOVED, Instant.now(), Instant.now()
        );

        BusinessException ex = assertThrows(BusinessException.class, discussion::markUnderReview);

        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals("INVALID_DISCUSSION_STATUS_TRANSITION", ex.getErrorCode());
    }

    @Test
    @DisplayName("Deve restaurar de UNDER_REVIEW para ACTIVE com sucesso")
    void shouldRestoreFromUnderReviewToActive() {
        Instant before = Instant.now().minusSeconds(10);
        ReviewDiscussion discussion = new ReviewDiscussion(
                UUID.randomUUID(), reviewId, authorId, null, "Texto", false,
                DiscussionStatus.UNDER_REVIEW, before, before
        );

        Instant restoreTime = Instant.now();
        discussion.restoreFromUnderReview(restoreTime);

        assertEquals(DiscussionStatus.ACTIVE, discussion.getStatus());
        assertTrue(discussion.isActive());
        assertFalse(discussion.isUnderReview());
        assertEquals(restoreTime, discussion.getUpdatedAt());
    }

    @Test
    @DisplayName("Deve rejeitar restoreFromUnderReview quando já estiver ACTIVE com DISCUSSION_ALREADY_ACTIVE")
    void shouldRejectRestoreWhenAlreadyActive() {
        ReviewDiscussion discussion = new ReviewDiscussion(
                UUID.randomUUID(), reviewId, authorId, null, "Texto", false,
                DiscussionStatus.ACTIVE, Instant.now(), Instant.now()
        );

        BusinessException ex = assertThrows(BusinessException.class, discussion::restoreFromUnderReview);

        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals("DISCUSSION_ALREADY_ACTIVE", ex.getErrorCode());
    }

    @Test
    @DisplayName("Deve rejeitar restoreFromUnderReview a partir de REMOVED com INVALID_DISCUSSION_STATUS_TRANSITION")
    void shouldRejectRestoreWhenRemoved() {
        ReviewDiscussion discussion = new ReviewDiscussion(
                UUID.randomUUID(), reviewId, authorId, null, "Texto", false,
                DiscussionStatus.REMOVED, Instant.now(), Instant.now()
        );

        BusinessException ex = assertThrows(BusinessException.class, discussion::restoreFromUnderReview);

        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals("INVALID_DISCUSSION_STATUS_TRANSITION", ex.getErrorCode());
    }

    @Test
    @DisplayName("Deve remover por moderação a partir de ACTIVE")
    void shouldMarkRemovedByModeratorFromActive() {
        ReviewDiscussion discussion = new ReviewDiscussion(
                UUID.randomUUID(), reviewId, authorId, null, "Texto", false,
                DiscussionStatus.ACTIVE, Instant.now(), Instant.now()
        );

        Instant removeTime = Instant.now();
        discussion.markRemovedByModerator(removeTime);

        assertEquals(DiscussionStatus.REMOVED, discussion.getStatus());
        assertTrue(discussion.isRemoved());
        assertEquals(removeTime, discussion.getUpdatedAt());
    }

    @Test
    @DisplayName("Deve remover por moderação a partir de UNDER_REVIEW")
    void shouldMarkRemovedByModeratorFromUnderReview() {
        ReviewDiscussion discussion = new ReviewDiscussion(
                UUID.randomUUID(), reviewId, authorId, null, "Texto", false,
                DiscussionStatus.UNDER_REVIEW, Instant.now(), Instant.now()
        );

        Instant removeTime = Instant.now();
        discussion.markRemovedByModerator(removeTime);

        assertEquals(DiscussionStatus.REMOVED, discussion.getStatus());
        assertTrue(discussion.isRemoved());
        assertEquals(removeTime, discussion.getUpdatedAt());
    }

    @Test
    @DisplayName("Deve rejeitar markRemovedByModerator quando já estiver REMOVED com DISCUSSION_ALREADY_REMOVED")
    void shouldRejectMarkRemovedByModeratorWhenAlreadyRemoved() {
        ReviewDiscussion discussion = new ReviewDiscussion(
                UUID.randomUUID(), reviewId, authorId, null, "Texto", false,
                DiscussionStatus.REMOVED, Instant.now(), Instant.now()
        );

        BusinessException ex = assertThrows(BusinessException.class, discussion::markRemovedByModerator);

        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals("DISCUSSION_ALREADY_REMOVED", ex.getErrorCode());
    }

    @Test
    @DisplayName("Deve remover pelo autor (soft delete) com sucesso")
    void shouldMarkRemovedByAuthorFromActive() {
        Instant before = Instant.now().minusSeconds(10);
        ReviewDiscussion discussion = new ReviewDiscussion(
                UUID.randomUUID(), reviewId, authorId, null, "Texto", false,
                DiscussionStatus.ACTIVE, before, before
        );

        Instant removeTime = Instant.now();
        discussion.markRemoved(removeTime);

        assertEquals(DiscussionStatus.REMOVED, discussion.getStatus());
        assertTrue(discussion.isRemoved());
        assertEquals(removeTime, discussion.getUpdatedAt());
    }

    @Test
    @DisplayName("Deve rejeitar timestamp nulo em todas as transições com MISSING_TIMESTAMP")
    void shouldRejectNullTimestampInTransitions() {
        ReviewDiscussion discussion = new ReviewDiscussion(
                UUID.randomUUID(), reviewId, authorId, null, "Texto", false
        );

        BusinessException ex1 = assertThrows(BusinessException.class, () -> discussion.markUnderReview(null));
        assertEquals("MISSING_TIMESTAMP", ex1.getErrorCode());

        BusinessException ex2 = assertThrows(BusinessException.class, () -> discussion.restoreFromUnderReview(null));
        assertEquals("MISSING_TIMESTAMP", ex2.getErrorCode());

        BusinessException ex3 = assertThrows(BusinessException.class, () -> discussion.markRemovedByModerator(null));
        assertEquals("MISSING_TIMESTAMP", ex3.getErrorCode());

        BusinessException ex4 = assertThrows(BusinessException.class, () -> discussion.markRemoved(null));
        assertEquals("MISSING_TIMESTAMP", ex4.getErrorCode());
    }
}
