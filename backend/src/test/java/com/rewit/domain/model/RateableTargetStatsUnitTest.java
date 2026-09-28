package com.rewit.domain.model;

import com.rewit.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Testes Unitários de Domínio - RateableTargetStats (Step 13.0)")
class RateableTargetStatsUnitTest {

    @Test
    @DisplayName("Deve inicializar estatísticas válidas com timestamp padrão")
    void shouldInitializeValidStats() {
        UUID targetId = UUID.randomUUID();
        RateableTargetStats stats = new RateableTargetStats(targetId, new BigDecimal("4.50"), 1);

        assertEquals(targetId, stats.getTargetId());
        assertEquals(new BigDecimal("4.50"), stats.getAverageRating());
        assertEquals(1, stats.getReviewsCount());
        assertNotNull(stats.getLastCalculatedAt());
    }

    @Test
    @DisplayName("Deve inicializar com construtor de persistência preservando lastCalculatedAt")
    void shouldInitializeWithExplicitTimestamp() {
        UUID targetId = UUID.randomUUID();
        Instant pastTimestamp = Instant.parse("2026-09-01T10:00:00Z");
        RateableTargetStats stats = new RateableTargetStats(targetId, new BigDecimal("3.75"), 2, pastTimestamp);

        assertEquals(targetId, stats.getTargetId());
        assertEquals(new BigDecimal("3.75"), stats.getAverageRating());
        assertEquals(2, stats.getReviewsCount());
        assertEquals(pastTimestamp, stats.getLastCalculatedAt());
    }

    @Test
    @DisplayName("Deve representar estado de zero reviews ativas com nota 0.00 e count 0")
    void shouldRepresentZeroActiveReviews() {
        UUID targetId = UUID.randomUUID();
        RateableTargetStats stats = new RateableTargetStats(targetId, BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP), 0);

        assertEquals(new BigDecimal("0.00"), stats.getAverageRating());
        assertEquals(0, stats.getReviewsCount());
    }

    @Test
    @DisplayName("Deve atualizar estatísticas para novo valor agregado e novo timestamp")
    void shouldUpdateStatsCorrectly() {
        UUID targetId = UUID.randomUUID();
        RateableTargetStats stats = new RateableTargetStats(targetId, new BigDecimal("4.00"), 1);
        Instant firstTimestamp = stats.getLastCalculatedAt();

        stats.updateStats(new BigDecimal("4.50"), 2);

        assertEquals(new BigDecimal("4.50"), stats.getAverageRating());
        assertEquals(2, stats.getReviewsCount());
        assertTrue(!stats.getLastCalculatedAt().isBefore(firstTimestamp));
    }

    @Test
    @DisplayName("Deve rejeitar média menor que zero ou maior que 5.00")
    void shouldRejectInvalidAverageRating() {
        UUID targetId = UUID.randomUUID();

        BusinessException exNegative = assertThrows(BusinessException.class, () ->
                new RateableTargetStats(targetId, new BigDecimal("-0.01"), 1)
        );
        assertEquals("INVALID_AVERAGE_RATING", exNegative.getErrorCode());

        BusinessException exOverMax = assertThrows(BusinessException.class, () ->
                new RateableTargetStats(targetId, new BigDecimal("5.01"), 1)
        );
        assertEquals("INVALID_AVERAGE_RATING", exOverMax.getErrorCode());

        RateableTargetStats stats = new RateableTargetStats(targetId, new BigDecimal("4.00"), 1);
        assertThrows(BusinessException.class, () -> stats.updateStats(new BigDecimal("5.01"), 2));
        assertThrows(BusinessException.class, () -> stats.updateStats(new BigDecimal("-0.01"), 2));
    }

    @Test
    @DisplayName("Deve rejeitar reviewsCount negativo")
    void shouldRejectNegativeReviewsCount() {
        UUID targetId = UUID.randomUUID();

        BusinessException ex = assertThrows(BusinessException.class, () ->
                new RateableTargetStats(targetId, new BigDecimal("4.00"), -1)
        );
        assertEquals("INVALID_REVIEWS_COUNT", ex.getErrorCode());

        RateableTargetStats stats = new RateableTargetStats(targetId, new BigDecimal("4.00"), 1);
        assertThrows(BusinessException.class, () -> stats.updateStats(new BigDecimal("4.00"), -5));
    }

    @Test
    @DisplayName("Deve rejeitar targetId nulo")
    void shouldRejectNullTargetId() {
        BusinessException ex = assertThrows(BusinessException.class, () ->
                new RateableTargetStats(null, new BigDecimal("4.00"), 1)
        );
        assertEquals("MISSING_TARGET_ID", ex.getErrorCode());
    }

    @Test
    @DisplayName("Deve validar arredondamento HALF_UP com 2 casas decimais em média de avaliações")
    void shouldValidateHalfUpRoundingForAverage() {
        // Exemplo: soma = 4.0 + 4.5 = 8.5 / 2 = 4.25
        BigDecimal sum1 = new BigDecimal("4.0").add(new BigDecimal("4.5"));
        BigDecimal avg1 = sum1.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
        assertEquals(new BigDecimal("4.25"), avg1);

        // Exemplo: soma = 4.0 + 4.0 + 5.0 = 13.0 / 3 = 4.3333... -> 4.33
        BigDecimal sum2 = new BigDecimal("13.0");
        BigDecimal avg2 = sum2.divide(BigDecimal.valueOf(3), 2, RoundingMode.HALF_UP);
        assertEquals(new BigDecimal("4.33"), avg2);

        // Exemplo: soma = 4.0 + 5.0 + 5.0 = 14.0 / 3 = 4.6666... -> 4.67
        BigDecimal sum3 = new BigDecimal("14.0");
        BigDecimal avg3 = sum3.divide(BigDecimal.valueOf(3), 2, RoundingMode.HALF_UP);
        assertEquals(new BigDecimal("4.67"), avg3);
    }
}
