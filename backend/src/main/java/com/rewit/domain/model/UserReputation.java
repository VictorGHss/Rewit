package com.rewit.domain.model;

import com.rewit.common.exception.BusinessException;

import java.time.Instant;
import java.util.UUID;

/**
 * Entidade de domínio representando o snapshot de Reputação V1 de um usuário.
 *
 * <p><b>Natureza do snapshot</b>: este objeto é um valor derivado — NÃO é source of truth.
 * Os fatos que originam os sinais continuam em {@code reviews}, {@code review_reactions},
 * {@code user_follows} e {@code review_targets}.
 *
 * <p><b>Score numérico</b>: deliberadamente ausente neste MVP.
 * O ADR-008 não especifica fórmula/pesos formais. Um score arbitrário
 * sem especificação formal seria enganoso e contrário à decisão arquitetural.
 *
 * <p><b>Reviews anônimas</b>: excluídas dos sinais públicos conforme ADR-008, Seção 3.
 * O cálculo usa {@code is_anonymous = FALSE} no filtro.
 *
 * <p><b>Versão da regra</b>: {@code version = 1}. Ao mudar sinais/critérios, incrementar.
 */
public class UserReputation {

    /** Versão atual das regras de cálculo de reputação. */
    public static final int CURRENT_VERSION = 1;

    private final UUID userId;
    private final int version;
    private final int activeReviews;
    private final int verifiedReviews;
    private final int helpfulVotesReceived;
    private final int distinctTargetsReviewed;
    private final Instant calculatedAt;

    /**
     * Construtor de uso interno da camada de domínio.
     * Validações garantem invariantes mesmo em testes unitários diretos.
     */
    public UserReputation(
            UUID userId,
            int version,
            int activeReviews,
            int verifiedReviews,
            int helpfulVotesReceived,
            int distinctTargetsReviewed,
            Instant calculatedAt
    ) {
        if (userId == null) {
            throw new BusinessException("userId é obrigatório para UserReputation", "MISSING_USER_ID");
        }
        if (version < 1) {
            throw new BusinessException("version deve ser >= 1", "INVALID_REPUTATION_VERSION");
        }
        if (activeReviews < 0) {
            throw new BusinessException("activeReviews nao pode ser negativo", "INVALID_REPUTATION_SIGNAL");
        }
        if (verifiedReviews < 0) {
            throw new BusinessException("verifiedReviews nao pode ser negativo", "INVALID_REPUTATION_SIGNAL");
        }
        if (verifiedReviews > activeReviews) {
            throw new BusinessException("verifiedReviews nao pode exceder activeReviews", "INVALID_REPUTATION_SIGNAL");
        }
        if (helpfulVotesReceived < 0) {
            throw new BusinessException("helpfulVotesReceived nao pode ser negativo", "INVALID_REPUTATION_SIGNAL");
        }
        if (distinctTargetsReviewed < 0) {
            throw new BusinessException("distinctTargetsReviewed nao pode ser negativo", "INVALID_REPUTATION_SIGNAL");
        }
        this.userId = userId;
        this.version = version;
        this.activeReviews = activeReviews;
        this.verifiedReviews = verifiedReviews;
        this.helpfulVotesReceived = helpfulVotesReceived;
        this.distinctTargetsReviewed = distinctTargetsReviewed;
        this.calculatedAt = calculatedAt != null ? calculatedAt : Instant.now();
    }

    public UUID getUserId() { return userId; }
    public int getVersion() { return version; }
    public int getActiveReviews() { return activeReviews; }
    public int getVerifiedReviews() { return verifiedReviews; }
    public int getHelpfulVotesReceived() { return helpfulVotesReceived; }
    public int getDistinctTargetsReviewed() { return distinctTargetsReviewed; }
    public Instant getCalculatedAt() { return calculatedAt; }
}
