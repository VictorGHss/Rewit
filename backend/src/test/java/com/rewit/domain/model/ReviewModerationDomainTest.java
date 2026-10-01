package com.rewit.domain.model;

import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.ReviewStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Testes de Unidade: Mutações de Moderação Administrativa em Review (Step 26.1)")
class ReviewModerationDomainTest {

    private Review createSampleReview(ReviewStatus initialStatus) {
        UUID reviewId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID placeId = UUID.randomUUID();
        Instant baseTime = Instant.parse("2026-03-01T10:00:00Z");

        Review review = new Review(
                reviewId, userId, placeId, "Texto da experiência",
                false, false,
                initialStatus, "PUBLIC",
                null, null, null,
                baseTime, baseTime
        );
        review.addTarget(new ReviewTarget(null, reviewId, UUID.randomUUID(), new BigDecimal("4.5"), "Muito bom"));
        return review;
    }

    @Test
    @DisplayName("markRemovedByModerator deve permitir transição ACTIVE -> REMOVED")
    void shouldAllowTransitionFromActiveToRemoved() {
        Review review = createSampleReview(ReviewStatus.ACTIVE);
        Instant now = Instant.parse("2026-03-01T12:00:00Z");

        review.markRemovedByModerator(now);

        assertEquals(ReviewStatus.REMOVED, review.getStatus());
        assertEquals(now, review.getUpdatedAt());
        assertEquals(Instant.parse("2026-03-01T10:00:00Z"), review.getCreatedAt());
    }

    @Test
    @DisplayName("markRemovedByModerator deve permitir transição UNDER_REVIEW -> REMOVED")
    void shouldAllowTransitionFromUnderReviewToRemoved() {
        Review review = createSampleReview(ReviewStatus.UNDER_REVIEW);
        Instant now = Instant.parse("2026-03-01T12:00:00Z");

        review.markRemovedByModerator(now);

        assertEquals(ReviewStatus.REMOVED, review.getStatus());
        assertEquals(now, review.getUpdatedAt());
    }

    @Test
    @DisplayName("markRemovedByModerator deve rejeitar REMOVED -> REMOVED com REVIEW_ALREADY_REMOVED")
    void shouldRejectTransitionFromRemovedToRemoved() {
        Review review = createSampleReview(ReviewStatus.REMOVED);
        Instant now = Instant.parse("2026-03-01T12:00:00Z");

        BusinessException ex = assertThrows(BusinessException.class, () ->
                review.markRemovedByModerator(now));

        assertEquals("REVIEW_ALREADY_REMOVED", ex.getErrorCode());
        assertEquals("A avaliação já se encontra removida", ex.getMessage());
    }

    @Test
    @DisplayName("markRemovedByModerator deve rejeitar timestamp nulo com MISSING_UPDATE_TIMESTAMP")
    void shouldRejectNullTimestampOnMarkRemoved() {
        Review review = createSampleReview(ReviewStatus.ACTIVE);

        BusinessException ex = assertThrows(BusinessException.class, () ->
                review.markRemovedByModerator(null));

        assertEquals("MISSING_UPDATE_TIMESTAMP", ex.getErrorCode());
    }

    @Test
    @DisplayName("restoreFromUnderReview deve permitir exclusivamente UNDER_REVIEW -> ACTIVE")
    void shouldAllowTransitionFromUnderReviewToActive() {
        Review review = createSampleReview(ReviewStatus.UNDER_REVIEW);
        Instant now = Instant.parse("2026-03-01T12:00:00Z");

        review.restoreFromUnderReview(now);

        assertEquals(ReviewStatus.ACTIVE, review.getStatus());
        assertEquals(now, review.getUpdatedAt());
    }

    @Test
    @DisplayName("restoreFromUnderReview deve rejeitar ACTIVE -> ACTIVE com REVIEW_ALREADY_ACTIVE")
    void shouldRejectTransitionFromActiveToActive() {
        Review review = createSampleReview(ReviewStatus.ACTIVE);
        Instant now = Instant.parse("2026-03-01T12:00:00Z");

        BusinessException ex = assertThrows(BusinessException.class, () ->
                review.restoreFromUnderReview(now));

        assertEquals("REVIEW_ALREADY_ACTIVE", ex.getErrorCode());
        assertEquals("A avaliação já se encontra ativa", ex.getMessage());
    }

    @Test
    @DisplayName("restoreFromUnderReview deve rejeitar REMOVED -> ACTIVE com INVALID_REVIEW_STATUS_TRANSITION")
    void shouldRejectTransitionFromRemovedToActive() {
        Review review = createSampleReview(ReviewStatus.REMOVED);
        Instant now = Instant.parse("2026-03-01T12:00:00Z");

        BusinessException ex = assertThrows(BusinessException.class, () ->
                review.restoreFromUnderReview(now));

        assertEquals("INVALID_REVIEW_STATUS_TRANSITION", ex.getErrorCode());
        assertEquals("Transição de estado inválida para restauração", ex.getMessage());
    }

    @Test
    @DisplayName("restoreFromUnderReview deve rejeitar timestamp nulo com MISSING_UPDATE_TIMESTAMP")
    void shouldRejectNullTimestampOnRestore() {
        Review review = createSampleReview(ReviewStatus.UNDER_REVIEW);

        BusinessException ex = assertThrows(BusinessException.class, () ->
                review.restoreFromUnderReview(null));

        assertEquals("MISSING_UPDATE_TIMESTAMP", ex.getErrorCode());
    }
}
