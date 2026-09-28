package com.rewit.application.dto;

import com.rewit.domain.enums.TargetType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
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

    public record CreateReviewTargetCommand(
            UUID rateableTargetId,
            BigDecimal rating,
            String specificComment
    ) {}

    public record CreateReviewCommand(
            UUID authorUserId,
            UUID contextPlaceId,
            String experienceText,
            boolean isAnonymous,
            String visibility,
            List<CreateReviewTargetCommand> targets
    ) {}

    public record ReviewTargetView(
            UUID id,
            UUID reviewId,
            UUID targetId,
            BigDecimal rating,
            String specificComment,
            Instant createdAt
    ) {}

    public record ReviewDetailView(
            UUID id,
            UUID userId,
            UUID contextPlaceId,
            String experienceText,
            boolean isAnonymous,
            boolean isVerifiedOnSite,
            String status,
            String visibility,
            Instant createdAt,
            Instant updatedAt,
            List<ReviewTargetView> targets
    ) {}

    public record PublicAuthorView(
            UUID id,
            String handle,
            String displayName,
            String avatarUrl,
            boolean isAnonymous
    ) {
        public static PublicAuthorView anonymous() {
            return new PublicAuthorView(null, null, "Anônimo", null, true);
        }
    }

    public record ReviewPublicView(
            UUID id,
            PublicAuthorView author,
            UUID contextPlaceId,
            String experienceText,
            boolean isAnonymous,
            String visibility,
            String status,
            Instant createdAt,
            Instant updatedAt,
            List<ReviewTargetView> targets
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
