package com.rewit.infrastructure.persistence.adapter;

import com.rewit.application.port.UserRepository;
import com.rewit.domain.enums.AuthProvider;
import com.rewit.domain.model.User;
import com.rewit.infrastructure.persistence.entity.UserJpaEntity;
import com.rewit.infrastructure.persistence.repository.UserJpaRepository;
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

    public UserRepositoryAdapter(UserJpaRepository userJpaRepository) {
        this.userJpaRepository = Objects.requireNonNull(userJpaRepository, "userJpaRepository must not be null");
    }

    @Override
    public User save(User user) {
        Objects.requireNonNull(user, "User cannot be null");
        return userJpaRepository.findById(user.getId())
                .map(existingEntity -> {
                    existingEntity.updateFromDomain(user);
                    return userJpaRepository.saveAndFlush(existingEntity).toDomain();
                })
                .orElseGet(() -> {
                    UserJpaEntity newEntity = UserJpaEntity.fromDomain(user);
                    return userJpaRepository.saveAndFlush(newEntity).toDomain();
                });
    }

    @Override
    public Optional<User> findById(UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        return userJpaRepository.findActiveById(id)
                .map(UserJpaEntity::toDomain);
    }

    @Override
    public Optional<User> findByEmail(String email) {
        if (email == null || email.isBlank()) {
            return Optional.empty();
        }
        String normalizedEmail = email.trim().toLowerCase();
        return userJpaRepository.findActiveByEmail(normalizedEmail)
                .map(UserJpaEntity::toDomain);
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
                .map(UserJpaEntity::toDomain);
    }
}
