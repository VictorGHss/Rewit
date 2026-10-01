package com.rewit.application.usecase;

import com.rewit.application.port.RateableTargetStatsRepository;
import com.rewit.application.port.ReviewMediaRepository;
import com.rewit.application.port.ReviewRepository;
import com.rewit.application.service.ReputationService;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.ReviewMediaType;
import com.rewit.domain.enums.ReviewStatus;
import com.rewit.domain.model.Review;
import com.rewit.domain.model.ReviewMedia;
import com.rewit.domain.model.ReviewTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Testes Unitários: DeleteReviewUseCase (Step 25.2)")
class DeleteReviewUseCaseUnitTest {

    @Mock
    private ReviewRepository reviewRepository;

    @Mock
    private RateableTargetStatsRepository rateableTargetStatsRepository;

    @Mock
    private ReputationService reputationService;

    @Mock
    private ReviewMediaRepository reviewMediaRepository;

    private DeleteReviewUseCase deleteReviewUseCase;

    private UUID authorId;
    private UUID reviewId;
    private UUID targetAId;
    private UUID targetBId;
    private Instant createdAt;

    @BeforeEach
    void setUp() {
        deleteReviewUseCase = new DeleteReviewUseCase(
                reviewRepository,
                rateableTargetStatsRepository,
                reputationService,
                reviewMediaRepository
        );

        authorId = UUID.randomUUID();
        reviewId = UUID.randomUUID();
        targetAId = UUID.randomUUID();
        targetBId = UUID.randomUUID();
        createdAt = Instant.parse("2026-10-01T10:00:00Z");
    }

    private Review createStandardReview(ReviewStatus status) {
        Review review = new Review(
                reviewId,
                authorId,
                null,
                "Texto para exclusão",
                false,
                false,
                status,
                "PUBLIC",
                null,
                null,
                null,
                createdAt,
                createdAt
        );

        ReviewTarget targetA = new ReviewTarget(UUID.randomUUID(), reviewId, targetAId, new BigDecimal("3.0"), "Target A", createdAt);
        ReviewTarget targetB = new ReviewTarget(UUID.randomUUID(), reviewId, targetBId, new BigDecimal("4.0"), "Target B", createdAt);
        review.addTarget(targetA);
        review.addTarget(targetB);

        return review;
    }

    @Test
    @DisplayName("1. ACTIVE -> REMOVED com recálculo atômico de stats (targetId ASC) e reputação")
    void shouldSoftDeleteActiveReviewAndRecalculateStatsAndReputation() {
        Review review = createStandardReview(ReviewStatus.ACTIVE);
        when(reviewRepository.findByIdForUpdate(reviewId)).thenReturn(Optional.of(review));
        when(reviewRepository.save(any(Review.class))).thenAnswer(inv -> inv.getArgument(0));

        Instant deleteTime = createdAt.plusSeconds(3600);
        deleteReviewUseCase.execute(reviewId, authorId, deleteTime);

        assertEquals(ReviewStatus.REMOVED, review.getStatus());
        assertEquals(deleteTime, review.getUpdatedAt());
        assertEquals(createdAt, review.getCreatedAt()); // createdAt intacto
        assertEquals(2, review.getTargets().size()); // targets preservados estruturalmente

        verify(reviewRepository).save(review);

        UUID lowerTargetId = targetAId.compareTo(targetBId) < 0 ? targetAId : targetBId;
        UUID higherTargetId = targetAId.compareTo(targetBId) < 0 ? targetBId : targetAId;

        InOrder inOrder = inOrder(rateableTargetStatsRepository);
        inOrder.verify(rateableTargetStatsRepository).recalculateAndSave(lowerTargetId);
        inOrder.verify(rateableTargetStatsRepository).recalculateAndSave(higherTargetId);

        verify(reputationService).recalculateAndSave(authorId);
    }

    @Test
    @DisplayName("2. UNDER_REVIEW -> REMOVED é permitido ao autor para retirar conteúdo de circulação")
    void shouldAllowSoftDeleteWhenReviewIsUnderReview() {
        Review review = createStandardReview(ReviewStatus.UNDER_REVIEW);
        when(reviewRepository.findByIdForUpdate(reviewId)).thenReturn(Optional.of(review));
        when(reviewRepository.save(any(Review.class))).thenAnswer(inv -> inv.getArgument(0));

        Instant deleteTime = createdAt.plusSeconds(7200);
        assertDoesNotThrow(() -> deleteReviewUseCase.execute(reviewId, authorId, deleteTime));

        assertEquals(ReviewStatus.REMOVED, review.getStatus());
        verify(reviewRepository).save(review);
        verify(reputationService).recalculateAndSave(authorId);
    }

