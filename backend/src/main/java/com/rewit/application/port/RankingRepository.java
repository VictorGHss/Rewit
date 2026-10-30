package com.rewit.application.port;

import com.rewit.domain.model.BrazilianState;
import com.rewit.domain.model.RankingItem;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Porta de persistência para recuperação agregada de rankings locais V1 (ADR-016).
 */
public interface RankingRepository {

    /**
     * Recupera o ranking de check-ins verificados elegíveis.
     */
    List<RankingItem> findCheckInsRanking(
            Instant effectiveStart,
            Instant periodEnd,
            BrazilianState state,
            String city,
            Set<String> categories,
            int limit
    );

    /**
     * Recupera o ranking de locais distintos explorados (avaliados com review público).
     */
    List<RankingItem> findPlacesExploredRanking(
            Instant effectiveStart,
            Instant periodEnd,
            BrazilianState state,
            String city,
            Set<String> categories,
            int limit
    );

    /**
     * Recupera o ranking de votos úteis recebidos em avaliações ativas e públicas.
     */
    List<RankingItem> findHelpfulVotesRanking(
            Instant effectiveStart,
            Instant periodEnd,
            BrazilianState state,
            String city,
            Set<String> categories,
            int limit
    );
}
