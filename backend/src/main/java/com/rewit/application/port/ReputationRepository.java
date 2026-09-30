package com.rewit.application.port;

import com.rewit.domain.model.UserReputation;

import java.util.Optional;
import java.util.UUID;

/**
 * Porta de persistência para snapshots de reputação de usuários (Step 23.0 / Step 23.1).
 *
 * <p>O snapshot é derivado — o source of truth continua nas tabelas de fatos
 * ({@code reviews}, {@code review_reactions}, {@code review_targets}).
 */
public interface ReputationRepository {

    /**
     * Persiste ou atualiza o snapshot de reputação do usuário.
     * Implementação usa INSERT ... ON CONFLICT DO UPDATE.
     */
    UserReputation save(UserReputation reputation);

    /**
     * Retorna o snapshot mais recente de reputação do usuário, se existir.
     */
    Optional<UserReputation> findByUserId(UUID userId);

    /**
     * Garante que uma linha de snapshot exista previamente para o usuário,
     * inicializando com valores padrão de forma atômica e idempotente.
     */
    void insertInitialRowIfNotExists(UUID userId);

    /**
     * Adquire lock pessimista exclusivo (SELECT ... FOR UPDATE) na linha do usuário.
     * Serializa recálculos concorrentes do mesmo usuário.
     */
    Optional<UserReputation> findByUserIdForUpdate(UUID userId);
}
