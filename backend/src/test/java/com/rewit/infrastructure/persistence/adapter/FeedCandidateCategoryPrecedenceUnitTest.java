package com.rewit.infrastructure.persistence.adapter;

import com.rewit.domain.enums.InterestCategory;
import com.rewit.domain.feed.FeedCandidate;
import com.rewit.infrastructure.persistence.entity.ReviewTargetJpaEntity;
import com.rewit.infrastructure.persistence.repository.RateableTargetJpaRepository;
import com.rewit.infrastructure.persistence.repository.ReviewJpaRepository;
import com.rewit.infrastructure.persistence.repository.ReviewReactionJpaRepository;
import com.rewit.infrastructure.persistence.repository.ReviewTargetJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("Testes Unitários: Precedência de Categoria entre Contexto e Alvo Principal no FeedCandidateRepositoryAdapter")
class FeedCandidateCategoryPrecedenceUnitTest {

    private ReviewJpaRepository reviewJpaRepository;
    private ReviewTargetJpaRepository reviewTargetJpaRepository;
    private ReviewReactionJpaRepository reviewReactionJpaRepository;
    private RateableTargetJpaRepository rateableTargetJpaRepository;
    private FeedCandidateRepositoryAdapter adapter;

    private final UUID requesterId = UUID.randomUUID();
    private final UUID authorId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        reviewJpaRepository = mock(ReviewJpaRepository.class);
        reviewTargetJpaRepository = mock(ReviewTargetJpaRepository.class);
        reviewReactionJpaRepository = mock(ReviewReactionJpaRepository.class);
        rateableTargetJpaRepository = mock(RateableTargetJpaRepository.class);

        adapter = new FeedCandidateRepositoryAdapter(
                reviewJpaRepository,
                reviewTargetJpaRepository,
                reviewReactionJpaRepository,
                rateableTargetJpaRepository
        );

