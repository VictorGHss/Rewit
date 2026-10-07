package com.rewit.application.dto;

import com.rewit.domain.enums.TargetType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
            Double userLatitude,
            Double userLongitude,
            Double locationAccuracyMeters,
            List<CreateReviewTargetCommand> targets
    ) {
        public CreateReviewCommand(
                UUID authorUserId,
                UUID contextPlaceId,
                String experienceText,
                boolean isAnonymous,
                String visibility,
                List<CreateReviewTargetCommand> targets
        ) {
            this(authorUserId, contextPlaceId, experienceText, isAnonymous, visibility, null, null, null, targets);
        }
    }

    public record UpdateReviewCommand(
            String experienceText,
            Map<UUID, BigDecimal> targetRatings,
            Boolean isAnonymous,
            String visibility,
            Instant now
    ) {
        public UpdateReviewCommand {
            if (targetRatings == null) {
                targetRatings = Map.of();
            }
        }
    }

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
        /** Nome exibido no lugar do autor de uma conta excluída, em toda leitura pública. */
        public static final String DELETED_DISPLAY_NAME = "Usuário excluído";

        public static PublicAuthorView anonymous() {
            return new PublicAuthorView(null, null, "Anônimo", null, true);
        }

        /**
         * Autor de uma conta {@code DELETED} (C2): o conteúdo continua, a identidade não. Mesma forma do anônimo
         * (sem id, handle, avatar nem link de perfil), com um nome que não se confunde com uma avaliação anônima.
         */
        public static PublicAuthorView deleted() {
            return new PublicAuthorView(null, null, DELETED_DISPLAY_NAME, null, true);
        }
    }

    public record ReviewPublicView(
            UUID id,
            PublicAuthorView author,
            UUID contextPlaceId,
            String experienceText,
            boolean isAnonymous,
            boolean isVerifiedOnSite,
            String visibility,
            String status,
            Instant createdAt,
            Instant updatedAt,
            List<ReviewTargetView> targets,
            long helpfulCount,
            boolean isHelpfulByMe
    ) {
        public ReviewPublicView(
                UUID id,
                PublicAuthorView author,
                UUID contextPlaceId,
                String experienceText,
                boolean isAnonymous,
                boolean isVerifiedOnSite,
                String visibility,
                String status,
                Instant createdAt,
                Instant updatedAt,
                List<ReviewTargetView> targets
        ) {
            this(id, author, contextPlaceId, experienceText, isAnonymous, isVerifiedOnSite, visibility, status, createdAt, updatedAt, targets, 0L, false);
        }
    }

    public record ReviewView(
            UUID id,
            UUID userId,
            UUID contextPlaceId,
            String experienceText,
            boolean isAnonymous,
            boolean isVerifiedOnSite,
            Instant createdAt
    ) {}

    public record TargetStatsView(
            UUID targetId,
            BigDecimal averageRating,
            int reviewsCount,
            Instant lastCalculatedAt
    ) {}
}
