package com.rewit.domain.enums;

/**
 * Dimensões independentes de Rankings V1 no Rewit (ADR-016).
 * Cada ranking representa uma métrica factual autônoma, sem score composto ou ponderação por reputação.
 */
public enum RankingType {
    CHECK_INS,
    PLACES_EXPLORED,
    HELPFUL_VOTES
}