        when(reviewReactionJpaRepository.countHelpfulByReviewIds(anyCollection())).thenReturn(Collections.emptyList());
    }

    private FeedCandidate executeRetrieval(UUID contextPlaceId, String contextCategory,
                                          UUID primaryTargetId, String primaryTargetCategory) {
        UUID reviewId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-10-28T12:00:00Z");

        // Query 1: Review row [reviewId, authorId, contextPlaceId, createdAt, verified, anonymous]
        Object[] reviewRow = new Object[]{reviewId, authorId, contextPlaceId, createdAt, true, false};
        List<Object[]> reviewRows = Collections.singletonList(reviewRow);
        when(reviewJpaRepository.findFeedV2Candidates(any(), any(Pageable.class)))
                .thenReturn(reviewRows);

        // Query 2: ReviewTarget
        if (primaryTargetId != null) {
            ReviewTargetJpaEntity rt = new ReviewTargetJpaEntity(
                    UUID.randomUUID(), reviewId, primaryTargetId, BigDecimal.valueOf(5.0), "Comentário", createdAt
            );
            when(reviewTargetJpaRepository.findByReviewIdIn(anyCollection())).thenReturn(Collections.singletonList(rt));
        } else {
            when(reviewTargetJpaRepository.findByReviewIdIn(anyCollection())).thenReturn(Collections.emptyList());
        }

        // Query 4: Categorias dos alvos
        List<Object[]> categoryRows = new ArrayList<>();
        if (contextPlaceId != null && contextCategory != null) {
            categoryRows.add(new Object[]{contextPlaceId, contextCategory});
        }
        if (primaryTargetId != null && primaryTargetCategory != null) {
            categoryRows.add(new Object[]{primaryTargetId, primaryTargetCategory});
        }
        when(rateableTargetJpaRepository.findCategoriesByIds(anyCollection())).thenReturn(categoryRows);

        List<FeedCandidate> candidates = adapter.retrieveCandidates(requesterId, 10);
        assertEquals(1, candidates.size(), "Deveria recuperar exatamente 1 candidato");
        return candidates.get(0);
    }

    @Test
    @DisplayName("1. Local de contexto mapeável tem precedência sobre o alvo principal")
    void shouldPrioritizeContextCategoryWhenContextPlaceIsMappable() {
        UUID contextPlaceId = UUID.randomUUID();
        UUID primaryTargetId = UUID.randomUUID();

        // Contexto é uma cafeteria; alvo principal é um livro vendido no local
        FeedCandidate candidate = executeRetrieval(
                contextPlaceId, "Cafeteria",
                primaryTargetId, "Livros"
        );

        assertEquals(InterestCategory.CAFE, candidate.category(),
                "A categoria da review deve ser a do contexto físico (CAFE), e não do alvo principal (BOOKS)");
        assertEquals(contextPlaceId, candidate.targetId(), "O targetId para diversidade deve ser o contextPlaceId");
    }

    @Test
    @DisplayName("2. Sem local de contexto, a categoria do alvo principal é mapeada")
    void shouldUsePrimaryTargetCategoryWhenContextPlaceIsNull() {
        UUID primaryTargetId = UUID.randomUUID();

        // Sem contexto; alvo principal é um produto/estabelecimento de games
        FeedCandidate candidate = executeRetrieval(
                null, null,
                primaryTargetId, "Loja de Games"
        );

        assertEquals(InterestCategory.GAMES, candidate.category(),
                "Na ausência de contextPlaceId, a categoria deve ser resolvida a partir do alvo principal");
        assertEquals(primaryTargetId, candidate.targetId());
    }

    @Test
    @DisplayName("3. Contexto com categoria desconhecida/não mapeável (ex: GERAL) atribui afinidade neutra (null)")
    void shouldReturnNeutralCategoryWhenContextPlaceHasUnrecognizedCategory() {
        UUID contextPlaceId = UUID.randomUUID();
        UUID primaryTargetId = UUID.randomUUID();

        // Contexto físico com categoria genérica "GERAL"; produto com categoria "Games"
        FeedCandidate candidate = executeRetrieval(
                contextPlaceId, "GERAL",
                primaryTargetId, "Games"
        );

        // Precedência do contexto: a string "GERAL" foi selecionada, e por ser neutra/não mapeável resulta em null
        assertNull(candidate.category(),
                "Quando o contexto possui categoria não reconhecida, a precedência do contexto atribui afinidade neutra");
        assertEquals(contextPlaceId, candidate.targetId());
    }

    @Test
    @DisplayName("4. Contexto com categoria nula ou em branco faz fallback para a categoria do alvo principal")
    void shouldFallbackToPrimaryTargetCategoryWhenContextPlaceCategoryIsEmptyOrNull() {
        UUID contextPlaceId = UUID.randomUUID();
        UUID primaryTargetId = UUID.randomUUID();

        // Contexto físico no banco sem categoria (em branco); produto com categoria "Tecnologia"
        FeedCandidate candidate = executeRetrieval(
                contextPlaceId, "   ",
                primaryTargetId, "Smartphones"
        );

        assertEquals(InterestCategory.TECHNOLOGY, candidate.category(),
                "Quando a categoria do contexto é em branco ou nula, faz fallback para a categoria do alvo principal");
        assertEquals(contextPlaceId, candidate.targetId(), "targetId preserva o contexto físico");
    }

    @Test
    @DisplayName("5. Contexto e alvo com categorias conflitantes preservam a precedência estrita do contexto")
    void shouldResolveContextOverConflictingTargetCategory() {
        UUID contextPlaceId = UUID.randomUUID();
        UUID primaryTargetId = UUID.randomUUID();

        // Contexto: Restaurante; Alvo: Loja de Roupas
        FeedCandidate candidate = executeRetrieval(
                contextPlaceId, "Pizzaria",
                primaryTargetId, "Moda e Vestuário"
        );

        assertEquals(InterestCategory.RESTAURANT, candidate.category(),
                "Em caso de conflito, a categoria do contexto físico (RESTAURANT) deve prevalecer sobre o alvo (FASHION)");
    }

    @Test
    @DisplayName("6. Contexto e alvo ambos com categorias desconhecidas retornam afinidade neutra (null)")
    void shouldReturnNeutralWhenBothContextAndTargetAreUnmapped() {
        UUID contextPlaceId = UUID.randomUUID();
        UUID primaryTargetId = UUID.randomUUID();

        FeedCandidate candidate = executeRetrieval(
                contextPlaceId, "Material de Construção",
                primaryTargetId, "Outros"
        );

        assertNull(candidate.category(), "Termos não mapeáveis devem retornar categoria nula (afinidade neutra)");
    }

    @Test
    @DisplayName("7. Candidato sem nenhuma categoria nos alvos retorna afinidade neutra (null)")
    void shouldReturnNeutralWhenNoCategoriesFound() {
        UUID primaryTargetId = UUID.randomUUID();

        FeedCandidate candidate = executeRetrieval(
                null, null,
                primaryTargetId, null
        );

        assertNull(candidate.category(), "Ausência de categoria deve resultar em afinidade neutra (null)");
    }
}
