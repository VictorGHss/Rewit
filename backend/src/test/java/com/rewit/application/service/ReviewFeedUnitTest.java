package com.rewit.application.service;

import com.rewit.application.dto.ReviewDto.ReviewPublicView;
import com.rewit.application.dto.common.PageResult;
import com.rewit.application.port.*;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.ReviewStatus;
import com.rewit.domain.model.Profile;
import com.rewit.domain.model.Review;
import com.rewit.domain.model.ReviewTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Testes Unitários: ReviewService.findFeed e Timeline Social (Step 17.0)")
class ReviewFeedUnitTest {

    @Mock
    private ReviewRepository reviewRepository;

    @Mock
    private ReviewTargetRepository reviewTargetRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private RateableTargetRepository rateableTargetRepository;

    @Mock
    private PlaceRepository placeRepository;

    @Mock
    private ProfileRepository profileRepository;

    @Mock
    private CheckInRepository checkInRepository;

    @Mock
    private RateableTargetStatsRepository rateableTargetStatsRepository;

    @Mock
    private UserFollowRepository userFollowRepository;

    @Mock
    private ReviewReactionRepository reviewReactionRepository;

    private ReviewService reviewService;

    private final UUID requesterUserId = UUID.randomUUID();
    private final UUID followedUserA = UUID.randomUUID();
    private final UUID followedUserB = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        reviewService = new ReviewService(
                reviewRepository,
                reviewTargetRepository,
                userRepository,
                rateableTargetRepository,
                placeRepository,
                profileRepository,
                checkInRepository,
                rateableTargetStatsRepository,
                userFollowRepository,
                reviewReactionRepository
        );
    }

    private Review createReview(UUID authorId, String visibility, ReviewStatus status, boolean isAnonymous, Instant createdAt) {
        return new Review(
                UUID.randomUUID(),
                authorId,
                UUID.randomUUID(),
                "Texto da review",
                isAnonymous,
                false,
                status,
                visibility,
                -23.5505,
                -46.6333,
                10.0,
                createdAt != null ? createdAt : Instant.now(),
                Instant.now()
        );
    }

    @Test
    @DisplayName("1. Retorna feed vazio quando o usuário não segue ninguém ou não há publicações")
    void shouldReturnEmptyFeedWhenNoReviewsFound() {
        when(reviewRepository.findFeedByFollowing(requesterUserId, 0, 10))
                .thenReturn(PageResult.of(List.of(), 0, 10, 0));

        PageResult<ReviewPublicView> result = reviewService.findFeed(requesterUserId, 0, 10, null);

        assertNotNull(result);
        assertTrue(result.content().isEmpty());
        assertEquals(0, result.totalElements());
    }

    @Test
    @DisplayName("2. Retorna feed quando requester segue um único usuário")
    void shouldReturnFeedWhenRequesterFollowsSingleUser() {
        Review review = createReview(followedUserA, "PUBLIC", ReviewStatus.ACTIVE, false, Instant.now());
        when(reviewRepository.findFeedByFollowing(requesterUserId, 0, 10))
                .thenReturn(PageResult.of(List.of(review), 0, 10, 1));
        when(profileRepository.findByUserIdIn(Set.of(followedUserA)))
                .thenReturn(List.of(new Profile(UUID.randomUUID(), followedUserA, "user_a", "User A", "Bio", null)));

        PageResult<ReviewPublicView> result = reviewService.findFeed(requesterUserId, 0, 10, "newest");

        assertEquals(1, result.content().size());
        assertEquals(review.getId(), result.content().get(0).id());
        assertEquals("user_a", result.content().get(0).author().handle());
    }

    @Test
    @DisplayName("3. Retorna feed quando requester segue múltiplos usuários")
    void shouldReturnFeedWhenRequesterFollowsMultipleUsers() {
        Review r1 = createReview(followedUserA, "PUBLIC", ReviewStatus.ACTIVE, false, Instant.now().minusSeconds(10));
        Review r2 = createReview(followedUserB, "FOLLOWERS", ReviewStatus.ACTIVE, false, Instant.now());
        when(reviewRepository.findFeedByFollowing(requesterUserId, 0, 10))
                .thenReturn(PageResult.of(List.of(r2, r1), 0, 10, 2));

        when(profileRepository.findByUserIdIn(any()))
                .thenReturn(List.of(
                        new Profile(UUID.randomUUID(), followedUserA, "user_a", "User A", null, null),
                        new Profile(UUID.randomUUID(), followedUserB, "user_b", "User B", null, null)
                ));

        PageResult<ReviewPublicView> result = reviewService.findFeed(requesterUserId, 0, 10, null);

        assertEquals(2, result.content().size());
        assertEquals(r2.getId(), result.content().get(0).id());
        assertEquals(r1.getId(), result.content().get(1).id());
    }

    @Test
    @DisplayName("4. Rejeita ordenação diferente de newest (400 INVALID_SORT)")
    void shouldRejectInvalidSortParameter() {
        BusinessException ex = assertThrows(BusinessException.class, () ->
                reviewService.findFeed(requesterUserId, 0, 10, "rating"));

        assertEquals("INVALID_SORT", ex.getErrorCode());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    @DisplayName("5. Rejeita página negativa (400 INVALID_PAGE)")
    void shouldRejectNegativePage() {
        BusinessException ex = assertThrows(BusinessException.class, () ->
                reviewService.findFeed(requesterUserId, -1, 10, null));

        assertEquals("INVALID_PAGE", ex.getErrorCode());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    @DisplayName("6. Rejeita tamanho de página menor ou igual a zero (400 INVALID_SIZE)")
    void shouldRejectZeroOrNegativeSize() {
        BusinessException ex = assertThrows(BusinessException.class, () ->
                reviewService.findFeed(requesterUserId, 0, 0, null));

        assertEquals("INVALID_SIZE", ex.getErrorCode());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    @DisplayName("7. Rejeita tamanho de página superior a 50 (400 PAGE_SIZE_EXCEEDED)")
    void shouldRejectSizeExceeding50() {
        BusinessException ex = assertThrows(BusinessException.class, () ->
                reviewService.findFeed(requesterUserId, 0, 51, null));

        assertEquals("PAGE_SIZE_EXCEEDED", ex.getErrorCode());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    @DisplayName("8. Rejeita requisição quando requesterUserId for nulo (401 UNAUTHORIZED)")
    void shouldRejectUnauthenticatedRequest() {
        BusinessException ex = assertThrows(BusinessException.class, () ->
                reviewService.findFeed(null, 0, 10, null));

        assertEquals("UNAUTHORIZED", ex.getErrorCode());
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus());
    }

    @Test
    @DisplayName("9. Preserva anonimização da Review no feed mascarando autor")
    void shouldAnonymizeAuthorWhenReviewIsAnonymous() {
        Review review = createReview(followedUserA, "PUBLIC", ReviewStatus.ACTIVE, true, Instant.now());
        when(reviewRepository.findFeedByFollowing(requesterUserId, 0, 10))
                .thenReturn(PageResult.of(List.of(review), 0, 10, 1));

        PageResult<ReviewPublicView> result = reviewService.findFeed(requesterUserId, 0, 10, null);

        assertEquals(1, result.content().size());
        ReviewPublicView view = result.content().get(0);
        assertTrue(view.isAnonymous());
        assertTrue(view.author().isAnonymous());
        assertEquals("Anônimo", view.author().displayName());
        assertNull(view.author().handle());
        assertNull(view.author().id());
        verify(profileRepository, never()).findByUserIdIn(any());
    }

    @Test
    @DisplayName("10. Enriquece as avaliações do feed com helpfulCount e isHelpfulByMe em lote")
    void shouldEnrichFeedWithHelpfulMetadataInBatch() {
        Review r1 = createReview(followedUserA, "PUBLIC", ReviewStatus.ACTIVE, false, Instant.now());
        Review r2 = createReview(followedUserB, "PUBLIC", ReviewStatus.ACTIVE, false, Instant.now().minusSeconds(5));

        when(reviewRepository.findFeedByFollowing(requesterUserId, 0, 10))
                .thenReturn(PageResult.of(List.of(r1, r2), 0, 10, 2));

        when(reviewReactionRepository.countHelpfulByReviewIds(List.of(r1.getId(), r2.getId())))
                .thenReturn(Map.of(r1.getId(), 5L, r2.getId(), 0L));

        when(reviewReactionRepository.findHelpfulReviewIdsByUser(List.of(r1.getId(), r2.getId()), requesterUserId))
                .thenReturn(Set.of(r1.getId()));

        PageResult<ReviewPublicView> result = reviewService.findFeed(requesterUserId, 0, 10, null);

        assertEquals(2, result.content().size());
        assertEquals(5L, result.content().get(0).helpfulCount());
        assertTrue(result.content().get(0).isHelpfulByMe());

        assertEquals(0L, result.content().get(1).helpfulCount());
        assertFalse(result.content().get(1).isHelpfulByMe());
    }

    @Test
    @DisplayName("11. Preserva múltiplos alvos (multi-target) da Review no feed")
    void shouldPreserveMultipleTargetsInFeed() {
        Review review = createReview(followedUserA, "PUBLIC", ReviewStatus.ACTIVE, false, Instant.now());
        UUID placeTargetId = UUID.randomUUID();
        UUID productTargetId = UUID.randomUUID();

        ReviewTarget target1 = new ReviewTarget(UUID.randomUUID(), review.getId(), placeTargetId, BigDecimal.valueOf(4.0), "Bom ambiente");
        ReviewTarget target2 = new ReviewTarget(UUID.randomUUID(), review.getId(), productTargetId, BigDecimal.valueOf(5.0), "Excelente prato");

        when(reviewRepository.findFeedByFollowing(requesterUserId, 0, 10))
                .thenReturn(PageResult.of(List.of(review), 0, 10, 1));
        when(reviewTargetRepository.findByReviewIdIn(List.of(review.getId())))
                .thenReturn(List.of(target1, target2));

        PageResult<ReviewPublicView> result = reviewService.findFeed(requesterUserId, 0, 10, null);

        assertEquals(1, result.content().size());
        assertEquals(2, result.content().get(0).targets().size());
        assertEquals(BigDecimal.valueOf(4.0), result.content().get(0).targets().get(0).rating());
        assertEquals(BigDecimal.valueOf(5.0), result.content().get(0).targets().get(1).rating());
    }

    @Test
    @DisplayName("12. Empate determinístico: reviews com mesmo createdAt mantêm ordem por id ASC")
    void shouldPreserveDeterministicTieBreakOrderingByIdAsc() {
        Instant sameCreatedAt = Instant.parse("2026-09-28T20:00:00Z");
        UUID id1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID id2 = UUID.fromString("00000000-0000-0000-0000-000000000002");

        UUID placeId = UUID.randomUUID();
        Review r1 = new Review(id1, followedUserA, placeId, "R1", false, false, ReviewStatus.ACTIVE, "PUBLIC", 0.0, 0.0, null, sameCreatedAt, sameCreatedAt);
        Review r2 = new Review(id2, followedUserB, placeId, "R2", false, false, ReviewStatus.ACTIVE, "PUBLIC", 0.0, 0.0, null, sameCreatedAt, sameCreatedAt);

        when(reviewRepository.findFeedByFollowing(requesterUserId, 0, 10))
                .thenReturn(PageResult.of(List.of(r1, r2), 0, 10, 2));

        when(profileRepository.findByUserIdIn(any()))
                .thenReturn(List.of(
                        new Profile(UUID.randomUUID(), followedUserA, "user_a", "User A", null, null),
                        new Profile(UUID.randomUUID(), followedUserB, "user_b", "User B", null, null)
                ));

        PageResult<ReviewPublicView> result = reviewService.findFeed(requesterUserId, 0, 10, "newest");

        assertEquals(2, result.content().size());
        assertEquals(id1, result.content().get(0).id());
        assertEquals(id2, result.content().get(1).id());
    }

    @Test
    @DisplayName("13. Exclui avaliações do próprio requester do Feed")
    void shouldNotIncludeReviewsAuthoredByRequester() {
        // A porta de repositório seleciona apenas avaliações de seguidos (user_follows)
        // Se o repositório retornar vazio ou apenas de seguidos, o feed do requester nunca deve ter suas próprias avaliações
        Review rFollowed = createReview(followedUserA, "PUBLIC", ReviewStatus.ACTIVE, false, Instant.now());
        when(reviewRepository.findFeedByFollowing(requesterUserId, 0, 10))
                .thenReturn(PageResult.of(List.of(rFollowed), 0, 10, 1));
        when(profileRepository.findByUserIdIn(any()))
                .thenReturn(List.of(new Profile(UUID.randomUUID(), followedUserA, "user_a", "User A", null, null)));

        PageResult<ReviewPublicView> result = reviewService.findFeed(requesterUserId, 0, 10, null);

        assertEquals(1, result.content().size());
        assertEquals(rFollowed.getId(), result.content().get(0).id());
        assertFalse(result.content().stream().anyMatch(r -> r.author().id() != null && r.author().id().equals(requesterUserId)));
    }
}
