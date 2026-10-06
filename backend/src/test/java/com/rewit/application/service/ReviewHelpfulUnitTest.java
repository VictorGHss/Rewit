package com.rewit.application.service;

import com.rewit.application.port.ReviewReactionRepository;
import com.rewit.application.port.ReviewRepository;
import com.rewit.application.port.UserFollowRepository;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.ReviewStatus;
import com.rewit.domain.model.Review;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Testes Unitários: ReviewHelpfulService e Validação de Utilidade (Step 16.0)")
class ReviewHelpfulUnitTest {

    @Mock
    private AccountStatusPolicy accountStatusPolicy;

    @Mock
    private ReviewReactionRepository reviewReactionRepository;

    @Mock
    private ReviewRepository reviewRepository;

    @Mock
    private UserFollowRepository userFollowRepository;

    private ReviewHelpfulService reviewHelpfulService;

    private final UUID authorUserId = UUID.randomUUID();
    private final UUID followerUserId = UUID.randomUUID();
    private final UUID otherUserId = UUID.randomUUID();
    private final UUID reviewId = UUID.randomUUID();
    private final UUID placeId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        reviewHelpfulService = new ReviewHelpfulService(
                reviewReactionRepository,
                reviewRepository,
                accountStatusPolicy,
                userFollowRepository
        );
    }

    private Review createReview(ReviewStatus status, String visibility, boolean isAnonymous) {
        return new Review(
                reviewId,
                authorUserId,
                placeId,
                "Texto de experiência",
                isAnonymous,
                true,
                status,
                visibility,
                -23.5505,
                -46.6333,
                10.0,
                Instant.now(),
                Instant.now()
        );
    }

    @Test
    @DisplayName("Deve registrar Helpful com sucesso em Review pública")
    void shouldAddHelpfulSuccessfullyOnPublicReview() {
        Review review = createReview(ReviewStatus.ACTIVE, "PUBLIC", false);
        when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(review));
        when(reviewReactionRepository.addHelpful(reviewId, otherUserId)).thenReturn(true);
        when(reviewReactionRepository.countHelpful(reviewId)).thenReturn(1L);

        ReviewHelpfulService.HelpfulResult result = reviewHelpfulService.addHelpful(reviewId, otherUserId);

        assertTrue(result.helpful());
        assertEquals(1L, result.helpfulCount());
        verify(reviewReactionRepository).addHelpful(reviewId, otherUserId);
    }

    @Test
    @DisplayName("Deve ser idempotente no POST repetido de Helpful")
    void shouldBeIdempotentOnRepeatedPost() {
        Review review = createReview(ReviewStatus.ACTIVE, "PUBLIC", false);
        when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(review));
        when(reviewReactionRepository.addHelpful(reviewId, otherUserId)).thenReturn(false); // Já existia
        when(reviewReactionRepository.countHelpful(reviewId)).thenReturn(1L);

        ReviewHelpfulService.HelpfulResult result = reviewHelpfulService.addHelpful(reviewId, otherUserId);

        assertTrue(result.helpful());
        assertEquals(1L, result.helpfulCount());
    }

    @Test
    @DisplayName("Deve remover marcação de Helpful com sucesso (DELETE)")
    void shouldRemoveHelpfulSuccessfully() {
        Review review = createReview(ReviewStatus.ACTIVE, "PUBLIC", false);
        when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(review));
        when(reviewReactionRepository.removeHelpful(reviewId, otherUserId)).thenReturn(true);
        when(reviewReactionRepository.countHelpful(reviewId)).thenReturn(0L);

        ReviewHelpfulService.HelpfulResult result = reviewHelpfulService.removeHelpful(reviewId, otherUserId);

        assertFalse(result.helpful());
        assertEquals(0L, result.helpfulCount());
        verify(reviewReactionRepository).removeHelpful(reviewId, otherUserId);
    }

    @Test
    @DisplayName("Deve ser idempotente no DELETE repetido de Helpful")
    void shouldBeIdempotentOnRepeatedDelete() {
        Review review = createReview(ReviewStatus.ACTIVE, "PUBLIC", false);
        when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(review));
        when(reviewReactionRepository.removeHelpful(reviewId, otherUserId)).thenReturn(false); // Não existia
        when(reviewReactionRepository.countHelpful(reviewId)).thenReturn(0L);

        ReviewHelpfulService.HelpfulResult result = reviewHelpfulService.removeHelpful(reviewId, otherUserId);

        assertFalse(result.helpful());
        assertEquals(0L, result.helpfulCount());
    }

    @Test
    @DisplayName("Deve rejeitar self-helpful quando autor tenta votar na própria Review")
    void shouldRejectSelfHelpful() {
        Review review = createReview(ReviewStatus.ACTIVE, "PUBLIC", false);
        when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(review));

        BusinessException ex = assertThrows(BusinessException.class, () ->
                reviewHelpfulService.addHelpful(reviewId, authorUserId));

        assertEquals("SELF_HELPFUL_FORBIDDEN", ex.getErrorCode());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        verify(reviewReactionRepository, never()).addHelpful(any(), any());
    }

    @Test
    @DisplayName("Deve retornar 404 quando a Review não existe")
    void shouldReturn404WhenReviewDoesNotExist() {
        when(reviewRepository.findById(reviewId)).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class, () ->
                reviewHelpfulService.addHelpful(reviewId, otherUserId));

        assertEquals("REVIEW_NOT_FOUND", ex.getErrorCode());
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }

    @Test
    @DisplayName("Deve rejeitar Helpful em Review com status UNDER_REVIEW")
    void shouldRejectHelpfulWhenReviewIsUnderReview() {
        Review review = createReview(ReviewStatus.UNDER_REVIEW, "PUBLIC", false);
        when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(review));

        BusinessException ex = assertThrows(BusinessException.class, () ->
                reviewHelpfulService.addHelpful(reviewId, otherUserId));

        assertEquals("REVIEW_NOT_FOUND", ex.getErrorCode());
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }

    @Test
    @DisplayName("Deve rejeitar Helpful em Review com status REMOVED")
    void shouldRejectHelpfulWhenReviewIsRemoved() {
        Review review = createReview(ReviewStatus.REMOVED, "PUBLIC", false);
        when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(review));

        BusinessException ex = assertThrows(BusinessException.class, () ->
                reviewHelpfulService.addHelpful(reviewId, otherUserId));

        assertEquals("REVIEW_NOT_FOUND", ex.getErrorCode());
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }

    @Test
    @DisplayName("Deve permitir Helpful em Review FOLLOWERS quando requester é seguidor ativo")
    void shouldAllowHelpfulOnFollowersReviewWhenRequesterIsFollower() {
        Review review = createReview(ReviewStatus.ACTIVE, "FOLLOWERS", false);
        when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(review));
        when(userFollowRepository.isFollowing(followerUserId, authorUserId)).thenReturn(true);
        when(reviewReactionRepository.addHelpful(reviewId, followerUserId)).thenReturn(true);
        when(reviewReactionRepository.countHelpful(reviewId)).thenReturn(3L);

        ReviewHelpfulService.HelpfulResult result = reviewHelpfulService.addHelpful(reviewId, followerUserId);

        assertTrue(result.helpful());
        assertEquals(3L, result.helpfulCount());
    }

    @Test
    @DisplayName("Deve rejeitar Helpful em Review FOLLOWERS quando requester NÃO segue o autor (403 FORBIDDEN)")
    void shouldRejectHelpfulOnFollowersReviewWhenRequesterIsNotFollower() {
        Review review = createReview(ReviewStatus.ACTIVE, "FOLLOWERS", false);
        when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(review));
        when(userFollowRepository.isFollowing(otherUserId, authorUserId)).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class, () ->
                reviewHelpfulService.addHelpful(reviewId, otherUserId));

        assertEquals("FORBIDDEN", ex.getErrorCode());
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
    }

    @Test
    @DisplayName("Deve rejeitar Helpful em Review PRIVATE de terceiros (403 FORBIDDEN)")
    void shouldRejectHelpfulOnPrivateReview() {
        Review review = createReview(ReviewStatus.ACTIVE, "PRIVATE", false);
        when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(review));

        BusinessException ex = assertThrows(BusinessException.class, () ->
                reviewHelpfulService.addHelpful(reviewId, otherUserId));

        assertEquals("FORBIDDEN", ex.getErrorCode());
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
    }

    @Test
    @DisplayName("Deve permitir Helpful em Review anônima preservando o mascaramento do autor")
    void shouldAllowHelpfulOnAnonymousReview() {
        Review review = createReview(ReviewStatus.ACTIVE, "PUBLIC", true);
        when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(review));
        when(reviewReactionRepository.addHelpful(reviewId, otherUserId)).thenReturn(true);
        when(reviewReactionRepository.countHelpful(reviewId)).thenReturn(5L);

        ReviewHelpfulService.HelpfulResult result = reviewHelpfulService.addHelpful(reviewId, otherUserId);

        assertTrue(result.helpful());
        assertEquals(5L, result.helpfulCount());
    }

    @Test
    @DisplayName("Deve rejeitar Helpful quando requester não é informado (401 UNAUTHORIZED)")
    void shouldRejectHelpfulWhenRequesterIsNull() {
        BusinessException ex = assertThrows(BusinessException.class, () ->
                reviewHelpfulService.addHelpful(reviewId, null));

        assertEquals("UNAUTHORIZED", ex.getErrorCode());
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus());
    }

    @Test
    @DisplayName("Deve rejeitar Helpful quando reviewId é nulo (400 BAD_REQUEST)")
    void shouldRejectHelpfulWhenReviewIdIsNull() {
        BusinessException ex = assertThrows(BusinessException.class, () ->
                reviewHelpfulService.addHelpful(null, otherUserId));

        assertEquals("MISSING_REVIEW_ID", ex.getErrorCode());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }
}
