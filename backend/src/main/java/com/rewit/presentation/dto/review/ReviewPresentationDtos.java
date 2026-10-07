package com.rewit.presentation.dto.review;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Contratos de DTO representacionais para a API REST de Reviews (Step 11.0 / Step 25.3).
 */
public final class ReviewPresentationDtos {

    private ReviewPresentationDtos() {}

    public record CreateReviewTargetRequest(
            @NotNull(message = "O identificador do alvo avaliado é obrigatório")
            UUID rateableTargetId,

            @NotNull(message = "A nota de avaliação é obrigatória")
            @DecimalMin(value = "1.0", message = "A nota deve ser no mínimo 1.0")
            @DecimalMax(value = "5.0", message = "A nota deve ser no máximo 5.0")
            BigDecimal rating,

            String specificComment
    ) {}

    public record CreateReviewRequest(
            UUID contextPlaceId,
            String experienceText,
            boolean isAnonymous,
            String visibility,

            @DecimalMin(value = "-90.0", message = "Latitude mínima é -90.0")
            @DecimalMax(value = "90.0", message = "Latitude máxima é 90.0")
            Double userLatitude,

            @DecimalMin(value = "-180.0", message = "Longitude mínima é -180.0")
            @DecimalMax(value = "180.0", message = "Longitude máxima é 180.0")
            Double userLongitude,

            @DecimalMin(value = "0.0", message = "A precisão da localização não pode ser negativa")
            Double locationAccuracyMeters,

            @NotEmpty(message = "A publicação deve conter pelo menos um alvo avaliado")
            List<@Valid CreateReviewTargetRequest> targets
    ) {
        public CreateReviewRequest(
                UUID contextPlaceId,
                String experienceText,
                boolean isAnonymous,
                String visibility,
                List<@Valid CreateReviewTargetRequest> targets
        ) {
            this(contextPlaceId, experienceText, isAnonymous, visibility, null, null, null, targets);
        }
    }

    public record UpdateReviewRequest(
            @Size(max = 2000, message = "O texto da avaliação não pode exceder 2000 caracteres")
            String experienceText,

            Map<UUID, @DecimalMin(value = "1.0", message = "A nota deve ser no mínimo 1.0") @DecimalMax(value = "5.0", message = "A nota deve ser no máximo 5.0") BigDecimal> targetRatings,

            Boolean isAnonymous,

            @Pattern(regexp = "^(?i)(PUBLIC|FOLLOWERS|PRIVATE)$", message = "Visibilidade inválida. Valores aceitos: PUBLIC, FOLLOWERS, PRIVATE")
            String visibility
    ) {}

    public record ReviewAuthorResponse(
            UUID id,
            String handle,
            String displayName,
            String avatarUrl,
            boolean isAnonymous
    ) {
        public static ReviewAuthorResponse anonymous() {
            return new ReviewAuthorResponse(null, null, "Anônimo", null, true);
        }

        /** Identidade oculta (avaliação anônima ou conta excluída): nunca id, handle nem avatar, só o nome exibido. */
        public static ReviewAuthorResponse hidden(String displayName) {
            return displayName == null ? anonymous() : new ReviewAuthorResponse(null, null, displayName, null, true);
        }

        public static ReviewAuthorResponse of(UUID id, String handle, String displayName, String avatarUrl) {
            return new ReviewAuthorResponse(id, handle, displayName, avatarUrl, false);
        }
    }

    public record ReviewTargetResponse(
            UUID id,
            UUID targetId,
            BigDecimal rating,
            String specificComment,
            Instant createdAt
    ) {}

    public record ReviewResponse(
            UUID id,
            ReviewAuthorResponse author,
            UUID contextPlaceId,
            String experienceText,
            boolean isAnonymous,
            boolean isVerifiedOnSite,
            String visibility,
            String status,
            Instant createdAt,
            Instant updatedAt,
            List<ReviewTargetResponse> targets,
            long helpfulCount,
            boolean isHelpfulByMe
    ) {
        public ReviewResponse(
                UUID id,
                ReviewAuthorResponse author,
                UUID contextPlaceId,
                String experienceText,
                boolean isAnonymous,
                boolean isVerifiedOnSite,
                String visibility,
                String status,
                Instant createdAt,
                Instant updatedAt,
                List<ReviewTargetResponse> targets
        ) {
            this(id, author, contextPlaceId, experienceText, isAnonymous, isVerifiedOnSite, visibility, status, createdAt, updatedAt, targets, 0L, false);
        }
    }
}
