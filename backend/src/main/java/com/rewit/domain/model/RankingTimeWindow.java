package com.rewit.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Janela temporal de apuração de rankings, calculada em America/Sao_Paulo (ADR-016).
 *
 * <p>Regras essenciais:
 * <ul>
 *   <li>Início inclusivo e fim exclusivo.</li>
 *   <li>Se a data de ativação persistente dos rankings ({@code rankingsActivatedAt}) for posterior ao início civil
 *       do período, o início efetivo é truncado para a ativação e {@code isPartialPeriod} é {@code true}.</li>
 *   <li>Se a ativação for posterior ou igual ao fim do período, a janela é considerada inativa ({@code isActive = false}),
 *       gerando retorno vazio válido.</li>
 * </ul>
 */
public record RankingTimeWindow(
        Instant periodStart,
        Instant periodEnd,
        Instant effectiveStart,
        boolean isPartialPeriod,
        boolean isActive
) {
    public RankingTimeWindow {
        Objects.requireNonNull(periodStart, "periodStart must not be null");
        Objects.requireNonNull(periodEnd, "periodEnd must not be null");
        Objects.requireNonNull(effectiveStart, "effectiveStart must not be null");
    }

    public static RankingTimeWindow inactive(Instant periodStart, Instant periodEnd, Instant effectiveStart) {
        return new RankingTimeWindow(periodStart, periodEnd, effectiveStart, false, false);
    }

    public static RankingTimeWindow active(Instant periodStart, Instant periodEnd, Instant effectiveStart, boolean isPartialPeriod) {
        return new RankingTimeWindow(periodStart, periodEnd, effectiveStart, isPartialPeriod, true);
    }
}
