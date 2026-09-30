package com.rewit.application.dto.reputation;

import com.rewit.domain.model.UserReputation;

import java.time.Instant;
import java.util.UUID;

/**
 * DTOs de aplicação para o subsistema de Reputação V1 (Step 23.0).
 */
public final class ReputationDtos {

    private ReputationDtos() {}

    /**
     * View pública de reputação. Não expõe score numérico (ADR-008 não define pesos).
     * Não expõe PII, localização, reports, reviews individuais ou reviews anônimas.
     */
    public record ReputationView(
        UUID userId,
        int version,
        SignalsView signals,
        Instant calculatedAt
    ) {
        public static ReputationView fromDomain(UserReputation reputation) {
            return new ReputationView(
                reputation.getUserId(),
                reputation.getVersion(),
                new SignalsView(
                    reputation.getActiveReviews(),
                    reputation.getVerifiedReviews(),
                    reputation.getHelpfulVotesReceived(),
                    reputation.getDistinctTargetsReviewed()
                ),
                reputation.getCalculatedAt()
            );
        }
    }

    /**
     * Sinais determinísticos de reputação V1.
     * Reviews anônimas excluídas de todos os sinais (ADR-008, Seção 3).
     */
    public record SignalsView(
        int activeReviews,
        int verifiedReviews,
        int helpfulVotesReceived,
        int distinctTargetsReviewed
    ) {}
}
