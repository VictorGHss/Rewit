package com.rewit.domain.enums;

import com.rewit.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Testes Unitários: RankingPeriod")
class RankingPeriodUnitTest {

    @Test
    @DisplayName("Padrão WEEK quando string for nula ou em branco")
    void shouldDefaultToWeekWhenBlank() {
        assertEquals(RankingPeriod.WEEK, RankingPeriod.fromString(null));
        assertEquals(RankingPeriod.WEEK, RankingPeriod.fromString("   "));
    }

    @Test
    @DisplayName("Converte WEEK e MONTH com trim e case-insensitive")
    void shouldConvertValidPeriods() {
        assertEquals(RankingPeriod.WEEK, RankingPeriod.fromString("week"));
        assertEquals(RankingPeriod.WEEK, RankingPeriod.fromString("WEEK"));
        assertEquals(RankingPeriod.MONTH, RankingPeriod.fromString("month"));
        assertEquals(RankingPeriod.MONTH, RankingPeriod.fromString("  MONTH  "));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ALL_TIME", "YEAR", "DAY", "TODAY", "CUSTOM"})
    @DisplayName("Lança BusinessException com INVALID_RANKING_PERIOD para períodos inválidos ou proibidos")
    void shouldThrowExceptionForInvalidPeriods(String invalidPeriod) {
        BusinessException ex = assertThrows(BusinessException.class, () -> RankingPeriod.fromString(invalidPeriod));
        assertEquals("INVALID_RANKING_PERIOD", ex.getErrorCode());
    }
}
