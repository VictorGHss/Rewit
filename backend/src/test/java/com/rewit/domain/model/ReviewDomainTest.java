package com.rewit.domain.model;

import com.rewit.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Testes Unitários de Domínio - Núcleo de Review e ReviewTarget (Step 10.0)")
class ReviewDomainTest {

    @Test
    @DisplayName("1. Review válido com um target")
    void shouldCreateValidReviewWithSingleTarget() {
        UUID userId = UUID.randomUUID();
        UUID reviewId = UUID.randomUUID();
        Review review = new Review(reviewId, userId, null, "Experiência ótima", false, null, null, null);

        UUID targetId = UUID.randomUUID();
        ReviewTarget target = new ReviewTarget(UUID.randomUUID(), reviewId, targetId, new BigDecimal("4.5"), "Muito bom");
        review.addTarget(target);

        assertDoesNotThrow(review::validateHasAtLeastOneTarget);
        assertEquals(1, review.getTargets().size());
        assertEquals(targetId, review.getTargets().get(0).getTargetId());
        assertEquals(new BigDecimal("4.5"), review.getTargets().get(0).getRating());
    }

    @Test
    @DisplayName("2. Review válido com múltiplos targets")
    void shouldCreateValidReviewWithMultipleTargets() {
        UUID userId = UUID.randomUUID();
        UUID reviewId = UUID.randomUUID();
        Review review = new Review(reviewId, userId, null, null, false, null, null, null);

        ReviewTarget target1 = new ReviewTarget(UUID.randomUUID(), reviewId, UUID.randomUUID(), new BigDecimal("5.0"), "Prato principal");
        ReviewTarget target2 = new ReviewTarget(UUID.randomUUID(), reviewId, UUID.randomUUID(), new BigDecimal("3.5"), "Sobremesa");
        ReviewTarget target3 = new ReviewTarget(UUID.randomUUID(), reviewId, UUID.randomUUID(), new BigDecimal("4.0"), "Atendimento");

        review.addTarget(target1);
        review.addTarget(target2);
        review.addTarget(target3);

        assertDoesNotThrow(review::validateHasAtLeastOneTarget);
        assertEquals(3, review.getTargets().size());
    }

    @Test
    @DisplayName("3. Rating mínimo válido (1.0)")
    void shouldAcceptMinimumValidRating() {
        UUID reviewId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();

        ReviewTarget target = new ReviewTarget(UUID.randomUUID(), reviewId, targetId, new BigDecimal("1.0"), "Mínimo");

        assertEquals(new BigDecimal("1.0"), target.getRating());
    }

    @Test
    @DisplayName("4. Rating máximo válido (5.0)")
    void shouldAcceptMaximumValidRating() {
        UUID reviewId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();

        ReviewTarget target = new ReviewTarget(UUID.randomUUID(), reviewId, targetId, new BigDecimal("5.0"), "Máximo");

        assertEquals(new BigDecimal("5.0"), target.getRating());
    }

    @Test
    @DisplayName("5. Rating abaixo do mínimo rejeitado (< 1.0)")
    void shouldRejectRatingBelowMinimum() {
        UUID reviewId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();

        BusinessException ex = assertThrows(BusinessException.class, () ->
                new ReviewTarget(UUID.randomUUID(), reviewId, targetId, new BigDecimal("0.9"), "Abaixo")
        );
        assertEquals("INVALID_RATING_RANGE", ex.getErrorCode());
    }

    @Test
    @DisplayName("6. Rating acima do máximo rejeitado (> 5.0)")
    void shouldRejectRatingAboveMaximum() {
        UUID reviewId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();

        BusinessException ex = assertThrows(BusinessException.class, () ->
                new ReviewTarget(UUID.randomUUID(), reviewId, targetId, new BigDecimal("5.1"), "Acima")
        );
        assertEquals("INVALID_RATING_RANGE", ex.getErrorCode());
    }

    @Test
    @DisplayName("7. Review sem targets rejeitado")
    void shouldRejectReviewWithoutTargets() {
        UUID userId = UUID.randomUUID();
        Review review = new Review(UUID.randomUUID(), userId, null, "Texto", false, null, null, null);

        BusinessException ex = assertThrows(BusinessException.class, review::validateHasAtLeastOneTarget);
        assertEquals("REVIEW_WITHOUT_TARGET", ex.getErrorCode());
    }

