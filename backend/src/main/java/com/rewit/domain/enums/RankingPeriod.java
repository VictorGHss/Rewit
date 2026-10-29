package com.rewit.domain.enums;

import com.rewit.common.exception.BusinessException;
import org.springframework.http.HttpStatus;

import java.util.Locale;

/**
 * Períodos admitidos para apuração de Rankings V1 (ADR-016).
 * Apenas períodos cíclicos de agregação local são admitidos; ALL_TIME é expressamente proibido na V1.
 */
public enum RankingPeriod {
    WEEK,
    MONTH;

    public static RankingPeriod fromString(String value) {
        if (value == null || value.isBlank()) {
            return WEEK;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(
                    "Período de ranking inválido: " + value + ". Valores aceitos: WEEK, MONTH.",
                    HttpStatus.BAD_REQUEST,
                    "INVALID_RANKING_PERIOD"
            );
        }
    }
}

