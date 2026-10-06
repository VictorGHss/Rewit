package com.rewit.application.usecase;

import com.rewit.application.dto.ReviewDto.UpdateReviewCommand;
import com.rewit.application.port.RateableTargetStatsRepository;
import com.rewit.application.port.ReviewReactionRepository;
import com.rewit.application.port.ReviewRepository;
import com.rewit.application.port.ReviewTargetRepository;
import com.rewit.application.service.AccountStatusPolicy;
import com.rewit.application.service.ReputationService;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.ReviewStatus;
import com.rewit.domain.model.Review;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Testes Unitários: UpdateReviewUseCase (Step 25.2)")
class UpdateReviewUseCaseUnitTest {

    @Mock
    private AccountStatusPolicy accountStatusPolicy;

    @Mock
    private ReviewRepository reviewRepository;

    @Mock
    private ReviewTargetRepository reviewTargetRepository;

    @Mock
    private ReviewReactionRepository reviewReactionRepository;

    @Mock
    private RateableTargetStatsRepository rateableTargetStatsRepository;

    @Mock
    private ReputationService reputationService;

    private UpdateReviewUseCase updateReviewUseCase;

    private UUID authorId;
    private UUID reviewId;
    private UUID targetAId;
    private UUID targetBId;
    private Instant createdAt;

    @BeforeEach
    void setUp() {
        updateReviewUseCase = new UpdateReviewUseCase(
                reviewRepository,
                accountStatusPolicy,
                reviewTargetRepository,
                reviewReactionRepository,
                rateableTargetStatsRepository,
                reputationService
        );

        authorId = UUID.randomUUID();
        reviewId = UUID.randomUUID();
        targetAId = UUID.randomUUID();
        targetBId = UUID.randomUUID();
        createdAt = Instant.parse("2026-10-01T10:00:00Z");
    }

