package com.rewit.presentation.dto.review;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Contratos de DTO representacionais para a API REST de Reviews (Step 11.0).
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

            @NotEmpty(message = "A publicação deve conter pelo menos um alvo avaliado")
            List<@Valid CreateReviewTargetRequest> targets
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
            String visibility,
            String status,
            Instant createdAt,
            Instant updatedAt,
            List<ReviewTargetResponse> targets
    ) {}
}
