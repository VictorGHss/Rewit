package com.rewit.application.dto.ranking;

/**
 * DTO para preferências de participação do usuário em rankings (ADR-016).
 */
public record RankingPreferencesDto(
        boolean showInRankings
) {
}