    @Test
    @DisplayName("3. Review inexistente é rejeitada com NOT_FOUND")
    void shouldRejectDeleteWhenReviewNotFound() {
        when(reviewRepository.findByIdForUpdate(reviewId)).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class, () ->
                deleteReviewUseCase.execute(reviewId, authorId, Instant.now()));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        assertEquals("REVIEW_NOT_FOUND", ex.getErrorCode());
        verify(reviewRepository, never()).save(any());
        verifyNoInteractions(rateableTargetStatsRepository);
        verifyNoInteractions(reputationService);
    }

    @Test
    @DisplayName("4. Requester diferente do autor é rejeitado com FORBIDDEN")
    void shouldRejectDeleteWhenRequesterIsNotAuthor() {
        Review review = createStandardReview(ReviewStatus.ACTIVE);
        when(reviewRepository.findByIdForUpdate(reviewId)).thenReturn(Optional.of(review));

        UUID imposterId = UUID.randomUUID();
        BusinessException ex = assertThrows(BusinessException.class, () ->
                deleteReviewUseCase.execute(reviewId, imposterId, Instant.now()));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        assertEquals("REVIEW_NOT_OWNED", ex.getErrorCode());
        verify(reviewRepository, never()).save(any());
        verifyNoInteractions(rateableTargetStatsRepository);
        verifyNoInteractions(reputationService);
    }

    @Test
    @DisplayName("5. Review já REMOVED não pode ser removida novamente (erro específico)")
    void shouldRejectDeleteWhenReviewAlreadyRemoved() {
        Review review = createStandardReview(ReviewStatus.REMOVED);
        when(reviewRepository.findByIdForUpdate(reviewId)).thenReturn(Optional.of(review));

        BusinessException ex = assertThrows(BusinessException.class, () ->
                deleteReviewUseCase.execute(reviewId, authorId, Instant.now()));

        assertEquals("REVIEW_ALREADY_REMOVED", ex.getErrorCode());
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        verify(reviewRepository, never()).save(any());
        verifyNoInteractions(rateableTargetStatsRepository);
        verifyNoInteractions(reputationService);
    }

    @Test
    @DisplayName("6. Mídias ativas são marcadas como REMOVED logicamente sem apagar do storage")
    void shouldMarkActiveMediaAsRemovedWhenReviewMediaRepositoryPresent() {
        Review review = createStandardReview(ReviewStatus.ACTIVE);
        when(reviewRepository.findByIdForUpdate(reviewId)).thenReturn(Optional.of(review));
        when(reviewRepository.save(any(Review.class))).thenAnswer(inv -> inv.getArgument(0));

        ReviewMedia media1 = new ReviewMedia(UUID.randomUUID(), reviewId, authorId, "reviews/k1", ReviewMediaType.IMAGE, "image/jpeg", 1000L, 800, 600);
        ReviewMedia media2 = new ReviewMedia(UUID.randomUUID(), reviewId, authorId, "reviews/k2", ReviewMediaType.IMAGE, "image/jpeg", 2000L, 800, 600);
        when(reviewMediaRepository.findActiveByReviewId(reviewId)).thenReturn(List.of(media1, media2));

        deleteReviewUseCase.execute(reviewId, authorId, Instant.now());

        assertFalse(media1.isActive());
        assertFalse(media2.isActive());
        verify(reviewMediaRepository).save(media1);
        verify(reviewMediaRepository).save(media2);
    }

    @Test
    @DisplayName("7. Timestamp nulo no delete é rejeitado com BAD_REQUEST")
    void shouldRejectMissingTimestampInDelete() {
        BusinessException ex = assertThrows(BusinessException.class, () ->
                deleteReviewUseCase.execute(reviewId, authorId, null));

        assertEquals("MISSING_UPDATE_TIMESTAMP", ex.getErrorCode());
        verifyNoInteractions(reviewRepository);
    }
}
