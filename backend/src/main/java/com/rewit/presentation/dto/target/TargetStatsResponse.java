package com.rewit.presentation.dto.target;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * DTO de resposta HTTP para estatísticas agregadas de um alvo avaliável (Step 13.0).
 * Projeção direta de {@link com.rewit.application.dto.ReviewDto.TargetStatsView}.
 */
public record TargetStatsResponse(
        UUID targetId,
        BigDecimal averageRating,
        int reviewsCount,
        // Alvo sem avaliações ainda não foi calculado: o campo é omitido do JSON
        @JsonInclude(JsonInclude.Include.NON_NULL) Instant lastCalculatedAt
) {}
