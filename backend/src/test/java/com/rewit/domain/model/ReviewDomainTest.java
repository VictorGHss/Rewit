package com.rewit.domain.model;

import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.CheckInStatus;
import com.rewit.domain.enums.ReviewStatus;
import com.rewit.domain.enums.VerificationMethod;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
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

    @Test
    @DisplayName("16. Edição de texto altera experienceText e updatedAt preservando createdAt")
    void shouldEditExperienceTextAndPreserveCreatedAtAndMutableUpdatedAt() {
        UUID userId = UUID.randomUUID();
        UUID reviewId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-10-01T10:00:00Z");
        Instant initialUpdatedAt = Instant.parse("2026-10-01T10:00:00Z");

        Review review = new Review(reviewId, userId, null, "Texto original", false, false,
                ReviewStatus.ACTIVE, "PUBLIC", null, null, null, createdAt, initialUpdatedAt);
        ReviewTarget target = new ReviewTarget(UUID.randomUUID(), reviewId, UUID.randomUUID(), new BigDecimal("4.0"), "Comentário");
        review.addTarget(target);

        Instant editTime = Instant.parse("2026-10-01T11:30:00Z");
        review.updateExperienceText("Texto corrigido e atualizado", editTime);

        assertEquals("Texto corrigido e atualizado", review.getExperienceText());
        assertEquals(createdAt, review.getCreatedAt());
        assertEquals(editTime, review.getUpdatedAt());
    }

    @Test
    @DisplayName("17. Edição de rating atualiza nota do target existente preservando targetId e reviewId")
    void shouldEditTargetRatingsAndPreserveTargetStructure() {
        UUID userId = UUID.randomUUID();
        UUID reviewId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-10-01T10:00:00Z");

        Review review = new Review(reviewId, userId, null, "Texto", false, false,
                ReviewStatus.ACTIVE, "PUBLIC", null, null, null, createdAt, createdAt);
        ReviewTarget target = new ReviewTarget(UUID.randomUUID(), reviewId, targetId, new BigDecimal("3.0"), "Inicial", createdAt);
        review.addTarget(target);

        Instant editTime = Instant.parse("2026-10-01T12:00:00Z");
        review.updateTargetRating(targetId, new BigDecimal("4.5"), editTime);

        assertEquals(new BigDecimal("4.5"), review.getTargets().get(0).getRating());
        assertEquals(targetId, review.getTargets().get(0).getTargetId());
        assertEquals(reviewId, review.getTargets().get(0).getReviewId());
        assertEquals(createdAt, review.getTargets().get(0).getCreatedAt());
        assertEquals(editTime, review.getUpdatedAt());
    }

    @Test
    @DisplayName("18. Edição de rating com valor inválido é rejeitada")
    void shouldRejectInvalidTargetRatingInEdit() {
        UUID reviewId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        Review review = new Review(reviewId, UUID.randomUUID(), null, null, false, null, null, null);
        review.addTarget(new ReviewTarget(UUID.randomUUID(), reviewId, targetId, new BigDecimal("3.0"), null));

        Instant editTime = Instant.now();

        BusinessException exBelow = assertThrows(BusinessException.class, () ->
                review.updateTargetRating(targetId, new BigDecimal("0.5"), editTime));
        assertEquals("INVALID_RATING_RANGE", exBelow.getErrorCode());

        BusinessException exAbove = assertThrows(BusinessException.class, () ->
                review.updateTargetRating(targetId, new BigDecimal("5.5"), editTime));
        assertEquals("INVALID_RATING_RANGE", exAbove.getErrorCode());

        BusinessException exPrecision = assertThrows(BusinessException.class, () ->
                review.updateTargetRating(targetId, new BigDecimal("4.55"), editTime));
        assertEquals("INVALID_RATING_PRECISION", exPrecision.getErrorCode());
    }

    @Test
    @DisplayName("19. Edição de rating para target inexistente na review é rejeitada")
    void shouldRejectTargetRatingForUnrelatedTargetId() {
        UUID reviewId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        Review review = new Review(reviewId, UUID.randomUUID(), null, null, false, null, null, null);
        review.addTarget(new ReviewTarget(UUID.randomUUID(), reviewId, targetId, new BigDecimal("3.0"), null));

        UUID unrelatedTargetId = UUID.randomUUID();
        Instant editTime = Instant.now();

        BusinessException ex = assertThrows(BusinessException.class, () ->
                review.updateTargetRating(unrelatedTargetId, new BigDecimal("5.0"), editTime));
        assertEquals("TARGET_NOT_FOUND", ex.getErrorCode());
    }

    @Test
    @DisplayName("20. Edição de anonimato e visibilidade altera apenas campos permitidos")
    void shouldEditAnonymousAndVisibilityPreservingImmutableFields() {
        UUID userId = UUID.randomUUID();
        UUID reviewId = UUID.randomUUID();
        UUID contextPlaceId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-10-01T10:00:00Z");

        Review review = new Review(reviewId, userId, contextPlaceId, "Texto", true, false,
                ReviewStatus.ACTIVE, "PRIVATE", -25.4284, -49.2733, 10.0, createdAt, createdAt);
        review.addTarget(new ReviewTarget(UUID.randomUUID(), reviewId, UUID.randomUUID(), new BigDecimal("4.0"), null));

        Instant editTime = Instant.parse("2026-10-01T14:00:00Z");
        review.editContent("Texto editado", null, false, "PUBLIC", editTime);

        assertFalse(review.isAnonymous());
        assertEquals("PUBLIC", review.getVisibility());
        assertEquals("Texto editado", review.getExperienceText());
        assertEquals(editTime, review.getUpdatedAt());

        // Invariantes imutáveis estritamente preservadas:
        assertEquals(reviewId, review.getId());
        assertEquals(userId, review.getUserId());
        assertEquals(contextPlaceId, review.getContextPlaceId());
        assertEquals(createdAt, review.getCreatedAt());
        assertEquals(-25.4284, review.getUserLatitude());
        assertEquals(-49.2733, review.getUserLongitude());
        assertEquals(10.0, review.getLocationAccuracyMeters());
    }

    @Test
    @DisplayName("21. Preservação de presença física e check-in após edição")
    void shouldPreservePhysicalPresenceAndCheckInOnEdit() {
        UUID userId = UUID.randomUUID();
        UUID reviewId = UUID.randomUUID();
        UUID placeId = UUID.randomUUID();

        Review review = new Review(reviewId, userId, placeId, "Texto presencial", false,
                -25.4284, -49.2733, 15.0);
        ReviewTarget target = new ReviewTarget(UUID.randomUUID(), reviewId, placeId, new BigDecimal("5.0"), null);
        review.addTarget(target);

        CheckIn checkIn = new CheckIn(UUID.randomUUID(), reviewId, userId, placeId,
                -25.4284, -49.2733, 5.0, CheckInStatus.VERIFIED, VerificationMethod.GPS, Instant.now());
        review.attachCheckIn(checkIn);

        assertTrue(review.isVerifiedOnSite());

        Instant editTime = Instant.now().plusSeconds(3600);
        review.editContent("Texto corrigido mantendo presença", Map.of(placeId, new BigDecimal("4.0")), false, "PUBLIC", editTime);

        assertTrue(review.isVerifiedOnSite());
        assertEquals(placeId, review.getContextPlaceId());
        assertEquals(-25.4284, review.getUserLatitude());
        assertEquals(-49.2733, review.getUserLongitude());
        assertEquals(15.0, review.getLocationAccuracyMeters());
        assertEquals(new BigDecimal("4.0"), review.getTargets().get(0).getRating());
    }

    @Test
    @DisplayName("22. Soft delete a partir de ACTIVE e UNDER_REVIEW atualiza status e timestamps sem apagar agregados")
    void shouldPerformSoftDeleteFromActiveAndUnderReview() {
        UUID userId = UUID.randomUUID();
        UUID reviewId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-10-01T08:00:00Z");

        Review activeReview = new Review(reviewId, userId, null, "Para deletar", false, false,
                ReviewStatus.ACTIVE, "PUBLIC", null, null, null, createdAt, createdAt);
        activeReview.addTarget(new ReviewTarget(UUID.randomUUID(), reviewId, targetId, new BigDecimal("4.0"), null));

        Instant deleteTime = Instant.parse("2026-10-01T09:30:00Z");
        activeReview.markRemovedByAuthor(deleteTime);

        assertEquals(ReviewStatus.REMOVED, activeReview.getStatus());
        assertEquals(deleteTime, activeReview.getUpdatedAt());
        assertEquals(createdAt, activeReview.getCreatedAt());
        assertEquals(1, activeReview.getTargets().size());

        // Testar transição permitida UNDER_REVIEW -> REMOVED
        Review underReview = new Review(UUID.randomUUID(), userId, null, "Sob moderacao", false, false,
                ReviewStatus.UNDER_REVIEW, "PUBLIC", null, null, null, createdAt, createdAt);
        underReview.addTarget(new ReviewTarget(UUID.randomUUID(), underReview.getId(), targetId, new BigDecimal("3.0"), null));

        Instant deleteTime2 = Instant.parse("2026-10-01T15:00:00Z");
        underReview.markRemovedByAuthor(deleteTime2);
        assertEquals(ReviewStatus.REMOVED, underReview.getStatus());
        assertEquals(deleteTime2, underReview.getUpdatedAt());
    }

    @Test
    @DisplayName("23. Transição duplicada para REMOVED é rejeitada")
    void shouldRejectDuplicateSoftDelete() {
        Review review = new Review(UUID.randomUUID(), UUID.randomUUID(), null, "Review", false, null, null, null);
        review.addTarget(new ReviewTarget(UUID.randomUUID(), review.getId(), UUID.randomUUID(), new BigDecimal("4.0"), null));

        Instant now = Instant.now();
        review.markRemovedByAuthor(now);
        assertEquals(ReviewStatus.REMOVED, review.getStatus());

        BusinessException ex = assertThrows(BusinessException.class, () -> review.markRemovedByAuthor(now.plusSeconds(60)));
        assertEquals("REVIEW_ALREADY_REMOVED", ex.getErrorCode());
    }

    @Test
    @DisplayName("24. Tentativa de edição em review REMOVED ou UNDER_REVIEW é rejeitada")
    void shouldRejectContentEditsWhenStatusIsNotActive() {
        UUID reviewId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        Instant now = Instant.now();

        // Cenário REMOVED:
        Review removedReview = new Review(reviewId, UUID.randomUUID(), null, "Review", false, false,
                ReviewStatus.REMOVED, "PUBLIC", null, null, null, now, now);
        removedReview.addTarget(new ReviewTarget(UUID.randomUUID(), reviewId, targetId, new BigDecimal("4.0"), null));

        BusinessException exRemoved = assertThrows(BusinessException.class, () ->
                removedReview.editContent("Tentando editar", null, null, null, now));
        assertEquals("REVIEW_ALREADY_REMOVED", exRemoved.getErrorCode());

        BusinessException exExp = assertThrows(BusinessException.class, () ->
                removedReview.updateExperienceText("Novo texto", now));
        assertEquals("REVIEW_ALREADY_REMOVED", exExp.getErrorCode());

        BusinessException exRating = assertThrows(BusinessException.class, () ->
                removedReview.updateTargetRating(targetId, new BigDecimal("5.0"), now));
        assertEquals("REVIEW_ALREADY_REMOVED", exRating.getErrorCode());

        BusinessException exAnon = assertThrows(BusinessException.class, () ->
                removedReview.updateAnonymous(true, now));
        assertEquals("REVIEW_ALREADY_REMOVED", exAnon.getErrorCode());

        BusinessException exVis = assertThrows(BusinessException.class, () ->
                removedReview.updateVisibility("PRIVATE", now));
        assertEquals("REVIEW_ALREADY_REMOVED", exVis.getErrorCode());

        // Cenário UNDER_REVIEW:
        Review underReview = new Review(UUID.randomUUID(), UUID.randomUUID(), null, "Review", false, false,
                ReviewStatus.UNDER_REVIEW, "PUBLIC", null, null, null, now, now);
        underReview.addTarget(new ReviewTarget(UUID.randomUUID(), underReview.getId(), targetId, new BigDecimal("4.0"), null));

        BusinessException exUnderReview = assertThrows(BusinessException.class, () ->
                underReview.editContent("Tentando editar", null, null, null, now));
        assertEquals("INVALID_REVIEW_STATUS_FOR_EDIT", exUnderReview.getErrorCode());

        BusinessException exUnderReviewExp = assertThrows(BusinessException.class, () ->
                underReview.updateExperienceText("Novo texto", now));
        assertEquals("INVALID_REVIEW_STATUS_FOR_EDIT", exUnderReviewExp.getErrorCode());
    }

    @Test
    @DisplayName("25. Timestamp nulo em mutação é rejeitado")
    void shouldRejectMissingTimestampInMutations() {
        Review review = new Review(UUID.randomUUID(), UUID.randomUUID(), null, "Texto", false, null, null, null);
        review.addTarget(new ReviewTarget(UUID.randomUUID(), review.getId(), UUID.randomUUID(), new BigDecimal("4.0"), null));

        BusinessException exSoft = assertThrows(BusinessException.class, () -> review.markRemovedByAuthor(null));
        assertEquals("MISSING_UPDATE_TIMESTAMP", exSoft.getErrorCode());

        BusinessException exEdit = assertThrows(BusinessException.class, () -> review.editContent("Novo", null, null, null, null));
        assertEquals("MISSING_UPDATE_TIMESTAMP", exEdit.getErrorCode());
    }

    @Test
    @DisplayName("26. updateRating direto em ReviewTarget com validação de range e escala")
    void shouldUpdateRatingDirectlyOnReviewTarget() {
        ReviewTarget target = new ReviewTarget(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("3.0"), "Comentário");

        target.updateRating(new BigDecimal("4.5"));
        assertEquals(new BigDecimal("4.5"), target.getRating());

        assertThrows(BusinessException.class, () -> target.updateRating(null));
        assertThrows(BusinessException.class, () -> target.updateRating(new BigDecimal("0.9")));
        assertThrows(BusinessException.class, () -> target.updateRating(new BigDecimal("5.1")));
        assertThrows(BusinessException.class, () -> target.updateRating(new BigDecimal("4.55")));
    }
}
