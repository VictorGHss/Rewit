package com.rewit.application.service;

import com.rewit.domain.enums.RankingPeriod;
import com.rewit.domain.model.RankingTimeWindow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Testes Unitários: RankingTimeWindowCalculator (ADR-016)")
class RankingTimeWindowCalculatorUnitTest {

    private final RankingTimeWindowCalculator calculator = new RankingTimeWindowCalculator();
    private static final ZoneId BRT = ZoneId.of("America/Sao_Paulo");

    @Nested
    @DisplayName("Cálculo de Período Semanal (WEEK)")
    class WeekPeriodTests {

        @Test
        @DisplayName("Semana civil calculada de segunda 00:00 a próxima segunda 00:00 no fuso de São Paulo")
        void shouldCalculateWeekBoundariesInSaoPauloTime() {
            // Quinta-feira, 29 de outubro de 2026 às 17:00 BRT
            ZonedDateTime nowBrt = ZonedDateTime.of(2026, 10, 29, 17, 0, 0, 0, BRT);
            Instant now = nowBrt.toInstant();

            // Ativação ocorrida antes do início da semana
            Instant activatedAt = ZonedDateTime.of(2026, 10, 1, 0, 0, 0, 0, BRT).toInstant();

            RankingTimeWindow window = calculator.calculateWindow(RankingPeriod.WEEK, now, activatedAt);

            assertTrue(window.isActive());
            assertFalse(window.isPartialPeriod());

            // Segunda-feira, 26 de outubro de 2026 00:00:00 BRT
            ZonedDateTime expectedStart = ZonedDateTime.of(2026, 10, 26, 0, 0, 0, 0, BRT);
            // Próxima segunda-feira, 02 de novembro de 2026 00:00:00 BRT
            ZonedDateTime expectedEnd = ZonedDateTime.of(2026, 11, 2, 0, 0, 0, 0, BRT);

            assertEquals(expectedStart.toInstant(), window.periodStart());
            assertEquals(expectedEnd.toInstant(), window.periodEnd());
            assertEquals(expectedStart.toInstant(), window.effectiveStart());
        }

        @Test
        @DisplayName("Semana truncada quando ativação ocorre durante a semana corrente (período parcial)")
        void shouldMarkPartialPeriodWhenActivatedMidWeek() {
            // Quinta-feira, 29 de outubro de 2026 às 17:00 BRT
            ZonedDateTime nowBrt = ZonedDateTime.of(2026, 10, 29, 17, 0, 0, 0, BRT);
            Instant now = nowBrt.toInstant();

            // Ativação na quarta-feira, 28 de outubro de 2026 às 10:00 BRT
            Instant activatedAt = ZonedDateTime.of(2026, 10, 28, 10, 0, 0, 0, BRT).toInstant();

            RankingTimeWindow window = calculator.calculateWindow(RankingPeriod.WEEK, now, activatedAt);

            assertTrue(window.isActive());
            assertTrue(window.isPartialPeriod());

            ZonedDateTime expectedWeekStart = ZonedDateTime.of(2026, 10, 26, 0, 0, 0, 0, BRT);
            ZonedDateTime expectedWeekEnd = ZonedDateTime.of(2026, 11, 2, 0, 0, 0, 0, BRT);

            assertEquals(expectedWeekStart.toInstant(), window.periodStart());
            assertEquals(expectedWeekEnd.toInstant(), window.periodEnd());
            assertEquals(activatedAt, window.effectiveStart());
        }
    }

    @Nested
    @DisplayName("Cálculo de Período Mensal (MONTH)")
    class MonthPeriodTests {

        @Test
        @DisplayName("Mês civil calculado do dia 1 às 00:00 ao dia 1 do mês seguinte às 00:00 no fuso de São Paulo")
        void shouldCalculateMonthBoundariesInSaoPauloTime() {
            // Quinta-feira, 29 de outubro de 2026 às 17:00 BRT
            ZonedDateTime nowBrt = ZonedDateTime.of(2026, 10, 29, 17, 0, 0, 0, BRT);
            Instant now = nowBrt.toInstant();

            Instant activatedAt = ZonedDateTime.of(2026, 9, 15, 0, 0, 0, 0, BRT).toInstant();

            RankingTimeWindow window = calculator.calculateWindow(RankingPeriod.MONTH, now, activatedAt);

            assertTrue(window.isActive());
            assertFalse(window.isPartialPeriod());

            ZonedDateTime expectedStart = ZonedDateTime.of(2026, 10, 1, 0, 0, 0, 0, BRT);
            ZonedDateTime expectedEnd = ZonedDateTime.of(2026, 11, 1, 0, 0, 0, 0, BRT);

            assertEquals(expectedStart.toInstant(), window.periodStart());
            assertEquals(expectedEnd.toInstant(), window.periodEnd());
            assertEquals(expectedStart.toInstant(), window.effectiveStart());
        }

        @Test
        @DisplayName("Mês truncado quando ativação ocorre no meio do mês corrente (período parcial)")
        void shouldMarkPartialPeriodWhenActivatedMidMonth() {
            // Quinta-feira, 29 de outubro de 2026 às 17:00 BRT
            ZonedDateTime nowBrt = ZonedDateTime.of(2026, 10, 29, 17, 0, 0, 0, BRT);
            Instant now = nowBrt.toInstant();

            // Ativação no dia 15 de outubro de 2026 às 12:00 BRT
            Instant activatedAt = ZonedDateTime.of(2026, 10, 15, 12, 0, 0, 0, BRT).toInstant();

            RankingTimeWindow window = calculator.calculateWindow(RankingPeriod.MONTH, now, activatedAt);

            assertTrue(window.isActive());
            assertTrue(window.isPartialPeriod());

            ZonedDateTime expectedMonthStart = ZonedDateTime.of(2026, 10, 1, 0, 0, 0, 0, BRT);
            ZonedDateTime expectedMonthEnd = ZonedDateTime.of(2026, 11, 1, 0, 0, 0, 0, BRT);

            assertEquals(expectedMonthStart.toInstant(), window.periodStart());
            assertEquals(expectedMonthEnd.toInstant(), window.periodEnd());
            assertEquals(activatedAt, window.effectiveStart());
        }

        @Test
        @DisplayName("Janela inativa quando a ativação persistente ocorre no futuro em relação ao encerramento do período")
        void shouldBeInactiveWhenActivatedAfterPeriodEnd() {
            // Consulta para o mês de outubro de 2026
            ZonedDateTime nowBrt = ZonedDateTime.of(2026, 10, 29, 17, 0, 0, 0, BRT);
            Instant now = nowBrt.toInstant();

            // Ativação prevista para o futuro: 15 de novembro de 2026
            Instant activatedAt = ZonedDateTime.of(2026, 11, 15, 0, 0, 0, 0, BRT).toInstant();

            RankingTimeWindow window = calculator.calculateWindow(RankingPeriod.MONTH, now, activatedAt);

            assertFalse(window.isActive());
        }
    }
}

