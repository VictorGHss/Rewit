package com.rewit.application.service;

import com.rewit.application.dto.feed.FeedV2CandidatePage;
import com.rewit.application.port.FeedCandidateRepository;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.feed.FeedCandidate;
import com.rewit.domain.feed.FeedScore;
import com.rewit.domain.feed.FeedV2Diversifier;
import com.rewit.domain.feed.FeedV2Ranker;
import com.rewit.domain.feed.RankedFeedCandidate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Testes Unitários: FeedV2Service (Orquestração do Feed V2 - Step 24.4.1)")
class FeedV2ServiceUnitTest {

    @Mock
    private FeedCandidateRepository feedCandidateRepository;

    @Mock
    private FeedV2Ranker feedV2Ranker;

    @Mock
    private FeedV2Diversifier feedV2Diversifier;

    private FeedV2Service feedV2Service;

    private final UUID requesterId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-09-30T19:00:00Z");

    @BeforeEach
    void setUp() {
        feedV2Service = new FeedV2Service(feedCandidateRepository, feedV2Ranker, feedV2Diversifier);
    }

    private FeedCandidate createCandidate(UUID reviewId, UUID authorId, UUID targetId, Instant createdAt) {
        return new FeedCandidate(
                reviewId,
                authorId,
                targetId,
                createdAt,
                false,
                0,
                true
        );
    }

    private RankedFeedCandidate createRankedCandidate(FeedCandidate candidate, double score) {
        return new RankedFeedCandidate(candidate, new FeedScore(score));
    }

