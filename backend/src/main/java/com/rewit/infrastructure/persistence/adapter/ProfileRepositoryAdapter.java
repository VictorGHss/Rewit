package com.rewit.infrastructure.persistence.adapter;

import com.rewit.application.port.ProfileRepository;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.model.Profile;
import com.rewit.infrastructure.persistence.entity.ProfileJpaEntity;
import com.rewit.infrastructure.persistence.entity.UserJpaEntity;
import com.rewit.infrastructure.persistence.repository.ProfileJpaRepository;
import com.rewit.infrastructure.persistence.repository.UserJpaRepository;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Adaptador de persistência que implementa a porta ProfileRepository.
 * Garante que perfis nunca sejam criados como órfãos e desacopla a aplicação de entidades JPA.
 */
@Component
public class ProfileRepositoryAdapter implements ProfileRepository {

    private final ProfileJpaRepository profileJpaRepository;
    private final UserJpaRepository userJpaRepository;

    public ProfileRepositoryAdapter(ProfileJpaRepository profileJpaRepository, UserJpaRepository userJpaRepository) {
        this.profileJpaRepository = Objects.requireNonNull(profileJpaRepository, "profileJpaRepository must not be null");
        this.userJpaRepository = Objects.requireNonNull(userJpaRepository, "userJpaRepository must not be null");
    }

    @Override
    public Profile save(Profile profile) {
        Objects.requireNonNull(profile, "Profile cannot be null");
        UUID userId = profile.getUserId();
        if (userId == null) {
            throw new BusinessException("O identificador do usuário é obrigatório para persistir o perfil", "MISSING_USER_ID");
        }

        UserJpaEntity userEntity = userJpaRepository.findById(userId)
                .orElseThrow(() -> new BusinessException("Usuário não encontrado para vincular ao perfil: " + userId, "USER_NOT_FOUND"));

        return profileJpaRepository.findById(profile.getId())
                .map(existingEntity -> {
                    existingEntity.setUser(userEntity);
                    existingEntity.updateFromDomain(profile);
                    return profileJpaRepository.saveAndFlush(existingEntity).toDomain();
                })
                .orElseGet(() -> {
                    ProfileJpaEntity newEntity = ProfileJpaEntity.fromDomain(profile, userEntity);
                    return profileJpaRepository.saveAndFlush(newEntity).toDomain();
                });
    }

    @Override
    public Optional<Profile> findById(UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        return profileJpaRepository.findById(id)
                .map(ProfileJpaEntity::toDomain);
    }

    @Override
    public Optional<Profile> findByUserId(UUID userId) {
        if (userId == null) {
            return Optional.empty();
        }
        return profileJpaRepository.findByUserId(userId)
                .map(ProfileJpaEntity::toDomain);
    }

    @Override
    public Optional<Profile> findByHandle(String handle) {
        if (handle == null || handle.isBlank()) {
            return Optional.empty();
        }
        String normalizedHandle = Profile.normalizeHandle(handle);
        return profileJpaRepository.findByHandleIgnoreCase(normalizedHandle)
                .map(ProfileJpaEntity::toDomain);
    }

    @Override
    public boolean existsByHandle(String handle) {
        if (handle == null || handle.isBlank()) {
            return false;
        }
        String normalizedHandle = Profile.normalizeHandle(handle);
        return profileJpaRepository.existsByHandleIgnoreCase(normalizedHandle);
    }

    @Override
    public boolean existsByUserId(UUID userId) {
        if (userId == null) {
            return false;
        }
        return profileJpaRepository.existsByUserId(userId);
    }
}
