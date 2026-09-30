package com.rewit.application.service;

import com.rewit.application.dto.ReviewDto.PublicAuthorView;
import com.rewit.application.dto.feed.FeedV2CandidatePage;
import com.rewit.application.dto.feed.FeedV2PageProjection;
import com.rewit.application.dto.feed.FeedV2Projection;
import com.rewit.application.port.ProfileRepository;
import com.rewit.application.port.ReviewReactionRepository;
import com.rewit.application.port.ReviewRepository;
import com.rewit.application.port.ReviewTargetRepository;
import com.rewit.domain.enums.ReviewStatus;
import com.rewit.domain.feed.FeedCandidate;
import com.rewit.domain.feed.FeedScore;
import com.rewit.domain.feed.RankedFeedCandidate;
import com.rewit.domain.model.Profile;
import com.rewit.domain.model.Review;
import com.rewit.domain.model.ReviewTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Testes Unitários: FeedV2Hydrator e Projeção Pública do Feed V2 (Step 24.4.2)")
class FeedV2HydratorUnitTest {

    @Mock
    private ReviewRepository reviewRepository;

    @Mock
    private ReviewTargetRepository reviewTargetRepository;

    @Mock
    private ProfileRepository profileRepository;

    @Mock
    private ReviewReactionRepository reviewReactionRepository;

    @Captor
    private ArgumentCaptor<Collection<UUID>> reviewIdsCaptor;

    @Captor
    private ArgumentCaptor<Collection<UUID>> authorIdsCaptor;

    private FeedV2Hydrator hydrator;

