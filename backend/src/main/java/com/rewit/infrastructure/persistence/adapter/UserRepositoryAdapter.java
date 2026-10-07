package com.rewit.infrastructure.persistence.adapter;

import com.rewit.application.port.UserRepository;
import com.rewit.domain.enums.AuthProvider;
import com.rewit.domain.model.User;
import com.rewit.infrastructure.persistence.entity.UserJpaEntity;
import com.rewit.infrastructure.persistence.repository.UserJpaRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Adaptador de persistência que implementa a porta UserRepository.
 * Converte chamadas do domínio/aplicação para o repositório JPA e isola entidades JPA.
 */
@Component
public class UserRepositoryAdapter implements UserRepository {

    private final UserJpaRepository userJpaRepository;
    private final EntityManager entityManager;

    public UserRepositoryAdapter(UserJpaRepository userJpaRepository, EntityManager entityManager) {
        this.userJpaRepository = Objects.requireNonNull(userJpaRepository, "userJpaRepository must not be null");
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager must not be null");
    }

    @Override
    public User save(User user) {
        Objects.requireNonNull(user, "User cannot be null");
        return userJpaRepository.findById(user.getId())
                .map(existingEntity -> {
                    existingEntity.updateFromDomain(user);
                    UserJpaEntity saved = userJpaRepository.saveAndFlush(existingEntity);
                    return Objects.requireNonNull(saved, "Saved UserJpaEntity cannot be null").toDomain();
                })
                .orElseGet(() -> {
                    UserJpaEntity newEntity = UserJpaEntity.fromDomain(user);
                    UserJpaEntity saved = userJpaRepository.saveAndFlush(newEntity);
                    return Objects.requireNonNull(saved, "Saved UserJpaEntity cannot be null").toDomain();
                });
    }

    @Override
    public Optional<User> findById(UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        return userJpaRepository.findActiveById(id).map(UserRepositoryAdapter::toDomain);
    }

    @Override
    public Optional<User> findByIdIncludingDeleted(UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        return userJpaRepository.findById(id).map(UserRepositoryAdapter::toDomain);
    }

    @Override
    public Optional<User> findByIdForUpdate(UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        UserJpaEntity entity = entityManager.find(UserJpaEntity.class, id);
        if (entity == null) {
            return Optional.empty();
        }
        // refresh com lock, e não uma consulta com lock: se a conta já foi carregada nesta transação (ex.: busca por
        // e-mail na reativação), uma consulta travaria a linha mas devolveria a instância gerenciada com o estado
        // antigo. O refresh trava (FOR NO KEY UPDATE) e recarrega o estado confirmado
        try {
            entityManager.refresh(entity, LockModeType.PESSIMISTIC_WRITE);
        } catch (EntityNotFoundException removedMeanwhile) {
            return Optional.empty();
        }
        return Optional.of(entity.toDomain());
    }

    @Override
    public boolean existsById(UUID id) {
        if (id == null) {
            return false;
        }
        return userJpaRepository.existsById(id);
    }

    @Override
    public Optional<User> findByEmail(String email) {
        if (email == null || email.isBlank()) {
            return Optional.empty();
        }
        String normalizedEmail = email.trim().toLowerCase();
        return userJpaRepository.findActiveByEmail(normalizedEmail).map(UserRepositoryAdapter::toDomain);
    }

    @Override
    public boolean existsByEmail(String email) {
        if (email == null || email.isBlank()) {
            return false;
        }
        String normalizedEmail = email.trim().toLowerCase();
        return userJpaRepository.existsByEmailIgnoreCase(normalizedEmail);
    }

    @Override
    public Optional<User> findByAuthProviderAndProviderUserId(AuthProvider authProvider, String providerUserId) {
        if (authProvider == null || providerUserId == null || providerUserId.isBlank()) {
            return Optional.empty();
        }
        return userJpaRepository.findActiveByAuthProviderAndProviderUserId(authProvider, providerUserId.trim())
                .map(UserRepositoryAdapter::toDomain);
    }

    private static User toDomain(UserJpaEntity entity) {
        Objects.requireNonNull(entity, "UserJpaEntity must not be null");
        return entity.toDomain();
    }
}
