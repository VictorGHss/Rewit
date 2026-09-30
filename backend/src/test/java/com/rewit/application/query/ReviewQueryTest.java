package com.rewit.application.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.rewit.domain.enums.ReviewStatus;

class ReviewQueryTest {

    @Test
    @DisplayName("1. Usa defaults seguros e não permite status público não ativo")
    void shouldUseSecureDefaults() {
        ReviewQuery query = ReviewQuery.builder().build();

        assertEquals(ReviewStatus.ACTIVE, query.status());
        assertEquals(ReviewSort.NEWEST, query.sort());
        assertEquals(0, query.page());
        assertEquals(20, query.size());
        assertEquals(ReviewVisibilityScope.ONLY_PUBLIC, query.visibilityScope());
        assertFalse(query.verifiedOnly());
        assertNull(query.targetId());
        assertNull(query.authorUserId());
    }

    @Test
    @DisplayName("2. Rejeita UNDER_REVIEW e REMOVED em query pública")
    void shouldRejectUnsafeStatusValues() {
        IllegalArgumentException underReview = assertThrows(IllegalArgumentException.class,
                () -> ReviewQuery.builder().status(ReviewStatus.UNDER_REVIEW).build());
        assertTrue(underReview.getMessage().contains("ACTIVE"));

        IllegalArgumentException removed = assertThrows(IllegalArgumentException.class,
                () -> ReviewQuery.builder().status(ReviewStatus.REMOVED).build());
        assertTrue(removed.getMessage().contains("ACTIVE"));
    }

    @Test
    @DisplayName("3. Valida paginação, sort e status seguros")
    void shouldValidatePaginationAndSort() {
        IllegalArgumentException page = assertThrows(IllegalArgumentException.class,
                () -> ReviewQuery.builder().page(-1).build());
        assertTrue(page.getMessage().contains("page"));

        IllegalArgumentException size = assertThrows(IllegalArgumentException.class,
                () -> ReviewQuery.builder().size(0).build());
        assertTrue(size.getMessage().contains("size"));

        IllegalArgumentException sizeLimit = assertThrows(IllegalArgumentException.class,
                () -> ReviewQuery.builder().size(51).build());
        assertTrue(sizeLimit.getMessage().contains("size"));

        IllegalArgumentException sort = assertThrows(IllegalArgumentException.class,
                () -> ReviewQuery.builder().sort(ReviewSort.fromRaw("relevance")).build());
        assertTrue(sort.getMessage().contains("sort"));
    }

    @Test
    @DisplayName("4. Aceita combinações válidas de target, autor, verified e rating")
    void shouldAcceptValidQueryCombinations() {
        UUID targetId = UUID.randomUUID();
        UUID authorUserId = UUID.randomUUID();

        ReviewQuery query = ReviewQuery.builder()
                .targetId(targetId)
                .authorUserId(authorUserId)
                .verifiedOnly(true)
                .ratingMin(BigDecimal.valueOf(3.0))
                .ratingMax(BigDecimal.valueOf(5.0))
                .createdFrom(Instant.parse("2026-01-01T00:00:00Z"))
                .createdTo(Instant.parse("2026-12-31T23:59:59Z"))
                .page(2)
                .size(10)
                .build();

        assertEquals(targetId, query.targetId());
        assertEquals(authorUserId, query.authorUserId());
        assertTrue(query.verifiedOnly());
        assertEquals(BigDecimal.valueOf(3.0), query.ratingMin());
        assertEquals(BigDecimal.valueOf(5.0), query.ratingMax());
        assertEquals(2, query.page());
        assertEquals(10, query.size());
    }

    @Test
    @DisplayName("5. Rejeita ratingMin maior que ratingMax")
    void shouldRejectInvalidRatingBounds() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ReviewQuery.builder()
                        .ratingMin(BigDecimal.valueOf(5.0))
                        .ratingMax(BigDecimal.valueOf(3.0))
                        .build());

        assertTrue(ex.getMessage().contains("ratingMin"));
    }

    @Test
    @DisplayName("6. Rejeita visibilidade privada/seguidores sem requester válido")
    void shouldRejectContextualVisibilityWithoutRequester() {
        IllegalArgumentException followers = assertThrows(IllegalArgumentException.class,
                () -> ReviewQuery.builder()
                        .visibilityScope(ReviewVisibilityScope.FOLLOWERS)
                        .build());
        assertTrue(followers.getMessage().contains("requesterUserId"));

        IllegalArgumentException privateScope = assertThrows(IllegalArgumentException.class,
                () -> ReviewQuery.builder()
                        .visibilityScope(ReviewVisibilityScope.PRIVATE)
                        .build());
        assertTrue(privateScope.getMessage().contains("requesterUserId"));
    }

    @Test
    @DisplayName("7. Converte sort de entrada sem aceitar strings futuras do domínio")
    void shouldNormalizeSupportedSortsOnly() {
        assertEquals(ReviewSort.NEWEST, ReviewSort.fromRaw("newest"));
        assertEquals(ReviewSort.RATING_DESC, ReviewSort.fromRaw("rating_desc"));
        assertEquals(ReviewSort.RATING_ASC, ReviewSort.fromRaw("rating_asc"));

        IllegalArgumentException unsupported = assertThrows(IllegalArgumentException.class,
                () -> ReviewSort.fromRaw("relevance"));
        assertTrue(unsupported.getMessage().contains("relevance"));
    }
}
