package com.rewit.application.service;

import com.rewit.domain.model.UserReputation;
import com.rewit.infrastructure.persistence.repository.ReviewJpaRepository;
import com.rewit.infrastructure.persistence.repository.ReviewReactionJpaRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Calculador determinístico de sinais de reputação V1 (Step 23.0).
 *
 * <p><b>Responsabilidade única</b>: calcular sinais a partir dos fatos existentes no banco.
 * NÃO persiste — apenas retorna um {@link UserReputation} com os sinais calculados.
 * A persistência é responsabilidade do {@link ReputationService}.
 *
 * <p><b>Sinais calculados (versão 1)</b>:
 * <ul>
 *   <li>{@code activeReviews}: reviews ACTIVE + is_anonymous = FALSE</li>
 *   <li>{@code verifiedReviews}: reviews ACTIVE + is_anonymous = FALSE + is_verified_on_site = TRUE</li>
 *   <li>{@code helpfulVotesReceived}: Helpful em reviews ACTIVE + is_anonymous = FALSE</li>
 *   <li>{@code distinctTargetsReviewed}: targets distintos em reviews ACTIVE + is_anonymous = FALSE</li>
 * </ul>
 *
 * <p><b>Reviews anônimas</b>: excluídas conforme ADR-008, Seção 3.
 * <p><b>Score numérico</b>: ausente — ADR-008 não define pesos formais.
 * <p><b>Reports</b>: não utilizados como sinal — ADR-008 não especifica threshold/peso.
 * <p><b>N+1</b>: zero — exatamente 3 queries agregadas, independentemente do volume.
 */
@Component
public class ReputationCalculator {

    private final ReviewJpaRepository reviewJpaRepository;
    private final ReviewReactionJpaRepository reactionJpaRepository;

    public ReputationCalculator(
            ReviewJpaRepository reviewJpaRepository,
            ReviewReactionJpaRepository reactionJpaRepository
    ) {
        this.reviewJpaRepository = Objects.requireNonNull(reviewJpaRepository,
            "reviewJpaRepository must not be null");
        this.reactionJpaRepository = Objects.requireNonNull(reactionJpaRepository,
            "reactionJpaRepository must not be null");
    }

    /**
     * Calcula o snapshot de reputação atual para o usuário informado.
     * Executa exatamente 3 queries agregadas no banco (sem N+1).
     *
     * @param userId UUID do usuário a calcular
     * @return snapshot de reputação com {@link UserReputation#CURRENT_VERSION}
     */
    @Transactional(readOnly = true)
    public UserReputation calculate(UUID userId) {
        Objects.requireNonNull(userId, "userId cannot be null");

        // Query 1: activeReviews + verifiedReviews (não-anon)
        long active   = reviewJpaRepository.countActiveNonAnonByUserId(userId);
        long verified = reviewJpaRepository.countActiveNonAnonVerifiedByUserId(userId);

        // Query 2: distinctTargetsReviewed (não-anon)
        long distinctTargets = reviewJpaRepository.countDistinctTargetsByUserIdActiveNonAnon(userId);

        // Query 3: helpfulVotesReceived (não-anon)
        long helpful = reactionJpaRepository.countHelpfulVotesReceivedByUserIdNonAnon(userId);

        return new UserReputation(
            userId,
            UserReputation.CURRENT_VERSION,
            clamp(active),
            clamp(verified),
            clamp(helpful),
            clamp(distinctTargets),
            Instant.now()
        );
    }

    /** Conversão segura de long para int com cap em Integer.MAX_VALUE. */
    private static int clamp(long value) {
        return (int) Math.min(value, Integer.MAX_VALUE);
    }
}
