package com.rewit.application.service;

import com.rewit.domain.enums.RankingPeriod;
import com.rewit.domain.model.RankingTimeWindow;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.Objects;

/**
 * Calculador determinístico de janelas de apuração de rankings em America/Sao_Paulo (ADR-016).
 */
@Component
public class RankingTimeWindowCalculator {

    public static final ZoneId ZONE_SAO_PAULO = ZoneId.of("America/Sao_Paulo");

    public RankingTimeWindow calculateWindow(RankingPeriod period, Instant now, Instant rankingsActivatedAt) {
        Objects.requireNonNull(period, "RankingPeriod must not be null");
        Instant currentInstant = now != null ? now : Instant.now();
        ZonedDateTime zdtNow = currentInstant.atZone(ZONE_SAO_PAULO);

        ZonedDateTime zdtStart;
        ZonedDateTime zdtEnd;

        if (period == RankingPeriod.WEEK) {
            LocalDate monday = zdtNow.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            zdtStart = monday.atStartOfDay(ZONE_SAO_PAULO);
            zdtEnd = zdtStart.plusWeeks(1);
        } else {
            LocalDate firstDayOfMonth = zdtNow.toLocalDate().withDayOfMonth(1);
            zdtStart = firstDayOfMonth.atStartOfDay(ZONE_SAO_PAULO);
            zdtEnd = zdtStart.plusMonths(1);
        }

        Instant periodStart = zdtStart.toInstant();
        Instant periodEnd = zdtEnd.toInstant();

        if (rankingsActivatedAt == null) {
            return RankingTimeWindow.active(periodStart, periodEnd, periodStart, false);
        }

        if (!rankingsActivatedAt.isBefore(periodEnd)) {
            // Ativação futura em relação ao encerramento do período
            return RankingTimeWindow.inactive(periodStart, periodEnd, periodEnd);
        }

        if (periodStart.isBefore(rankingsActivatedAt)) {
            // Período civil iniciado antes da data de ativação: período parcial
            return RankingTimeWindow.active(periodStart, periodEnd, rankingsActivatedAt, true);
        }

        return RankingTimeWindow.active(periodStart, periodEnd, periodStart, false);
    }
}

