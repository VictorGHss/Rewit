package com.rewit.application.port;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Porta de aplicação para persistência e consulta do subsistema de validação de utilidade (Helpful) em avaliações (Step 16.0).
 */
public interface ReviewReactionRepository {

    /**
     * Registra o voto de Helpful de forma atômica e idempotente.
     * Retorna true se a marcação foi inserida agora, ou false se já existia previamente.
     */
    boolean addHelpful(UUID reviewId, UUID userId);

    /**
     * Remove o voto de Helpful de forma idempotente.
     * Retorna true se a marcação foi removida, ou false se não existia previamente.
     */
    boolean removeHelpful(UUID reviewId, UUID userId);

    /**
     * Verifica se o usuário marcou a avaliação como Helpful.
     */
    boolean isHelpful(UUID reviewId, UUID userId);

    /**
     * Retorna a quantidade total de marcações de Helpful da avaliação.
     * Considera estritamente registros com reaction_type = 'HELPFUL'.
     */
    long countHelpful(UUID reviewId);

    /**
     * Retorna contagens de Helpful em lote para um conjunto de avaliações (prevenção de N+1).
     */
    Map<UUID, Long> countHelpfulByReviewIds(Collection<UUID> reviewIds);

    /**
     * Retorna o conjunto de IDs de avaliações marcadas como Helpful pelo usuário especificado dentre as informadas (prevenção de N+1).
     */
    Set<UUID> findHelpfulReviewIdsByUser(Collection<UUID> reviewIds, UUID userId);

    /**
     * Retorna a quantidade total de votos de Helpful recebidos por todas as avaliações ativas do usuário.
     */
    long countHelpfulVotesReceivedByUserId(UUID userId);
}
