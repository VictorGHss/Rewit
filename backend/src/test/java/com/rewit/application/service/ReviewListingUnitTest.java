package com.rewit.application.service;

import com.rewit.application.dto.ReviewDto.ReviewPublicView;
import com.rewit.application.dto.common.PageResult;
import com.rewit.application.port.ProfileRepository;
import com.rewit.application.port.RateableTargetRepository;
import com.rewit.application.port.ReviewRepository;
import com.rewit.application.port.ReviewRepository.ReviewWithTarget;
import com.rewit.application.port.ReviewTargetRepository;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Testes Unitários - Listagem e Paginação de Reviews (Step 14.0)")
class ReviewListingUnitTest {

    @Mock
    private ReviewRepository reviewRepository;

    @Mock
    private ReviewTargetRepository reviewTargetRepository;

    @Mock
    private com.rewit.application.port.UserRepository userRepository;

    @Mock
    private RateableTargetRepository rateableTargetRepository;

    @Mock
    private com.rewit.application.port.PlaceRepository placeRepository;

    @Mock
    private ProfileRepository profileRepository;

    @Mock
    private com.rewit.application.port.CheckInRepository checkInRepository;

    @Mock
    private com.rewit.application.port.RateableTargetStatsRepository rateableTargetStatsRepository;

    private ReviewService reviewService;

