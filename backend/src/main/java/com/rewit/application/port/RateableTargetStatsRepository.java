package com.rewit.application.port;

import com.rewit.domain.model.RateableTargetStats;

import java.util.Optional;
import java.util.UUID;

/**
 * Porta de saída da aplicação para operações de persistência, consulta e agregação de RateableTargetStats (Step 13.0).
 */
public interface RateableTargetStatsRepository {

    RateableTargetStats save(RateableTargetStats stats);

    Optional<RateableTargetStats> findByTargetId(UUID targetId);

    RateableTargetStats recalculateAndSave(UUID targetId);
}
