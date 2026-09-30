package com.rewit.application.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Projeção pública compartilhada para reviews em query results.
 * Não depende de JPA e não inclui qualquer dado sensível. 
 */
public record ReviewProjection(
        UUID id,
        UUID authorUserId,
        UUID contextPlaceId,
        String experienceText,
        boolean isAnonymous,
        boolean isVerifiedOnSite,
        String visibility,
        String status,
        Instant createdAt,
        Instant updatedAt,
        List<ReviewTargetProjection> targets,
        long helpfulCount,
        boolean isHelpfulByMe
) {
    public record ReviewTargetProjection(
            UUID id,
            UUID targetId,
            BigDecimal rating,
            String specificComment,
            Instant createdAt
    ) {}
}
