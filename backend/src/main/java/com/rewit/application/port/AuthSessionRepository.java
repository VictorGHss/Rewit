package com.rewit.application.port;

import com.rewit.domain.model.AuthSession;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Porta de saída da aplicação para persistência e recuperação de sessões de autenticação.
 */
public interface AuthSessionRepository {

    AuthSession save(AuthSession session);

    Optional<AuthSession> findById(UUID id);

    Optional<AuthSession> findByTokenHash(String tokenHash);

    Optional<AuthSession> findByTokenHashForUpdate(String tokenHash);

    void revokeAllByUserId(UUID userId);

    /**
     * Limpa os metadados técnicos (IP e user agent) de até {@code limit} sessões inativas
     * ({@code revoked_at} preenchido ou {@code expires_at < now}), numa transação própria.
     *
     * @return quantidade de sessões alteradas
     */
    int clearInactiveMetadata(Instant now, int limit);

    /**
     * Remove até {@code limit} sessões com {@code expires_at < now} e sem sucessora de rotação,
     * numa transação própria. Sessões ativas e antecessoras de cadeias vivas nunca são removidas.
     *
     * @return quantidade de sessões removidas
     */
    int purgeExpiredWithoutSuccessor(Instant now, int limit);
}
