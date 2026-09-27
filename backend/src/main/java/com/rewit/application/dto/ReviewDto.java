package com.rewit.application.dto;

import com.rewit.domain.enums.TargetType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Contratos de DTO representacionais para a camada de aplicação.
 */
public final class ReviewDto {

    private ReviewDto() {}

    public record TargetSummary(
            UUID targetId,
            TargetType targetType,
            BigDecimal rating,
            String specificComment
    ) {}

    public record ReviewView(
            UUID id,
            UUID userId,
            UUID contextPlaceId,
            String experienceText,
            boolean isAnonymous,
            boolean isVerifiedOnSite,
            Instant createdAt
    ) {}
}
