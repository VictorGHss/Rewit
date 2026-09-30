package com.rewit.presentation.dto.reputation;

import com.rewit.application.dto.reputation.ReputationDtos;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

/**
 * Response HTTP para o endpoint GET /api/v1/users/{userId}/reputation (Step 23.0).
 *
 * <p>Não expõe score numérico, PII, localização, reports ou reviews individuais.
 */
public record ReputationResponse(
    @JsonProperty("userId") UUID userId,
    @JsonProperty("version") int version,
    @JsonProperty("signals") SignalsResponse signals,
    @JsonProperty("calculatedAt") Instant calculatedAt
) {
    public static ReputationResponse from(ReputationDtos.ReputationView view) {
        return new ReputationResponse(
            view.userId(),
            view.version(),
            new SignalsResponse(
                view.signals().activeReviews(),
                view.signals().verifiedReviews(),
                view.signals().helpfulVotesReceived(),
                view.signals().distinctTargetsReviewed()
            ),
            view.calculatedAt()
        );
    }

    /**
     * Sinais determinísticos de reputação na representação HTTP.
     * Reviews anônimas excluídas (ADR-008, Seção 3).
     */
    public record SignalsResponse(
        @JsonProperty("activeReviews") int activeReviews,
        @JsonProperty("verifiedReviews") int verifiedReviews,
        @JsonProperty("helpfulVotesReceived") int helpfulVotesReceived,
        @JsonProperty("distinctTargetsReviewed") int distinctTargetsReviewed
    ) {}
}