    @Test
    @DisplayName("8. Target duplicado no mesmo Review rejeitado")
    void shouldRejectDuplicateTargetInSameReview() {
        UUID reviewId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        Review review = new Review(reviewId, UUID.randomUUID(), null, null, false, null, null, null);

        ReviewTarget target1 = new ReviewTarget(UUID.randomUUID(), reviewId, targetId, new BigDecimal("4.0"), "Primeira");
        ReviewTarget target2 = new ReviewTarget(UUID.randomUUID(), reviewId, targetId, new BigDecimal("5.0"), "Segunda");

        review.addTarget(target1);

        BusinessException ex = assertThrows(BusinessException.class, () -> review.addTarget(target2));
        assertEquals("DUPLICATE_REVIEW_TARGET", ex.getErrorCode());
        assertEquals(1, review.getTargets().size());
    }

    @Test
    @DisplayName("9. Target diferente no mesmo Review permitido")
    void shouldAllowDifferentTargetsInSameReview() {
        UUID reviewId = UUID.randomUUID();
        Review review = new Review(reviewId, UUID.randomUUID(), null, null, false, null, null, null);

        UUID targetA = UUID.randomUUID();
        UUID targetB = UUID.randomUUID();

        review.addTarget(new ReviewTarget(UUID.randomUUID(), reviewId, targetA, new BigDecimal("4.0"), "Alvo A"));
        review.addTarget(new ReviewTarget(UUID.randomUUID(), reviewId, targetB, new BigDecimal("5.0"), "Alvo B"));

        assertEquals(2, review.getTargets().size());
    }

    @Test
    @DisplayName("10. Experience opcional funcionando")
    void shouldAllowOptionalExperienceText() {
        UUID userId = UUID.randomUUID();

        Review withExp = new Review(UUID.randomUUID(), userId, null, "Texto descritivo de experiência", false, null, null, null);
        Review withoutExp = new Review(UUID.randomUUID(), userId, null, null, false, null, null, null);

        assertEquals("Texto descritivo de experiência", withExp.getExperienceText());
        assertNull(withoutExp.getExperienceText());
    }

    @Test
    @DisplayName("11. Author obrigatório")
    void shouldRequireAuthorUserId() {
        BusinessException ex = assertThrows(BusinessException.class, () ->
                new Review(UUID.randomUUID(), null, null, "Texto", false, null, null, null)
        );
        assertEquals("MISSING_USER_ID", ex.getErrorCode());
    }

    @Test
    @DisplayName("12. Anonymous preservando autor interno")
    void shouldPreserveAuthorWhenAnonymous() {
        UUID authorUserId = UUID.randomUUID();
        Review review = new Review(UUID.randomUUID(), authorUserId, null, "Review secreta", true, null, null, null);

        assertTrue(review.isAnonymous());
        assertEquals(authorUserId, review.getUserId());
    }

    @Test
    @DisplayName("13. Visibility válida e inválida rejeitada")
    void shouldValidateVisibilityCorrectly() {
        UUID userId = UUID.randomUUID();

        Review pub = new Review(UUID.randomUUID(), userId, null, null, false, "PUBLIC", null, null, null);
        Review priv = new Review(UUID.randomUUID(), userId, null, null, false, "PRIVATE", null, null, null);
        Review foll = new Review(UUID.randomUUID(), userId, null, null, false, "FOLLOWERS", null, null, null);
        Review def = new Review(UUID.randomUUID(), userId, null, null, false, null, null, null, null);

        assertEquals("PUBLIC", pub.getVisibility());
        assertEquals("PRIVATE", priv.getVisibility());
        assertEquals("FOLLOWERS", foll.getVisibility());
        assertEquals("PUBLIC", def.getVisibility());

        BusinessException ex = assertThrows(BusinessException.class, () ->
                new Review(UUID.randomUUID(), userId, null, null, false, "INVALID_VISIBILITY", null, null, null)
        );
        assertEquals("INVALID_VISIBILITY", ex.getErrorCode());
    }

    @Test
    @DisplayName("14. ContextPlace opcional")
    void shouldAllowOptionalContextPlace() {
        UUID userId = UUID.randomUUID();
        UUID placeId = UUID.randomUUID();

        Review withPlace = new Review(UUID.randomUUID(), userId, placeId, null, false, null, null, null);
        Review withoutPlace = new Review(UUID.randomUUID(), userId, null, null, false, null, null, null);

        assertEquals(placeId, withPlace.getContextPlaceId());
        assertNull(withoutPlace.getContextPlaceId());
    }

    @Test
    @DisplayName("15. Rating com mais de uma casa decimal rejeitado")
    void shouldRejectRatingWithExcessiveScale() {
        UUID reviewId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();

        BusinessException ex = assertThrows(BusinessException.class, () ->
                new ReviewTarget(UUID.randomUUID(), reviewId, targetId, new BigDecimal("4.55"), "Escala excessiva")
        );
        assertEquals("INVALID_RATING_PRECISION", ex.getErrorCode());
    }
}