    private final UUID requesterId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        hydrator = new FeedV2Hydrator(
                reviewRepository,
                reviewTargetRepository,
                profileRepository,
                reviewReactionRepository
        );
    }

    private RankedFeedCandidate createRankedCandidate(UUID reviewId, UUID authorId, double scoreVal) {
        FeedCandidate candidate = new FeedCandidate(
                reviewId,
                authorId,
                UUID.randomUUID(),
                Instant.now(),
                false,
                0,
                true
        );
        return new RankedFeedCandidate(candidate, new FeedScore(scoreVal));
    }

    private Review createDomainReview(UUID reviewId, UUID authorId, boolean isAnonymous, ReviewStatus status) {
        return new Review(
                reviewId,
                authorId,
                UUID.randomUUID(),
                "Experiência detalhada da review",
                isAnonymous,
                false,
                status,
                "PUBLIC",
                -23.55,
                -46.63,
                10.0,
                Instant.parse("2026-09-30T10:00:00Z"),
                Instant.parse("2026-09-30T10:00:00Z")
        );
    }

    @Test
    @DisplayName("1. Ordem: Preserva estritamente a ordem de entrada produzida pelo ranker/diversifier")
    void shouldPreserveExactInputOrder() {
        UUID r1 = UUID.randomUUID();
        UUID r2 = UUID.randomUUID();
        UUID r3 = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();

        RankedFeedCandidate c1 = createRankedCandidate(r1, authorId, 0.95);
        RankedFeedCandidate c2 = createRankedCandidate(r2, authorId, 0.85);
        RankedFeedCandidate c3 = createRankedCandidate(r3, authorId, 0.70);

        FeedV2CandidatePage candidatePage = new FeedV2CandidatePage(List.of(c1, c2, c3), 0, 10, 3);

        Review rev1 = createDomainReview(r1, authorId, false, ReviewStatus.ACTIVE);
        Review rev2 = createDomainReview(r2, authorId, false, ReviewStatus.ACTIVE);
        Review rev3 = createDomainReview(r3, authorId, false, ReviewStatus.ACTIVE);

        // O repositório retorna em ordem arbitrária (ex: rev3, rev1, rev2)
        when(reviewRepository.findByIdIn(anyCollection())).thenReturn(List.of(rev3, rev1, rev2));
        when(reviewTargetRepository.findByReviewIdIn(anyCollection())).thenReturn(List.of());
        when(profileRepository.findByUserIdIn(anyCollection())).thenReturn(List.of(
                new Profile(UUID.randomUUID(), authorId, "author_handle", "Author Name", "bio", null)
        ));
        when(reviewReactionRepository.countHelpfulByReviewIds(anyCollection())).thenReturn(Map.of());
        when(reviewReactionRepository.findHelpfulReviewIdsByUser(anyCollection(), eq(requesterId))).thenReturn(Set.of());

        FeedV2PageProjection projection = hydrator.hydrate(candidatePage, requesterId);

        assertNotNull(projection);
        assertEquals(3, projection.items().size());
        assertEquals(r1, projection.items().get(0).id());
        assertEquals(r2, projection.items().get(1).id());
        assertEquals(r3, projection.items().get(2).id());
        assertEquals(0, projection.page());
        assertEquals(10, projection.size());
        assertEquals(3, projection.windowSize());
    }

    @Test
    @DisplayName("2. Empty: Página vazia não realiza nenhuma consulta a repositórios")
    void shouldNotCallRepositoriesWhenPageIsEmpty() {
        FeedV2CandidatePage emptyPage = new FeedV2CandidatePage(List.of(), 0, 10, 0);

        FeedV2PageProjection projection = hydrator.hydrate(emptyPage, requesterId);

        assertNotNull(projection);
        assertTrue(projection.isEmpty());
        assertEquals(0, projection.items().size());
        verifyNoInteractions(reviewRepository, reviewTargetRepository, profileRepository, reviewReactionRepository);
    }

    @Test
    @DisplayName("3. Empty: Página nula não realiza nenhuma consulta e retorna projeção vazia")
    void shouldNotCallRepositoriesWhenPageIsNull() {
        FeedV2PageProjection projection = hydrator.hydrate((FeedV2CandidatePage) null, requesterId);

        assertNotNull(projection);
        assertTrue(projection.isEmpty());
        verifyNoInteractions(reviewRepository, reviewTargetRepository, profileRepository, reviewReactionRepository);
    }

    @Test
    @DisplayName("4. Anonymous: Review anônima nunca consulta profile no banco e usa estritamente PublicAuthorView.anonymous()")
    void shouldPreserveStrictAnonymityAndNotLookupProfile() {
        UUID reviewId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();

        RankedFeedCandidate candidate = createRankedCandidate(reviewId, authorId, 0.9);
        FeedV2CandidatePage page = new FeedV2CandidatePage(List.of(candidate), 0, 10, 1);

        Review anonymousReview = createDomainReview(reviewId, authorId, true, ReviewStatus.ACTIVE);

        when(reviewRepository.findByIdIn(anyCollection())).thenReturn(List.of(anonymousReview));
        when(reviewTargetRepository.findByReviewIdIn(anyCollection())).thenReturn(List.of());
        when(reviewReactionRepository.countHelpfulByReviewIds(anyCollection())).thenReturn(Map.of());
        when(reviewReactionRepository.findHelpfulReviewIdsByUser(anyCollection(), eq(requesterId))).thenReturn(Set.of());

        FeedV2PageProjection result = hydrator.hydrate(page, requesterId);

        assertEquals(1, result.items().size());
        FeedV2Projection item = result.items().get(0);
        assertTrue(item.isAnonymous());
        assertNotNull(item.author());
        assertNull(item.author().id(), "O authorId nunca pode vazar para reviews anônimas");
        assertNull(item.author().handle());
        assertEquals("Anônimo", item.author().displayName());
        assertNull(item.author().avatarUrl());
        assertTrue(item.author().isAnonymous());

        // Confirma que profileRepository NUNCA foi chamado
        verifyNoInteractions(profileRepository);
    }

    @Test
    @DisplayName("5. Non-anonymous: Autor público tem seu perfil associado corretamente")
    void shouldHydrateNonAnonymousAuthorWithProfile() {
        UUID reviewId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();

        RankedFeedCandidate candidate = createRankedCandidate(reviewId, authorId, 0.9);
        FeedV2CandidatePage page = new FeedV2CandidatePage(List.of(candidate), 0, 10, 1);

        Review review = createDomainReview(reviewId, authorId, false, ReviewStatus.ACTIVE);

        when(reviewRepository.findByIdIn(anyCollection())).thenReturn(List.of(review));
        when(reviewTargetRepository.findByReviewIdIn(anyCollection())).thenReturn(List.of());
        when(profileRepository.findByUserIdIn(anyCollection())).thenReturn(List.of(
                new Profile(UUID.randomUUID(), authorId, "joaosilva", "João Silva", "Bio", "https://img.avatar.jpg")
        ));
        when(reviewReactionRepository.countHelpfulByReviewIds(anyCollection())).thenReturn(Map.of());
        when(reviewReactionRepository.findHelpfulReviewIdsByUser(anyCollection(), eq(requesterId))).thenReturn(Set.of());

        FeedV2PageProjection result = hydrator.hydrate(page, requesterId);

        assertEquals(1, result.items().size());
        FeedV2Projection item = result.items().get(0);
        assertFalse(item.isAnonymous());
        assertEquals(authorId, item.author().id());
        assertEquals("joaosilva", item.author().handle());
        assertEquals("João Silva", item.author().displayName());
        assertEquals("https://img.avatar.jpg", item.author().avatarUrl());
        assertFalse(item.author().isAnonymous());

        verify(profileRepository, times(1)).findByUserIdIn(authorIdsCaptor.capture());
        assertTrue(authorIdsCaptor.getValue().contains(authorId));
    }

    @Test
    @DisplayName("6. Multi-target: Todos os alvos da avaliação são preservados na projeção de forma determinística")
    void shouldPreserveAllTargetsForMultiTargetReview() {
        UUID reviewId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        UUID targetPlaceId = UUID.randomUUID();
        UUID targetDishId = UUID.randomUUID();

        RankedFeedCandidate candidate = createRankedCandidate(reviewId, authorId, 0.85);
        FeedV2CandidatePage page = new FeedV2CandidatePage(List.of(candidate), 0, 10, 1);

        Review review = createDomainReview(reviewId, authorId, false, ReviewStatus.ACTIVE);

        Instant t1Time = Instant.parse("2026-09-30T10:00:00Z");
        Instant t2Time = Instant.parse("2026-09-30T10:05:00Z");
        ReviewTarget t1 = new ReviewTarget(UUID.randomUUID(), reviewId, targetPlaceId, BigDecimal.valueOf(4.5), "Lugar bacana", t1Time);
        ReviewTarget t2 = new ReviewTarget(UUID.randomUUID(), reviewId, targetDishId, BigDecimal.valueOf(5.0), "Prato espetacular", t2Time);

        when(reviewRepository.findByIdIn(anyCollection())).thenReturn(List.of(review));
        // Repositório retorna em ordem inversa (t2, t1) para validar a ordenação determinística
        when(reviewTargetRepository.findByReviewIdIn(anyCollection())).thenReturn(List.of(t2, t1));
        when(profileRepository.findByUserIdIn(anyCollection())).thenReturn(List.of());
        when(reviewReactionRepository.countHelpfulByReviewIds(anyCollection())).thenReturn(Map.of());
        when(reviewReactionRepository.findHelpfulReviewIdsByUser(anyCollection(), eq(requesterId))).thenReturn(Set.of());

        FeedV2PageProjection result = hydrator.hydrate(page, requesterId);

        assertEquals(1, result.items().size());
        FeedV2Projection item = result.items().get(0);
        assertEquals(2, item.targets().size());
        assertEquals(targetPlaceId, item.targets().get(0).targetId());
        assertEquals(BigDecimal.valueOf(4.5), item.targets().get(0).rating());
        assertEquals("Lugar bacana", item.targets().get(0).specificComment());
        assertEquals(targetDishId, item.targets().get(1).targetId());
        assertEquals(BigDecimal.valueOf(5.0), item.targets().get(1).rating());
        assertEquals("Prato espetacular", item.targets().get(1).specificComment());
    }

    @Test
    @DisplayName("7. Helpful: Total de contagens e helpful do requester são calculados em lote corretamente")
    void shouldHydrateHelpfulCountsAndRequesterVote() {
        UUID r1 = UUID.randomUUID();
        UUID r2 = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();

        RankedFeedCandidate c1 = createRankedCandidate(r1, authorId, 0.9);
        RankedFeedCandidate c2 = createRankedCandidate(r2, authorId, 0.8);
        FeedV2CandidatePage page = new FeedV2CandidatePage(List.of(c1, c2), 0, 10, 2);

        Review rev1 = createDomainReview(r1, authorId, false, ReviewStatus.ACTIVE);
        Review rev2 = createDomainReview(r2, authorId, false, ReviewStatus.ACTIVE);

        when(reviewRepository.findByIdIn(anyCollection())).thenReturn(List.of(rev1, rev2));
        when(reviewTargetRepository.findByReviewIdIn(anyCollection())).thenReturn(List.of());
        when(profileRepository.findByUserIdIn(anyCollection())).thenReturn(List.of());
        when(reviewReactionRepository.countHelpfulByReviewIds(anyCollection())).thenReturn(Map.of(
                r1, 14L,
                r2, 3L
        ));
        when(reviewReactionRepository.findHelpfulReviewIdsByUser(anyCollection(), eq(requesterId))).thenReturn(Set.of(r1));

        FeedV2PageProjection result = hydrator.hydrate(page, requesterId);

        assertEquals(2, result.items().size());
        FeedV2Projection p1 = result.items().get(0);
        FeedV2Projection p2 = result.items().get(1);

        assertEquals(14L, p1.helpfulCount());
        assertTrue(p1.isHelpfulByMe(), "Requester votou em r1");

        assertEquals(3L, p2.helpfulCount());
        assertFalse(p2.isHelpfulByMe(), "Requester não votou em r2");
    }

    @Test
    @DisplayName("8. Helpful: Usuário deslogado (requesterId == null) não dispara query de helpful do requester")
    void shouldNotQueryHelpfulByUserWhenRequesterIsNull() {
        UUID reviewId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();

        RankedFeedCandidate candidate = createRankedCandidate(reviewId, authorId, 0.9);
        FeedV2CandidatePage page = new FeedV2CandidatePage(List.of(candidate), 0, 10, 1);

        Review review = createDomainReview(reviewId, authorId, false, ReviewStatus.ACTIVE);

        when(reviewRepository.findByIdIn(anyCollection())).thenReturn(List.of(review));
        when(reviewTargetRepository.findByReviewIdIn(anyCollection())).thenReturn(List.of());
        when(profileRepository.findByUserIdIn(anyCollection())).thenReturn(List.of());
        when(reviewReactionRepository.countHelpfulByReviewIds(anyCollection())).thenReturn(Map.of(reviewId, 5L));

        FeedV2PageProjection result = hydrator.hydrate(page, null);

        assertEquals(1, result.items().size());
        assertEquals(5L, result.items().get(0).helpfulCount());
        assertFalse(result.items().get(0).isHelpfulByMe());

        verify(reviewReactionRepository, never()).findHelpfulReviewIdsByUser(anyCollection(), any());
    }

    @Test
    @DisplayName("9. Missing profile: Autor sem registro de Profile não quebra e mantém id com campos nulos")
    void shouldHandleMissingProfileGracefully() {
        UUID reviewId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();

        RankedFeedCandidate candidate = createRankedCandidate(reviewId, authorId, 0.85);
        FeedV2CandidatePage page = new FeedV2CandidatePage(List.of(candidate), 0, 10, 1);

        Review review = createDomainReview(reviewId, authorId, false, ReviewStatus.ACTIVE);

        when(reviewRepository.findByIdIn(anyCollection())).thenReturn(List.of(review));
        when(reviewTargetRepository.findByReviewIdIn(anyCollection())).thenReturn(List.of());
        when(profileRepository.findByUserIdIn(anyCollection())).thenReturn(List.of()); // Perfil ausente
        when(reviewReactionRepository.countHelpfulByReviewIds(anyCollection())).thenReturn(Map.of());
        when(reviewReactionRepository.findHelpfulReviewIdsByUser(anyCollection(), eq(requesterId))).thenReturn(Set.of());

        FeedV2PageProjection result = hydrator.hydrate(page, requesterId);

        assertEquals(1, result.items().size());
        PublicAuthorView authorView = result.items().get(0).author();
        assertNotNull(authorView);
        assertEquals(authorId, authorView.id());
        assertNull(authorView.handle());
        assertNull(authorView.displayName());
        assertNull(authorView.avatarUrl());
        assertFalse(authorView.isAnonymous());
    }

    @Test
    @DisplayName("10. Missing target: Review sem alvos avaliados não quebra a projeção")
    void shouldHandleMissingTargetsGracefully() {
        UUID reviewId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();

        RankedFeedCandidate candidate = createRankedCandidate(reviewId, authorId, 0.85);
        FeedV2CandidatePage page = new FeedV2CandidatePage(List.of(candidate), 0, 10, 1);

        Review review = createDomainReview(reviewId, authorId, false, ReviewStatus.ACTIVE);

        when(reviewRepository.findByIdIn(anyCollection())).thenReturn(List.of(review));
        when(reviewTargetRepository.findByReviewIdIn(anyCollection())).thenReturn(List.of()); // Sem alvos
        when(profileRepository.findByUserIdIn(anyCollection())).thenReturn(List.of());
        when(reviewReactionRepository.countHelpfulByReviewIds(anyCollection())).thenReturn(Map.of());
        when(reviewReactionRepository.findHelpfulReviewIdsByUser(anyCollection(), eq(requesterId))).thenReturn(Set.of());

        FeedV2PageProjection result = hydrator.hydrate(page, requesterId);

        assertEquals(1, result.items().size());
        assertTrue(result.items().get(0).targets().isEmpty());
    }

    @Test
    @DisplayName("11. Duplicidade: Candidatos duplicados não geram IDs duplicados na consulta batch de reviews")
    void shouldDeduplicateReviewIdsForBatchQueries() {
        UUID reviewId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();

        RankedFeedCandidate c1 = createRankedCandidate(reviewId, authorId, 0.9);
        RankedFeedCandidate c2 = createRankedCandidate(reviewId, authorId, 0.8);
        FeedV2CandidatePage page = new FeedV2CandidatePage(List.of(c1, c2), 0, 10, 2);

        Review review = createDomainReview(reviewId, authorId, false, ReviewStatus.ACTIVE);

        when(reviewRepository.findByIdIn(reviewIdsCaptor.capture())).thenReturn(List.of(review));
        when(reviewTargetRepository.findByReviewIdIn(anyCollection())).thenReturn(List.of());
        when(profileRepository.findByUserIdIn(anyCollection())).thenReturn(List.of());
        when(reviewReactionRepository.countHelpfulByReviewIds(anyCollection())).thenReturn(Map.of());
        when(reviewReactionRepository.findHelpfulReviewIdsByUser(anyCollection(), eq(requesterId))).thenReturn(Set.of());

        hydrator.hydrate(page, requesterId);

        Collection<UUID> capturedIds = reviewIdsCaptor.getValue();
        assertEquals(1, capturedIds.size(), "O conjunto de IDs para busca no banco deve ser deduplicado");
    }

    @Test
    @DisplayName("12. Imutabilidade: Projeção retornada é imutável e preserva a lista original de candidatos")
    void shouldEnsureImmutabilityOfReturnedList() {
        UUID reviewId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();

        RankedFeedCandidate candidate = createRankedCandidate(reviewId, authorId, 0.9);
        List<RankedFeedCandidate> candidateList = List.of(candidate);
        FeedV2CandidatePage page = new FeedV2CandidatePage(candidateList, 0, 10, 1);

        Review review = createDomainReview(reviewId, authorId, false, ReviewStatus.ACTIVE);

        when(reviewRepository.findByIdIn(anyCollection())).thenReturn(List.of(review));
        when(reviewTargetRepository.findByReviewIdIn(anyCollection())).thenReturn(List.of());
        when(profileRepository.findByUserIdIn(anyCollection())).thenReturn(List.of());
        when(reviewReactionRepository.countHelpfulByReviewIds(anyCollection())).thenReturn(Map.of());
        when(reviewReactionRepository.findHelpfulReviewIdsByUser(anyCollection(), eq(requesterId))).thenReturn(Set.of());

        FeedV2PageProjection result = hydrator.hydrate(page, requesterId);

        assertThrows(UnsupportedOperationException.class, () -> result.items().add(null));
        assertEquals(1, candidateList.size());
    }

    @Test
    @DisplayName("13. Status filter: Reviews com status REMOVED ou UNDER_REVIEW são filtradas e não geram projeção")
    void shouldFilterOutRemovedAndUnderReviewReviews() {
        UUID rActive = UUID.randomUUID();
        UUID rRemoved = UUID.randomUUID();
        UUID rUnderReview = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();

        RankedFeedCandidate cActive = createRankedCandidate(rActive, authorId, 0.9);
        RankedFeedCandidate cRemoved = createRankedCandidate(rRemoved, authorId, 0.8);
        RankedFeedCandidate cUnderReview = createRankedCandidate(rUnderReview, authorId, 0.7);

        FeedV2CandidatePage page = new FeedV2CandidatePage(List.of(cActive, cRemoved, cUnderReview), 0, 10, 3);

        Review revActive = createDomainReview(rActive, authorId, false, ReviewStatus.ACTIVE);
        Review revRemoved = createDomainReview(rRemoved, authorId, false, ReviewStatus.REMOVED);
        Review revUnderReview = createDomainReview(rUnderReview, authorId, false, ReviewStatus.UNDER_REVIEW);

        when(reviewRepository.findByIdIn(anyCollection())).thenReturn(List.of(revActive, revRemoved, revUnderReview));
        when(reviewTargetRepository.findByReviewIdIn(anyCollection())).thenReturn(List.of());
        when(profileRepository.findByUserIdIn(anyCollection())).thenReturn(List.of());
        when(reviewReactionRepository.countHelpfulByReviewIds(anyCollection())).thenReturn(Map.of());
        when(reviewReactionRepository.findHelpfulReviewIdsByUser(anyCollection(), eq(requesterId))).thenReturn(Set.of());

        FeedV2PageProjection result = hydrator.hydrate(page, requesterId);

        assertEquals(1, result.items().size(), "Apenas a review ACTIVE deve ser projetada");
        assertEquals(rActive, result.items().get(0).id());
    }

    @Test
    @DisplayName("14. Missing review: Review inexistente no banco não quebra o pipeline")
    void shouldIgnoreReviewsMissingFromDatabase() {
        UUID rExisting = UUID.randomUUID();
        UUID rMissing = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();

        RankedFeedCandidate cExisting = createRankedCandidate(rExisting, authorId, 0.9);
        RankedFeedCandidate cMissing = createRankedCandidate(rMissing, authorId, 0.8);

        FeedV2CandidatePage page = new FeedV2CandidatePage(List.of(cExisting, cMissing), 0, 10, 2);

        Review revExisting = createDomainReview(rExisting, authorId, false, ReviewStatus.ACTIVE);

        when(reviewRepository.findByIdIn(anyCollection())).thenReturn(List.of(revExisting)); // rMissing não retorna nada
        when(reviewTargetRepository.findByReviewIdIn(anyCollection())).thenReturn(List.of());
        when(profileRepository.findByUserIdIn(anyCollection())).thenReturn(List.of());
        when(reviewReactionRepository.countHelpfulByReviewIds(anyCollection())).thenReturn(Map.of());
        when(reviewReactionRepository.findHelpfulReviewIdsByUser(anyCollection(), eq(requesterId))).thenReturn(Set.of());

        FeedV2PageProjection result = hydrator.hydrate(page, requesterId);

        assertEquals(1, result.items().size());
        assertEquals(rExisting, result.items().get(0).id());
    }

    @Test
    @DisplayName("15. Sobrecarga com lista direta de candidatos funciona preservando as mesmas invariantes")
    void shouldHydrateDirectCandidatesListOverload() {
        UUID reviewId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();

        RankedFeedCandidate candidate = createRankedCandidate(reviewId, authorId, 0.9);
        Review review = createDomainReview(reviewId, authorId, false, ReviewStatus.ACTIVE);

        when(reviewRepository.findByIdIn(anyCollection())).thenReturn(List.of(review));
        when(reviewTargetRepository.findByReviewIdIn(anyCollection())).thenReturn(List.of());
        when(profileRepository.findByUserIdIn(anyCollection())).thenReturn(List.of());
        when(reviewReactionRepository.countHelpfulByReviewIds(anyCollection())).thenReturn(Map.of());
        when(reviewReactionRepository.findHelpfulReviewIdsByUser(anyCollection(), eq(requesterId))).thenReturn(Set.of());

        List<FeedV2Projection> items = hydrator.hydrate(List.of(candidate), requesterId);

        assertEquals(1, items.size());
        assertEquals(reviewId, items.get(0).id());

        // Testar com lista vazia e nula
        assertTrue(hydrator.hydrate((List<RankedFeedCandidate>) null, requesterId).isEmpty());
        assertTrue(hydrator.hydrate(List.of(), requesterId).isEmpty());
    }
}
