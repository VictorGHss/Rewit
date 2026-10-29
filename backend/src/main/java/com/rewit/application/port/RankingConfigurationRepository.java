package com.rewit.application.port;

import java.time.Instant;

/**
 * Porta de persistência para recuperação do timestamp singleton de ativação dos rankings.
 */
public interface RankingConfigurationRepository {

    /**
     * Retorna o timestamp de ativação dos rankings V1 no banco de dados.
     */
    Instant getRankingsActivatedAt();
}

