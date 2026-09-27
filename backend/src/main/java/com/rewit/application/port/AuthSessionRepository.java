package com.rewit.application.port;

import com.rewit.domain.model.AuthSession;

import java.util.Optional;
import java.util.UUID;

/**
 * Porta de saída da aplicação para persistência e recuperação de sessões de autenticação.
 */
public interface AuthSessionRepository {

    AuthSession save(AuthSession session);

    Optional<AuthSession> findById(UUID id);

    Optional<AuthSession> findByTokenHash(String tokenHash);

    void revokeAllByUserId(UUID userId);
}
