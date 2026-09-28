package com.rewit.infrastructure.persistence.adapter;

import com.rewit.application.port.AuthSessionRepository;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.model.AuthSession;
import com.rewit.infrastructure.persistence.entity.AuthSessionJpaEntity;
import com.rewit.infrastructure.persistence.entity.UserJpaEntity;
import com.rewit.infrastructure.persistence.repository.AuthSessionJpaRepository;
import com.rewit.infrastructure.persistence.repository.UserJpaRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Adaptador de persistência que implementa a porta AuthSessionRepository.
 */
@Component
public class AuthSessionRepositoryAdapter implements AuthSessionRepository {

    private final AuthSessionJpaRepository authSessionJpaRepository;
    private final UserJpaRepository userJpaRepository;

    public AuthSessionRepositoryAdapter(AuthSessionJpaRepository authSessionJpaRepository, UserJpaRepository userJpaRepository) {
        this.authSessionJpaRepository = Objects.requireNonNull(authSessionJpaRepository, "authSessionJpaRepository must not be null");
        this.userJpaRepository = Objects.requireNonNull(userJpaRepository, "userJpaRepository must not be null");
    }

    @Override
    public AuthSession save(AuthSession session) {
        Objects.requireNonNull(session, "AuthSession cannot be null");
        UUID userId = session.getUserId();

        UserJpaEntity userEntity = userJpaRepository.findById(userId)
                .orElseThrow(() -> new BusinessException("Usuário não encontrado para a sessão: " + userId, "USER_NOT_FOUND"));

        return authSessionJpaRepository.findById(session.getId())
                .map(existing -> {
                    existing.updateFromDomain(session);
                    AuthSessionJpaEntity saved = authSessionJpaRepository.saveAndFlush(existing);
                    return Objects.requireNonNull(saved, "Saved AuthSessionJpaEntity cannot be null").toDomain();
                })
                .orElseGet(() -> {
                    AuthSessionJpaEntity newEntity = AuthSessionJpaEntity.fromDomain(session, userEntity);
                    AuthSessionJpaEntity saved = authSessionJpaRepository.saveAndFlush(newEntity);
                    return Objects.requireNonNull(saved, "Saved AuthSessionJpaEntity cannot be null").toDomain();
                });
    }

    @Override
    public Optional<AuthSession> findById(UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        return authSessionJpaRepository.findById(id).map(AuthSessionRepositoryAdapter::toDomain);
    }

    @Override
    public Optional<AuthSession> findByTokenHash(String tokenHash) {
        if (tokenHash == null || tokenHash.isBlank()) {
            return Optional.empty();
        }
        return authSessionJpaRepository.findByTokenHash(tokenHash.trim()).map(AuthSessionRepositoryAdapter::toDomain);
    }

    @Override
    public Optional<AuthSession> findByTokenHashForUpdate(String tokenHash) {
        if (tokenHash == null || tokenHash.isBlank()) {
            return Optional.empty();
        }
        return authSessionJpaRepository.findByTokenHashWithLock(tokenHash.trim()).map(AuthSessionRepositoryAdapter::toDomain);
    }

    @Override
    @Transactional
    public void revokeAllByUserId(UUID userId) {
        if (userId != null) {
            authSessionJpaRepository.revokeAllActiveByUserId(userId);
        }
    }

    private static AuthSession toDomain(AuthSessionJpaEntity entity) {
        Objects.requireNonNull(entity, "AuthSessionJpaEntity must not be null");
        return entity.toDomain();
    }
}
