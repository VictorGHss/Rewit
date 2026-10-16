package com.rewit.infrastructure.persistence.adapter;

import com.rewit.application.service.FeedV2Service;
import com.rewit.domain.feed.RankedFeedCandidate;
import com.rewit.infrastructure.persistence.repository.ReviewJpaRepository;
import com.rewit.infrastructure.persistence.repository.ReviewReactionJpaRepository;
import com.rewit.infrastructure.persistence.repository.ReviewTargetJpaRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A ordem do Feed V2 (ranking + diversidade) não pode depender do autor real de uma review anônima: com o autor real,
 * o espaçamento por autor afastaria a anônima das reviews identificadas dele, e a posição a ligaria a elas.
 */
@DisplayName("Testes Unitários: diversidade do Feed V2 sem o autor real de reviews anônimas")
class FeedCandidateAnonymityUnitTest {

    private static final Instant REFERENCE = Instant.parse("2026-10-16T12:00:00Z");

    private final UUID requester = UUID.randomUUID();
    private final UUID authorA = UUID.randomUUID();
    private final UUID authorB = UUID.randomUUID();
    private final UUID stranger = UUID.randomUUID();

    private final UUID a1 = UUID.randomUUID();
    private final UUID a2 = UUID.randomUUID();
    private final UUID third = UUID.randomUUID();
    private final UUID b1 = UUID.randomUUID();

    private static Object[] row(UUID reviewId, UUID authorId, int minutesAgo, boolean anonymous) {
        // Locais de contexto distintos: só a regra de autor interfere na ordem
        return new Object[]{reviewId, authorId, UUID.randomUUID(), REFERENCE.minusSeconds(60L * minutesAgo), false, anonymous};
    }

    /** Ordem final da descoberta para a terceira review publicada por {@code thirdAuthor}. */
    private List<UUID> discoveryOrder(UUID thirdAuthor, boolean thirdIsAnonymous) {
        ReviewJpaRepository reviews = mock(ReviewJpaRepository.class);
        ReviewTargetJpaRepository targets = mock(ReviewTargetJpaRepository.class);
        ReviewReactionJpaRepository reactions = mock(ReviewReactionJpaRepository.class);

        when(reviews.findFeedV2Candidates(any(), any(Pageable.class))).thenReturn(List.of());
        when(reviews.findFeedV2DiscoveryCandidates(any(), any(Pageable.class))).thenReturn(List.of(
                row(a1, authorA, 1, false),
                row(a2, authorA, 2, false),
                row(third, thirdAuthor, 3, thirdIsAnonymous),
                row(b1, authorB, 4, false)));
        when(targets.findByReviewIdIn(anyCollection())).thenReturn(List.of());
        when(reactions.countHelpfulByReviewIds(anyCollection())).thenReturn(List.of());

        FeedV2Service service = new FeedV2Service(new FeedCandidateRepositoryAdapter(reviews, targets, reactions));
        return service.getCandidatePage(requester, 0, 50, REFERENCE).items().stream()
                .map(RankedFeedCandidate::candidate)
                .map(candidate -> candidate.reviewId())
                .toList();
    }

    @Test
    @DisplayName("Anônima do autor A ocupa a mesma posição que teria se fosse de um desconhecido")
    void anonymousReviewPositionDoesNotDependOnRealAuthor() {
        List<UUID> byAuthorA = discoveryOrder(authorA, true);
        List<UUID> byStranger = discoveryOrder(stranger, true);

        assertEquals(byStranger, byAuthorA, "A ordem não pode ligar a anônima às reviews identificadas do autor");
        assertEquals(List.of(a1, a2, third, b1), byAuthorA);
    }

    @Test
    @DisplayName("Controle: a mesma review identificada do autor A é espaçada pela diversidade")
    void identifiedReviewOfSameAuthorIsStillSpaced() {
        List<UUID> identifiedByAuthorA = discoveryOrder(authorA, false);

        assertEquals(List.of(a1, a2, b1, third), identifiedByAuthorA,
                "Sem anonimato a diversidade por autor continua valendo");
        assertNotEquals(discoveryOrder(authorA, true), identifiedByAuthorA);
    }
}