    // -------------------------------------------------------------------------
    // 1. Validação de Input
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("1.1 Rejeita requesterId nulo com 401 UNAUTHORIZED")
    void shouldRejectNullRequesterId() {
        BusinessException ex = assertThrows(BusinessException.class, () ->
                feedV2Service.getCandidatePage(null, 0, 10, now));

        assertEquals("UNAUTHORIZED", ex.getErrorCode());
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus());
        verifyNoInteractions(feedCandidateRepository, feedV2Ranker, feedV2Diversifier);
    }

    @Test
    @DisplayName("1.2 Rejeita referenceTime nulo com 400 MISSING_REFERENCE_TIME")
    void shouldRejectNullReferenceTime() {
        BusinessException ex = assertThrows(BusinessException.class, () ->
                feedV2Service.getCandidatePage(requesterId, 0, 10, null));

        assertEquals("MISSING_REFERENCE_TIME", ex.getErrorCode());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        verifyNoInteractions(feedCandidateRepository, feedV2Ranker, feedV2Diversifier);
    }

    @Test
    @DisplayName("1.3 Rejeita página negativa com 400 INVALID_PAGE")
    void shouldRejectNegativePage() {
        BusinessException ex = assertThrows(BusinessException.class, () ->
                feedV2Service.getCandidatePage(requesterId, -1, 10, now));

        assertEquals("INVALID_PAGE", ex.getErrorCode());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        verifyNoInteractions(feedCandidateRepository);
    }

    @Test
    @DisplayName("1.4 Rejeita size zero com 400 INVALID_SIZE")
    void shouldRejectZeroSize() {
        BusinessException ex = assertThrows(BusinessException.class, () ->
                feedV2Service.getCandidatePage(requesterId, 0, 0, now));

        assertEquals("INVALID_SIZE", ex.getErrorCode());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        verifyNoInteractions(feedCandidateRepository);
    }

    @Test
    @DisplayName("1.5 Rejeita size negativo com 400 INVALID_SIZE")
    void shouldRejectNegativeSize() {
        BusinessException ex = assertThrows(BusinessException.class, () ->
                feedV2Service.getCandidatePage(requesterId, 0, -5, now));

        assertEquals("INVALID_SIZE", ex.getErrorCode());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        verifyNoInteractions(feedCandidateRepository);
    }

    @Test
    @DisplayName("1.6 Rejeita size superior a 50 com 400 PAGE_SIZE_EXCEEDED")
    void shouldRejectSizeExceeding50() {
        BusinessException ex = assertThrows(BusinessException.class, () ->
                feedV2Service.getCandidatePage(requesterId, 0, 51, now));

        assertEquals("PAGE_SIZE_EXCEEDED", ex.getErrorCode());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        verifyNoInteractions(feedCandidateRepository);
    }

    @Test
    @DisplayName("1.7 Aceita limites válidos: page=0 e size=1 (mínimo)")
    void shouldAcceptMinimumValidParameters() {
        when(feedCandidateRepository.retrieveCandidates(requesterId, FeedCandidateRepository.CANDIDATE_WINDOW))
                .thenReturn(List.of());

        FeedV2CandidatePage result = feedV2Service.getCandidatePage(requesterId, 0, 1, now);

        assertNotNull(result);
        assertTrue(result.isEmpty());
        assertEquals(0, result.page());
        assertEquals(1, result.size());
        assertEquals(0, result.windowSize());
    }

    @Test
    @DisplayName("1.8 Aceita limites válidos: page positivo e size=50 (máximo)")
    void shouldAcceptMaximumValidParameters() {
        when(feedCandidateRepository.retrieveCandidates(requesterId, FeedCandidateRepository.CANDIDATE_WINDOW))
                .thenReturn(List.of());

        FeedV2CandidatePage result = feedV2Service.getCandidatePage(requesterId, 5, 50, now);

        assertNotNull(result);
        assertTrue(result.isEmpty());
        assertEquals(5, result.page());
        assertEquals(50, result.size());
        assertEquals(0, result.windowSize());
    }

    // -------------------------------------------------------------------------
    // 2. Pipeline na Ordem Estrita: Retrieval → Ranking → Diversidade → Fatiamento
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("2.1 Executa pipeline na ordem estrita: retrieveCandidates → rank → diversify")
    void shouldExecutePipelineInStrictOrder() {
        FeedCandidate candidate = createCandidate(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), now);
        RankedFeedCandidate ranked = createRankedCandidate(candidate, 0.85);

        when(feedCandidateRepository.retrieveCandidates(requesterId, FeedCandidateRepository.CANDIDATE_WINDOW))
                .thenReturn(List.of(candidate));
        when(feedV2Ranker.rank(List.of(candidate), now))
                .thenReturn(List.of(ranked));
        when(feedV2Diversifier.diversify(List.of(ranked)))
                .thenReturn(List.of(ranked));

        FeedV2CandidatePage page = feedV2Service.getCandidatePage(requesterId, 0, 10, now);

        assertNotNull(page);
        assertEquals(1, page.items().size());
        assertSame(ranked, page.items().get(0));

        InOrder inOrder = inOrder(feedCandidateRepository, feedV2Ranker, feedV2Diversifier);
        inOrder.verify(feedCandidateRepository).retrieveCandidates(requesterId, FeedCandidateRepository.CANDIDATE_WINDOW);
        inOrder.verify(feedV2Ranker).rank(List.of(candidate), now);
        inOrder.verify(feedV2Diversifier).diversify(List.of(ranked));
        inOrder.verifyNoMoreInteractions();
    }

    @Test
    @DisplayName("2.2 Retorna imediatamente com lista vazia se retrieval não encontrar candidatos")
    void shouldShortCircuitWhenRetrievalReturnsEmpty() {
        when(feedCandidateRepository.retrieveCandidates(requesterId, FeedCandidateRepository.CANDIDATE_WINDOW))
                .thenReturn(List.of());

        FeedV2CandidatePage page = feedV2Service.getCandidatePage(requesterId, 0, 10, now);

        assertNotNull(page);
        assertTrue(page.isEmpty());
        assertEquals(0, page.windowSize());

        verify(feedCandidateRepository).retrieveCandidates(requesterId, FeedCandidateRepository.CANDIDATE_WINDOW);
        verifyNoInteractions(feedV2Ranker, feedV2Diversifier);
    }

    // -------------------------------------------------------------------------
    // 3. Paginação Interna sobre a Candidate Window
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("3.1 Fatiamento correto: primeira página, segunda página, página parcial e além da janela")
    void shouldSlicePagesCorrectly() {
        // Criar 12 candidatos simulados
        List<FeedCandidate> candidates = new ArrayList<>();
        List<RankedFeedCandidate> rankedList = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            FeedCandidate c = createCandidate(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), now.minusSeconds(i));
            candidates.add(c);
            rankedList.add(createRankedCandidate(c, 1.0 - (i * 0.05)));
        }

        when(feedCandidateRepository.retrieveCandidates(requesterId, FeedCandidateRepository.CANDIDATE_WINDOW))
                .thenReturn(candidates);
        when(feedV2Ranker.rank(eq(candidates), eq(now)))
                .thenReturn(rankedList);
        when(feedV2Diversifier.diversify(rankedList))
                .thenReturn(rankedList);

        // Page 0, Size 5 -> Itens 0..4 (5 itens)
        FeedV2CandidatePage p0 = feedV2Service.getCandidatePage(requesterId, 0, 5, now);
        assertEquals(5, p0.items().size());
        assertEquals(0, p0.page());
        assertEquals(5, p0.size());
        assertEquals(12, p0.windowSize());
        assertEquals(rankedList.get(0).candidate().reviewId(), p0.items().get(0).candidate().reviewId());
        assertEquals(rankedList.get(4).candidate().reviewId(), p0.items().get(4).candidate().reviewId());
        assertFalse(p0.isLast());

        // Page 1, Size 5 -> Itens 5..9 (5 itens)
        FeedV2CandidatePage p1 = feedV2Service.getCandidatePage(requesterId, 1, 5, now);
        assertEquals(5, p1.items().size());
        assertEquals(1, p1.page());
        assertEquals(12, p1.windowSize());
        assertEquals(rankedList.get(5).candidate().reviewId(), p1.items().get(0).candidate().reviewId());
        assertEquals(rankedList.get(9).candidate().reviewId(), p1.items().get(4).candidate().reviewId());
        assertFalse(p1.isLast());

        // Page 2, Size 5 -> Itens 10..11 (2 itens - página parcial)
        FeedV2CandidatePage p2 = feedV2Service.getCandidatePage(requesterId, 2, 5, now);
        assertEquals(2, p2.items().size());
        assertEquals(2, p2.page());
        assertEquals(12, p2.windowSize());
        assertEquals(rankedList.get(10).candidate().reviewId(), p2.items().get(0).candidate().reviewId());
        assertEquals(rankedList.get(11).candidate().reviewId(), p2.items().get(1).candidate().reviewId());
        assertTrue(p2.isLast());

        // Page 3, Size 5 -> Além da janela (0 itens - sem lançar erro)
        FeedV2CandidatePage p3 = feedV2Service.getCandidatePage(requesterId, 3, 5, now);
        assertNotNull(p3);
        assertTrue(p3.isEmpty());
        assertEquals(3, p3.page());
        assertEquals(12, p3.windowSize());
        assertTrue(p3.isLast());
    }

    @Test
    @DisplayName("3.2 Paginação com janela cheia de 100 candidatos em páginas de 50")
    void shouldPaginateFullWindowOf100Candidates() {
        List<FeedCandidate> candidates = new ArrayList<>();
        List<RankedFeedCandidate> rankedList = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            FeedCandidate c = createCandidate(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), now.minusSeconds(i));
            candidates.add(c);
            rankedList.add(createRankedCandidate(c, 1.0 - (i * 0.005)));
        }

        when(feedCandidateRepository.retrieveCandidates(requesterId, FeedCandidateRepository.CANDIDATE_WINDOW))
                .thenReturn(candidates);
        when(feedV2Ranker.rank(eq(candidates), eq(now)))
                .thenReturn(rankedList);
        when(feedV2Diversifier.diversify(rankedList))
                .thenReturn(rankedList);

        // Page 0, Size 50 -> Itens 0..49
        FeedV2CandidatePage page0 = feedV2Service.getCandidatePage(requesterId, 0, 50, now);
        assertEquals(50, page0.items().size());
        assertEquals(100, page0.windowSize());
        assertEquals(2, page0.totalPages());
        assertFalse(page0.isLast());
        assertEquals(rankedList.get(0).candidate().reviewId(), page0.items().get(0).candidate().reviewId());
        assertEquals(rankedList.get(49).candidate().reviewId(), page0.items().get(49).candidate().reviewId());

        // Page 1, Size 50 -> Itens 50..99
        FeedV2CandidatePage page1 = feedV2Service.getCandidatePage(requesterId, 1, 50, now);
        assertEquals(50, page1.items().size());
        assertEquals(100, page1.windowSize());
        assertTrue(page1.isLast());
        assertEquals(rankedList.get(50).candidate().reviewId(), page1.items().get(0).candidate().reviewId());
        assertEquals(rankedList.get(99).candidate().reviewId(), page1.items().get(49).candidate().reviewId());

        // Page 2, Size 50 -> Além da janela (vazio)
        FeedV2CandidatePage page2 = feedV2Service.getCandidatePage(requesterId, 2, 50, now);
        assertTrue(page2.isEmpty());
        assertEquals(100, page2.windowSize());
        assertTrue(page2.isLast());
    }

    // -------------------------------------------------------------------------
    // 4. Determinismo e Preservação da Ordem do Diversifier
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("4.1 Determinismo: mesmos parâmetros e candidatos produzem resultado estritamente idêntico")
    void shouldProduceStrictlyDeterministicResults() {
        FeedCandidate c1 = createCandidate(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), now.minusSeconds(10));
        FeedCandidate c2 = createCandidate(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), now.minusSeconds(20));
        RankedFeedCandidate r1 = createRankedCandidate(c1, 0.90);
        RankedFeedCandidate r2 = createRankedCandidate(c2, 0.70);

        when(feedCandidateRepository.retrieveCandidates(requesterId, FeedCandidateRepository.CANDIDATE_WINDOW))
                .thenReturn(List.of(c1, c2));
        when(feedV2Ranker.rank(List.of(c1, c2), now))
                .thenReturn(List.of(r1, r2));
        when(feedV2Diversifier.diversify(List.of(r1, r2)))
                .thenReturn(List.of(r1, r2));

        FeedV2CandidatePage run1 = feedV2Service.getCandidatePage(requesterId, 0, 10, now);
        FeedV2CandidatePage run2 = feedV2Service.getCandidatePage(requesterId, 0, 10, now);

        assertEquals(run1.items().size(), run2.items().size());
        assertEquals(run1.items().get(0).candidate().reviewId(), run2.items().get(0).candidate().reviewId());
        assertEquals(run1.items().get(0).score().value(), run2.items().get(0).score().value());
        assertEquals(run1.items().get(1).candidate().reviewId(), run2.items().get(1).candidate().reviewId());
    }

    @Test
    @DisplayName("4.2 Preservação: Service preserva rigorosamente a ordem entregue pelo diversifier")
    void shouldPreserveDiversifierOrderingWithoutModification() {
        // Suponha que o diversifier alterou a ordem de [rA, rB, rC] para [rA, rC, rB]
        FeedCandidate cA = createCandidate(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), now);
        FeedCandidate cB = createCandidate(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), now);
        FeedCandidate cC = createCandidate(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), now);

        RankedFeedCandidate rA = createRankedCandidate(cA, 0.90);
        RankedFeedCandidate rB = createRankedCandidate(cB, 0.85);
        RankedFeedCandidate rC = createRankedCandidate(cC, 0.80);

        when(feedCandidateRepository.retrieveCandidates(requesterId, FeedCandidateRepository.CANDIDATE_WINDOW))
                .thenReturn(List.of(cA, cB, cC));
        when(feedV2Ranker.rank(any(), eq(now)))
                .thenReturn(List.of(rA, rB, rC));
        // Diversifier reordena para [rA, rC, rB] por diversidade de autor/alvo
        when(feedV2Diversifier.diversify(List.of(rA, rB, rC)))
                .thenReturn(List.of(rA, rC, rB));

        FeedV2CandidatePage page = feedV2Service.getCandidatePage(requesterId, 0, 10, now);

        assertEquals(3, page.items().size());
        assertEquals(rA.candidate().reviewId(), page.items().get(0).candidate().reviewId());
        assertEquals(rC.candidate().reviewId(), page.items().get(1).candidate().reviewId());
        assertEquals(rB.candidate().reviewId(), page.items().get(2).candidate().reviewId());
    }

    @Test
    @DisplayName("4.3 Alias findFeedCandidates delega fielmente para getCandidatePage")
    void aliasFindFeedCandidatesDelegatesFaithfully() {
        when(feedCandidateRepository.retrieveCandidates(requesterId, FeedCandidateRepository.CANDIDATE_WINDOW))
                .thenReturn(List.of());

        FeedV2CandidatePage result = feedV2Service.findFeedCandidates(requesterId, 0, 10, now);

        assertNotNull(result);
        assertTrue(result.isEmpty());
        verify(feedCandidateRepository).retrieveCandidates(requesterId, FeedCandidateRepository.CANDIDATE_WINDOW);
    }
}