    private UUID targetId;
    private UUID requesterUserId;
    private UUID authorUserId;

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
                rateableTargetStatsRepository
        );
        targetId = UUID.randomUUID();
        requesterUserId = UUID.randomUUID();
        authorUserId = UUID.randomUUID();
    }

    @Test
    @DisplayName("1. Lança 404 quando RateableTarget não existe")
    void shouldThrow404WhenTargetNotFound() {
        when(rateableTargetRepository.existsPubliclyVisibleById(targetId)).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class, () ->
                reviewService.findReviewsByTarget(targetId, 0, 10, "newest", false, requesterUserId)
        );

        assertEquals("RATEABLE_TARGET_NOT_FOUND", ex.getErrorCode());
        assertEquals(404, ex.getStatus().value());
        verifyNoInteractions(reviewRepository);
    }

    @Test
    @DisplayName("2. Retorna página vazia quando RateableTarget existe mas não tem reviews")
    void shouldReturnEmptyPageWhenTargetHasNoReviews() {
        when(rateableTargetRepository.existsPubliclyVisibleById(targetId)).thenReturn(true);
        when(reviewRepository.findByTarget(eq(targetId), eq(requesterUserId), eq(false), eq("newest"), eq(0), eq(10)))
                .thenReturn(PageResult.of(List.of(), 0, 10, 0));

        PageResult<ReviewPublicView> result = reviewService.findReviewsByTarget(
                targetId, 0, 10, "newest", false, requesterUserId
        );

        assertNotNull(result);
        assertTrue(result.content().isEmpty());
        assertEquals(0, result.totalElements());
        assertEquals(0, result.totalPages());
        assertEquals(0, result.pageNumber());
        assertEquals(10, result.pageSize());
        assertTrue(result.isLast());
    }

    @Test
    @DisplayName("3. Rejeita parâmetros de paginação inválidos (page < 0)")
    void shouldRejectNegativePage() {
        BusinessException ex = assertThrows(BusinessException.class, () ->
                reviewService.findReviewsByTarget(targetId, -1, 10, "newest", false, requesterUserId)
        );
        assertEquals("INVALID_PAGE", ex.getErrorCode());
    }

    @Test
    @DisplayName("4. Rejeita parâmetros de paginação inválidos (size <= 0)")
    void shouldRejectZeroOrNegativeSize() {
        BusinessException ex = assertThrows(BusinessException.class, () ->
                reviewService.findReviewsByTarget(targetId, 0, 0, "newest", false, requesterUserId)
        );
        assertEquals("INVALID_SIZE", ex.getErrorCode());
    }

    @Test
    @DisplayName("5. Rejeita tamanho de página acima do limite (size > 50)")
    void shouldRejectExceededPageSize() {
        BusinessException ex = assertThrows(BusinessException.class, () ->
                reviewService.findReviewsByTarget(targetId, 0, 51, "newest", false, requesterUserId)
        );
        assertEquals("PAGE_SIZE_EXCEEDED", ex.getErrorCode());
    }

    @Test
    @DisplayName("6. Rejeita ordenação inválida")
    void shouldRejectInvalidSort() {
        BusinessException ex = assertThrows(BusinessException.class, () ->
                reviewService.findReviewsByTarget(targetId, 0, 10, "random_sort", false, requesterUserId)
        );
        assertEquals("INVALID_SORT", ex.getErrorCode());
    }

    @Test
    @DisplayName("7. Permite ordenações válidas: newest, rating_desc, rating_asc")
    void shouldAllowValidSortOptions() {
        when(rateableTargetRepository.existsPubliclyVisibleById(targetId)).thenReturn(true);
        when(reviewRepository.findByTarget(any(), any(), anyBoolean(), anyString(), anyInt(), anyInt()))
                .thenReturn(PageResult.of(List.of(), 0, 10, 0));

        assertDoesNotThrow(() -> reviewService.findReviewsByTarget(targetId, 0, 10, "newest", false, requesterUserId));
        assertDoesNotThrow(() -> reviewService.findReviewsByTarget(targetId, 0, 10, "rating_desc", false, requesterUserId));
        assertDoesNotThrow(() -> reviewService.findReviewsByTarget(targetId, 0, 10, "rating_asc", false, requesterUserId));
        assertDoesNotThrow(() -> reviewService.findReviewsByTarget(targetId, 0, 10, null, false, requesterUserId));
    }

    @Test
    @DisplayName("8. Anonimização em lote: autor mascarado com 'Anônimo' e dados sigilosos ocultos")
    void shouldMaskAnonymousAuthorsInBatch() {
        when(rateableTargetRepository.existsPubliclyVisibleById(targetId)).thenReturn(true);

        Review anonymousReview = new Review(
                UUID.randomUUID(), authorUserId, null, "Experiência anônima",
                true, false, ReviewStatus.ACTIVE, "PUBLIC", null, null, null,
                Instant.now(), Instant.now()
        );
        ReviewTarget target = new ReviewTarget(UUID.randomUUID(), anonymousReview.getId(), targetId, new BigDecimal("4.5"), "Nota boa");

        when(reviewRepository.findByTarget(eq(targetId), eq(requesterUserId), eq(false), eq("newest"), eq(0), eq(10)))
                .thenReturn(PageResult.of(List.of(new ReviewWithTarget(anonymousReview, target)), 0, 10, 1));

        PageResult<ReviewPublicView> result = reviewService.findReviewsByTarget(
                targetId, 0, 10, "newest", false, requesterUserId
        );

        assertEquals(1, result.content().size());
        ReviewPublicView view = result.content().getFirst();
        assertTrue(view.isAnonymous());
        assertEquals("Anônimo", view.author().displayName());
        assertNull(view.author().id());
        assertNull(view.author().handle());
        assertNull(view.author().avatarUrl());

        // Não deve ter consultado profile para autor anônimo
        verify(profileRepository, never()).findByUserIdIn(any());
    }

    @Test
    @DisplayName("9. Autor público tem perfil resolvido em lote sem N+1")
    void shouldResolvePublicAuthorProfilesInBatch() {
        when(rateableTargetRepository.existsPubliclyVisibleById(targetId)).thenReturn(true);

        Review publicReview = new Review(
                UUID.randomUUID(), authorUserId, null, "Excelente lugar",
                false, true, ReviewStatus.ACTIVE, "PUBLIC", null, null, null,
                Instant.now(), Instant.now()
        );
        ReviewTarget target = new ReviewTarget(UUID.randomUUID(), publicReview.getId(), targetId, new BigDecimal("5.0"), "Perfeito");

        when(reviewRepository.findByTarget(eq(targetId), eq(requesterUserId), eq(false), eq("newest"), eq(0), eq(10)))
                .thenReturn(PageResult.of(List.of(new ReviewWithTarget(publicReview, target)), 0, 10, 1));

        Profile profile = new Profile(UUID.randomUUID(), authorUserId, "joao_silva", "João Silva", "Bio", "https://avatar");
        when(profileRepository.findByUserIdIn(Set.of(authorUserId))).thenReturn(List.of(profile));

        PageResult<ReviewPublicView> result = reviewService.findReviewsByTarget(
                targetId, 0, 10, "newest", false, requesterUserId
        );

        assertEquals(1, result.content().size());
        ReviewPublicView view = result.content().getFirst();
        assertFalse(view.isAnonymous());
        assertEquals("João Silva", view.author().displayName());
        assertEquals("joao_silva", view.author().handle());
        assertEquals("https://avatar", view.author().avatarUrl());
        assertEquals(authorUserId, view.author().id());
        assertTrue(view.isVerifiedOnSite());

        // Target da review contém apenas o alvo consultado
        assertEquals(1, view.targets().size());
        assertEquals(targetId, view.targets().getFirst().targetId());
        assertEquals(new BigDecimal("5.0"), view.targets().getFirst().rating());
    }

    @Test
    @DisplayName("10. /me/reviews exige usuário autenticado")
    void shouldRequireAuthenticatedUserInFindMyReviews() {
        BusinessException ex = assertThrows(BusinessException.class, () ->
                reviewService.findMyReviews(null, 0, 10)
        );
        assertEquals("UNAUTHORIZED", ex.getErrorCode());
        assertEquals(401, ex.getStatus().value());
    }

    @Test
    @DisplayName("11. /me/reviews retorna avaliações com alvos agregados e perfil do usuário")
    void shouldReturnMyReviewsWithAllTargetsAndProfile() {
        UUID reviewId = UUID.randomUUID();
        Review myReview = new Review(
                reviewId, authorUserId, null, "Minha avaliação",
                false, false, ReviewStatus.ACTIVE, "PRIVATE", null, null, null,
                Instant.now(), Instant.now()
        );
        ReviewTarget target1 = new ReviewTarget(UUID.randomUUID(), reviewId, targetId, new BigDecimal("4.0"), "Comentário");
        ReviewTarget target2 = new ReviewTarget(UUID.randomUUID(), reviewId, UUID.randomUUID(), new BigDecimal("3.5"), "Outro");

        when(reviewRepository.findByUserIdPaged(eq(authorUserId), eq(0), eq(10)))
                .thenReturn(PageResult.of(List.of(myReview), 0, 10, 1));
        when(reviewTargetRepository.findByReviewIdIn(List.of(reviewId)))
                .thenReturn(List.of(target1, target2));

        Profile profile = new Profile(UUID.randomUUID(), authorUserId, "me_user", "Meu Nome", "Bio", null);
        when(profileRepository.findByUserId(authorUserId)).thenReturn(Optional.of(profile));

        PageResult<ReviewPublicView> result = reviewService.findMyReviews(authorUserId, 0, 10);

        assertEquals(1, result.content().size());
        ReviewPublicView view = result.content().getFirst();
        assertEquals("Minha avaliação", view.experienceText());
        assertEquals("PRIVATE", view.visibility());
        assertEquals(2, view.targets().size());
        assertEquals("Meu Nome", view.author().displayName());
        assertEquals("me_user", view.author().handle());
    }

    @Test
    @DisplayName("12. Auditoria de Visibilidade - Casos A a E: Equivalência entre GET individual e listagem por target")
    void shouldValidateVisibilityCasesAtoE() {
        UUID reviewId = UUID.randomUUID();
        when(rateableTargetRepository.existsPubliclyVisibleById(targetId)).thenReturn(true);

        ReviewTarget target = new ReviewTarget(UUID.randomUUID(), reviewId, targetId, new BigDecimal("4.5"), "Comentário");
        Profile authorProfile = new Profile(UUID.randomUUID(), authorUserId, "author", "Author Name", "Bio", null);
        when(profileRepository.findByUserId(authorUserId)).thenReturn(Optional.of(authorProfile));
        when(profileRepository.findByUserIdIn(Set.of(authorUserId))).thenReturn(List.of(authorProfile));

        // Caso A: PUBLIC, requester terceiro
        Review publicReview = new Review(reviewId, authorUserId, null, "Public text", false, false, ReviewStatus.ACTIVE, "PUBLIC", null, null, null, Instant.now(), Instant.now());
        publicReview.addRehydratedTarget(target);
        when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(publicReview));
        ReviewPublicView getPublicInd = reviewService.getReviewPublicView(reviewId, requesterUserId);
        assertNotNull(getPublicInd);

        when(reviewRepository.findByTarget(eq(targetId), eq(requesterUserId), eq(false), eq("newest"), eq(0), eq(10)))
                .thenReturn(PageResult.of(List.of(new ReviewWithTarget(publicReview, target)), 0, 10, 1));
        PageResult<ReviewPublicView> listPublic = reviewService.findReviewsByTarget(targetId, 0, 10, "newest", false, requesterUserId);
        assertEquals(1, listPublic.content().size());

        // Caso B: PRIVATE, requester terceiro
        Review privateReview = new Review(reviewId, authorUserId, null, "Private text", false, false, ReviewStatus.ACTIVE, "PRIVATE", null, null, null, Instant.now(), Instant.now());
        when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(privateReview));
        BusinessException exB = assertThrows(BusinessException.class, () -> reviewService.getReviewPublicView(reviewId, requesterUserId));
        assertEquals("FORBIDDEN", exB.getErrorCode());

        when(reviewRepository.findByTarget(eq(targetId), eq(requesterUserId), eq(false), eq("newest"), eq(0), eq(10)))
                .thenReturn(PageResult.of(List.of(), 0, 10, 0));
        PageResult<ReviewPublicView> listPrivateThirdParty = reviewService.findReviewsByTarget(targetId, 0, 10, "newest", false, requesterUserId);
        assertTrue(listPrivateThirdParty.content().isEmpty());

        // Caso C: PRIVATE, requester autor
        ReviewPublicView getPrivateAuthor = reviewService.getReviewPublicView(reviewId, authorUserId);
        assertNotNull(getPrivateAuthor);
        assertEquals("PRIVATE", getPrivateAuthor.visibility());

        when(reviewRepository.findByTarget(eq(targetId), eq(authorUserId), eq(false), eq("newest"), eq(0), eq(10)))
                .thenReturn(PageResult.of(List.of(new ReviewWithTarget(privateReview, target)), 0, 10, 1));
        PageResult<ReviewPublicView> listPrivateAuthor = reviewService.findReviewsByTarget(targetId, 0, 10, "newest", false, authorUserId);
        assertEquals(1, listPrivateAuthor.content().size());

        // Caso D: FOLLOWERS, requester não autorizado (terceiro)
        Review followersReview = new Review(reviewId, authorUserId, null, "Followers text", false, false, ReviewStatus.ACTIVE, "FOLLOWERS", null, null, null, Instant.now(), Instant.now());
        when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(followersReview));
        BusinessException exD = assertThrows(BusinessException.class, () -> reviewService.getReviewPublicView(reviewId, requesterUserId));
        assertEquals("FORBIDDEN", exD.getErrorCode());

        when(reviewRepository.findByTarget(eq(targetId), eq(requesterUserId), eq(false), eq("newest"), eq(0), eq(10)))
                .thenReturn(PageResult.of(List.of(), 0, 10, 0));
        PageResult<ReviewPublicView> listFollowersThirdParty = reviewService.findReviewsByTarget(targetId, 0, 10, "newest", false, requesterUserId);
        assertTrue(listFollowersThirdParty.content().isEmpty());

        // Caso E: FOLLOWERS, requester autorizado (autor)
        ReviewPublicView getFollowersAuthor = reviewService.getReviewPublicView(reviewId, authorUserId);
        assertNotNull(getFollowersAuthor);
        assertEquals("FOLLOWERS", getFollowersAuthor.visibility());

        when(reviewRepository.findByTarget(eq(targetId), eq(authorUserId), eq(false), eq("newest"), eq(0), eq(10)))
                .thenReturn(PageResult.of(List.of(new ReviewWithTarget(followersReview, target)), 0, 10, 1));
        PageResult<ReviewPublicView> listFollowersAuthor = reviewService.findReviewsByTarget(targetId, 0, 10, "newest", false, authorUserId);
        assertEquals(1, listFollowersAuthor.content().size());
    }

    @Test
    @DisplayName("13. Auditoria de Visibilidade - Casos F e G: Status UNDER_REVIEW e REMOVED não aparecem em listagem por target mas aparecem em /me/reviews")
    void shouldValidateStatusFilteringCasesFandG() {
        when(rateableTargetRepository.existsPubliclyVisibleById(targetId)).thenReturn(true);
        UUID reviewFId = UUID.randomUUID();
        UUID reviewGId = UUID.randomUUID();

        Review reviewUnderReview = new Review(reviewFId, authorUserId, null, "Sob análise", false, false, ReviewStatus.UNDER_REVIEW, "PUBLIC", null, null, null, Instant.now(), Instant.now());
        Review reviewRemoved = new Review(reviewGId, authorUserId, null, "Removida", false, false, ReviewStatus.REMOVED, "PUBLIC", null, null, null, Instant.now(), Instant.now());

        // Em findReviewsByTarget, o repositório filtra WHERE r.status = 'ACTIVE', portanto não retorna F ou G
        when(reviewRepository.findByTarget(eq(targetId), any(), eq(false), eq("newest"), eq(0), eq(10)))
                .thenReturn(PageResult.of(List.of(), 0, 10, 0));

        PageResult<ReviewPublicView> listResult = reviewService.findReviewsByTarget(targetId, 0, 10, "newest", false, requesterUserId);
        assertTrue(listResult.content().isEmpty(), "Reviews UNDER_REVIEW e REMOVED não devem ser expostas em listagem por target");

        // Em /me/reviews, o autor tem acesso às suas próprias avaliações independentemente do status
        when(reviewRepository.findByUserIdPaged(eq(authorUserId), eq(0), eq(10)))
                .thenReturn(PageResult.of(List.of(reviewUnderReview, reviewRemoved), 0, 10, 2));
        when(reviewTargetRepository.findByReviewIdIn(anyList()))
                .thenReturn(List.of());
        when(profileRepository.findByUserId(authorUserId))
                .thenReturn(Optional.of(new Profile(UUID.randomUUID(), authorUserId, "author", "Author", null, null)));

        PageResult<ReviewPublicView> meResult = reviewService.findMyReviews(authorUserId, 0, 10);
        assertEquals(2, meResult.content().size());
        assertEquals("UNDER_REVIEW", meResult.content().get(0).status());
        assertEquals("REMOVED", meResult.content().get(1).status());
    }

    @Test
    @DisplayName("14. GET individual: isMine é contextual ao requester e não depende do id público do autor")
    void shouldResolveIsMineFromRealAuthorWithoutExposingIdentity() {
        UUID reviewId = UUID.randomUUID();
        Profile authorProfile = new Profile(UUID.randomUUID(), authorUserId, "author", "Author Name", "Bio", null);
        when(profileRepository.findByUserId(authorUserId)).thenReturn(Optional.of(authorProfile));

        Review publicReview = new Review(reviewId, authorUserId, null, "Public text", false, false, ReviewStatus.ACTIVE, "PUBLIC", null, null, null, Instant.now(), Instant.now());
        when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(publicReview));

        assertTrue(reviewService.getReviewPublicView(reviewId, authorUserId).isMine());
        assertFalse(reviewService.getReviewPublicView(reviewId, requesterUserId).isMine());
        assertFalse(reviewService.getReviewPublicView(reviewId, null).isMine());

        Review anonymousReview = new Review(reviewId, authorUserId, null, "Anon text", true, false, ReviewStatus.ACTIVE, "PUBLIC", null, null, null, Instant.now(), Instant.now());
        when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(anonymousReview));

        ReviewPublicView ownAnonymous = reviewService.getReviewPublicView(reviewId, authorUserId);
        assertTrue(ownAnonymous.isMine());
        assertNull(ownAnonymous.author().id());
        assertTrue(ownAnonymous.author().isAnonymous());

        ReviewPublicView thirdPartyAnonymous = reviewService.getReviewPublicView(reviewId, requesterUserId);
        assertFalse(thirdPartyAnonymous.isMine());
        assertNull(thirdPartyAnonymous.author().id());

        assertFalse(reviewService.getReviewPublicView(reviewId, null).isMine());
    }
}