    private Review createStandardReview() {
        Review review = new Review(
                reviewId,
                authorId,
                null,
                "Texto original",
                false,
                false,
                ReviewStatus.ACTIVE,
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
    @DisplayName("1. Autor edita texto dentro de 24h com sucesso preservando createdAt e atualizando updatedAt")
    void shouldUpdateExperienceTextWithin24HoursPreservingCreatedAtAndUpdatingUpdatedAt() {
        Review review = createStandardReview();
        when(reviewRepository.findByIdForUpdate(reviewId)).thenReturn(Optional.of(review));
        when(reviewRepository.save(any(Review.class))).thenAnswer(inv -> inv.getArgument(0));

        Instant editTime = createdAt.plusSeconds(3600); // 1 hora depois
        UpdateReviewCommand command = new UpdateReviewCommand("Texto atualizado e revisado", null, null, null, editTime);

        Review updated = updateReviewUseCase.execute(reviewId, authorId, command);

        assertEquals("Texto atualizado e revisado", updated.getExperienceText());
        assertEquals(createdAt, updated.getCreatedAt());
        assertEquals(editTime, updated.getUpdatedAt());
        verify(reviewRepository).save(review);
        verifyNoInteractions(rateableTargetStatsRepository);
        verifyNoInteractions(reputationService);
        verifyNoInteractions(reviewTargetRepository);
    }

    @Test
    @DisplayName("2. Autor edita rating sem helpful com recálculo exclusivo do target alterado")
    void shouldUpdateTargetRatingWhenNoHelpfulVotesExist() {
        Review review = createStandardReview();
        when(reviewRepository.findByIdForUpdate(reviewId)).thenReturn(Optional.of(review));
        when(reviewReactionRepository.countHelpful(reviewId)).thenReturn(0L);
        when(reviewRepository.save(any(Review.class))).thenAnswer(inv -> inv.getArgument(0));

        Instant editTime = createdAt.plusSeconds(7200);
        UpdateReviewCommand command = new UpdateReviewCommand(
                null,
                Map.of(targetAId, new BigDecimal("4.5")),
                null,
                null,
                editTime
        );

        Review updated = updateReviewUseCase.execute(reviewId, authorId, command);

        assertEquals(new BigDecimal("4.5"), updated.getTargets().get(0).getRating());
        assertEquals(new BigDecimal("4.0"), updated.getTargets().get(1).getRating()); // Target B inalterado
        verify(reviewTargetRepository).saveAll(review.getTargets());
        verify(rateableTargetStatsRepository).recalculateAndSave(targetAId);
        verify(rateableTargetStatsRepository, never()).recalculateAndSave(targetBId);
        verifyNoInteractions(reputationService); // rating não altera reputação
    }

    @Test
    @DisplayName("3. Alteração de anonimato dispara recálculo de UserReputation")
    void shouldUpdateAnonymousAndTriggerReputationRecalculation() {
        Review review = createStandardReview();
        assertFalse(review.isAnonymous());

        when(reviewRepository.findByIdForUpdate(reviewId)).thenReturn(Optional.of(review));
        when(reviewRepository.save(any(Review.class))).thenAnswer(inv -> inv.getArgument(0));

        Instant editTime = createdAt.plusSeconds(1800);
        UpdateReviewCommand command = new UpdateReviewCommand(null, null, true, null, editTime);

        Review updated = updateReviewUseCase.execute(reviewId, authorId, command);

        assertTrue(updated.isAnonymous());
        verify(reputationService).recalculateAndSave(authorId);
        verifyNoInteractions(rateableTargetStatsRepository);
    }

    @Test
    @DisplayName("4. Alteração de visibilidade altera o campo sem disparar reputação ou stats")
    void shouldUpdateVisibilityPreservingOtherFields() {
        Review review = createStandardReview();
        when(reviewRepository.findByIdForUpdate(reviewId)).thenReturn(Optional.of(review));
        when(reviewRepository.save(any(Review.class))).thenAnswer(inv -> inv.getArgument(0));

        Instant editTime = createdAt.plusSeconds(1800);
        UpdateReviewCommand command = new UpdateReviewCommand(null, null, null, "PRIVATE", editTime);

        Review updated = updateReviewUseCase.execute(reviewId, authorId, command);

        assertEquals("PRIVATE", updated.getVisibility());
        verifyNoInteractions(reputationService);
        verifyNoInteractions(rateableTargetStatsRepository);
    }

    @Test
    @DisplayName("5. Requester diferente do autor é rejeitado com FORBIDDEN")
    void shouldRejectEditWhenRequesterIsNotAuthor() {
        Review review = createStandardReview();
        when(reviewRepository.findByIdForUpdate(reviewId)).thenReturn(Optional.of(review));

        UUID imposterId = UUID.randomUUID();
        UpdateReviewCommand command = new UpdateReviewCommand("Hacked", null, null, null, createdAt.plusSeconds(60));

        BusinessException ex = assertThrows(BusinessException.class, () ->
                updateReviewUseCase.execute(reviewId, imposterId, command));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        assertEquals("REVIEW_NOT_OWNED", ex.getErrorCode());
        verify(reviewRepository, never()).save(any());
        verifyNoInteractions(rateableTargetStatsRepository);
        verifyNoInteractions(reputationService);
    }

    @Test
    @DisplayName("6. Review inexistente é rejeitada com NOT_FOUND")
    void shouldRejectEditWhenReviewNotFound() {
        when(reviewRepository.findByIdForUpdate(reviewId)).thenReturn(Optional.empty());

        UpdateReviewCommand command = new UpdateReviewCommand("Texto", null, null, null, createdAt.plusSeconds(60));

        BusinessException ex = assertThrows(BusinessException.class, () ->
                updateReviewUseCase.execute(reviewId, authorId, command));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        assertEquals("REVIEW_NOT_FOUND", ex.getErrorCode());
    }

    @Test
    @DisplayName("7. Review sob moderação (UNDER_REVIEW) rejeita edição com erro específico")
    void shouldRejectEditWhenReviewIsUnderReview() {
        Review review = new Review(
                reviewId, authorId, null, "Texto", false, false,
                ReviewStatus.UNDER_REVIEW, "PUBLIC", null, null, null, createdAt, createdAt
        );
        when(reviewRepository.findByIdForUpdate(reviewId)).thenReturn(Optional.of(review));

        UpdateReviewCommand command = new UpdateReviewCommand("Tentando burlar moderação", null, null, null, createdAt.plusSeconds(60));

        BusinessException ex = assertThrows(BusinessException.class, () ->
                updateReviewUseCase.execute(reviewId, authorId, command));

        assertEquals("REVIEW_UNDER_REVIEW_MUTATION_DENIED", ex.getErrorCode());
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        verify(reviewRepository, never()).save(any());
    }

    @Test
    @DisplayName("8. Review já removida (REMOVED) rejeita qualquer tentativa de edição")
    void shouldRejectEditWhenReviewIsRemoved() {
        Review review = new Review(
                reviewId, authorId, null, "Texto", false, false,
                ReviewStatus.REMOVED, "PUBLIC", null, null, null, createdAt, createdAt
        );
        when(reviewRepository.findByIdForUpdate(reviewId)).thenReturn(Optional.of(review));

        UpdateReviewCommand command = new UpdateReviewCommand("Tentando editar removida", null, null, null, createdAt.plusSeconds(60));

        BusinessException ex = assertThrows(BusinessException.class, () ->
                updateReviewUseCase.execute(reviewId, authorId, command));

        assertEquals("REVIEW_ALREADY_REMOVED", ex.getErrorCode());
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        verify(reviewRepository, never()).save(any());
    }

    @Test
    @DisplayName("9. Edição no limite exato de 24 horas (24h 00m 00s) é permitida")
    void shouldAllowEditAtExactly24HoursBoundary() {
        Review review = createStandardReview();
        when(reviewRepository.findByIdForUpdate(reviewId)).thenReturn(Optional.of(review));
        when(reviewRepository.save(any(Review.class))).thenAnswer(inv -> inv.getArgument(0));

        Instant exact24h = createdAt.plus(24, java.time.temporal.ChronoUnit.HOURS);
        UpdateReviewCommand command = new UpdateReviewCommand("Edição no último segundo", null, null, null, exact24h);

        assertDoesNotThrow(() -> updateReviewUseCase.execute(reviewId, authorId, command));
        verify(reviewRepository).save(review);
    }

    @Test
    @DisplayName("10. Edição após a janela de 24 horas (24h + 1ns) é rejeitada")
    void shouldRejectEditWhen24HoursWindowHasExpired() {
        Review review = createStandardReview();
        when(reviewRepository.findByIdForUpdate(reviewId)).thenReturn(Optional.of(review));

        Instant expiredTime = createdAt.plus(24, java.time.temporal.ChronoUnit.HOURS).plusNanos(1);
        UpdateReviewCommand command = new UpdateReviewCommand("Edição expirada", null, null, null, expiredTime);

        BusinessException ex = assertThrows(BusinessException.class, () ->
                updateReviewUseCase.execute(reviewId, authorId, command));

        assertEquals("REVIEW_EDIT_WINDOW_EXPIRED", ex.getErrorCode());
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        verify(reviewRepository, never()).save(any());
    }

    @Test
    @DisplayName("11. Timestamp da edição anterior à criação é rejeitado como inválido")
    void shouldRejectEditWhenNowIsBeforeCreatedAt() {
        Review review = createStandardReview();
        when(reviewRepository.findByIdForUpdate(reviewId)).thenReturn(Optional.of(review));

        Instant pastTime = createdAt.minusSeconds(10);
        UpdateReviewCommand command = new UpdateReviewCommand("Edição no passado", null, null, null, pastTime);

        BusinessException ex = assertThrows(BusinessException.class, () ->
                updateReviewUseCase.execute(reviewId, authorId, command));

        assertEquals("INVALID_UPDATE_TIMESTAMP", ex.getErrorCode());
        verify(reviewRepository, never()).save(any());
    }

    @Test
    @DisplayName("12. Alteração de rating em review que possui Helpful votes é bloqueada")
    void shouldRejectRatingEditWhenReviewHasHelpfulVotes() {
        Review review = createStandardReview();
        when(reviewRepository.findByIdForUpdate(reviewId)).thenReturn(Optional.of(review));
        when(reviewReactionRepository.countHelpful(reviewId)).thenReturn(3L); // Possui 3 votos úteis

        UpdateReviewCommand command = new UpdateReviewCommand(
                "Texto pode mudar",
                Map.of(targetAId, new BigDecimal("5.0")),
                null,
                null,
                createdAt.plusSeconds(3600)
        );

        BusinessException ex = assertThrows(BusinessException.class, () ->
                updateReviewUseCase.execute(reviewId, authorId, command));

        assertEquals("REVIEW_EDIT_RATING_BLOCKED_BY_HELPFUL", ex.getErrorCode());
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        verify(reviewRepository, never()).save(any());
        verifyNoInteractions(rateableTargetStatsRepository);
    }

    @Test
    @DisplayName("13. Edição apenas de texto em review com Helpful é permitida normalmente")
    void shouldAllowTextEditEvenWhenReviewHasHelpfulVotes() {
        Review review = createStandardReview();
        when(reviewRepository.findByIdForUpdate(reviewId)).thenReturn(Optional.of(review));
        when(reviewRepository.save(any(Review.class))).thenAnswer(inv -> inv.getArgument(0));

        // Não altera ratings: targetRatings vazio
        UpdateReviewCommand command = new UpdateReviewCommand("Texto atualizado com helpful mantido", Map.of(), null, null, createdAt.plusSeconds(3600));

        assertDoesNotThrow(() -> updateReviewUseCase.execute(reviewId, authorId, command));
        verify(reviewRepository).save(review);
        verify(reviewReactionRepository, never()).countHelpful(any()); // Nem precisa consultar helpful se não há alteração de nota
    }

    @Test
    @DisplayName("14. Target inexistente na review é rejeitado com TARGET_NOT_FOUND")
    void shouldRejectTargetRatingForTargetNotBelongingToReview() {
        Review review = createStandardReview();
        when(reviewRepository.findByIdForUpdate(reviewId)).thenReturn(Optional.of(review));

        UUID unrelatedTargetId = UUID.randomUUID();
        UpdateReviewCommand command = new UpdateReviewCommand(null, Map.of(unrelatedTargetId, new BigDecimal("5.0")), null, null, createdAt.plusSeconds(60));

        BusinessException ex = assertThrows(BusinessException.class, () ->
                updateReviewUseCase.execute(reviewId, authorId, command));

        assertEquals("TARGET_NOT_FOUND", ex.getErrorCode());
        verify(reviewRepository, never()).save(any());
    }

    @Test
    @DisplayName("15. Múltiplos targets alterados são recalculados em ordem determinística (targetId ASC)")
    void shouldSortTargetsByAscendingOrderWhenRecalculatingStats() {
        Review review = createStandardReview();
        when(reviewRepository.findByIdForUpdate(reviewId)).thenReturn(Optional.of(review));
        when(reviewReactionRepository.countHelpful(reviewId)).thenReturn(0L);
        when(reviewRepository.save(any(Review.class))).thenAnswer(inv -> inv.getArgument(0));

        UUID lowerId = targetAId.compareTo(targetBId) < 0 ? targetAId : targetBId;
        UUID higherId = targetAId.compareTo(targetBId) < 0 ? targetBId : targetAId;

        UpdateReviewCommand command = new UpdateReviewCommand(
                null,
                Map.of(higherId, new BigDecimal("5.0"), lowerId, new BigDecimal("1.0")),
                null,
                null,
                createdAt.plusSeconds(600)
        );

        updateReviewUseCase.execute(reviewId, authorId, command);

        InOrder inOrder = inOrder(rateableTargetStatsRepository);
        inOrder.verify(rateableTargetStatsRepository).recalculateAndSave(lowerId);
        inOrder.verify(rateableTargetStatsRepository).recalculateAndSave(higherId);
    }

    @Test
    @DisplayName("16. Reenvio de mesma nota não dispara recálculo de stats")
    void shouldNotRecalculateStatsIfTargetRatingWasResubmittedWithSameValue() {
        Review review = createStandardReview();
        when(reviewRepository.findByIdForUpdate(reviewId)).thenReturn(Optional.of(review));
        when(reviewRepository.save(any(Review.class))).thenAnswer(inv -> inv.getArgument(0));

        // targetA já tem nota 3.0 no fixture
        UpdateReviewCommand command = new UpdateReviewCommand(
                "Novo texto",
                Map.of(targetAId, new BigDecimal("3.0")),
                null,
                null,
                createdAt.plusSeconds(600)
        );

        updateReviewUseCase.execute(reviewId, authorId, command);

        verify(reviewRepository).save(review);
        verifyNoInteractions(rateableTargetStatsRepository);
        verify(reviewReactionRepository, never()).countHelpful(any());
    }

    @Test
    @DisplayName("17. Timestamp nulo no comando é rejeitado com BAD_REQUEST")
    void shouldRejectMissingUpdateTimestamp() {
        UpdateReviewCommand command = new UpdateReviewCommand("Texto", null, null, null, null);

        BusinessException ex = assertThrows(BusinessException.class, () ->
                updateReviewUseCase.execute(reviewId, authorId, command));

        assertEquals("MISSING_UPDATE_TIMESTAMP", ex.getErrorCode());
        verifyNoInteractions(reviewRepository);
    }
}
