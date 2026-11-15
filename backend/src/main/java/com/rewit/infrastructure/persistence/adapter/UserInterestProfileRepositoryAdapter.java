package com.rewit.infrastructure.persistence.adapter;

import com.rewit.application.port.UserInterestProfileRepository;
import com.rewit.domain.enums.InterestCategory;
import com.rewit.domain.model.UserInterestProfile;
import com.rewit.infrastructure.persistence.entity.UserInterestProfileJpaEntity;
import com.rewit.infrastructure.persistence.repository.UserInterestProfileJpaRepository;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Persistência dos interesses do usuário (V1 {@code user_interests}, V40 {@code user_interest_profiles}).
 *
 * <p>A linha de {@code user_interest_profiles} é a trava por usuário: criada por {@code INSERT ... ON CONFLICT DO
 * NOTHING} (a segunda transação concorrente espera a primeira e não falha por chave duplicada) e lida com
 * {@code FOR NO KEY UPDATE}. As seleções são lidas e alteradas por SQL nativo, sem entidades gerenciadas, como nas
 * comodidades; a gravação aplica só a diferença, preservando {@code created_at} dos interesses mantidos.
 */
@Component
public class UserInterestProfileRepositoryAdapter implements UserInterestProfileRepository {

    private final UserInterestProfileJpaRepository profileRepository;
    private final EntityManager entityManager;

    public UserInterestProfileRepositoryAdapter(UserInterestProfileJpaRepository profileRepository,
                                                EntityManager entityManager) {
        this.profileRepository = Objects.requireNonNull(profileRepository,
                "UserInterestProfileJpaRepository must not be null");
        this.entityManager = Objects.requireNonNull(entityManager, "EntityManager must not be null");
    }

    @Override
    public Optional<UserInterestProfile> findByUserId(UUID userId) {
        if (userId == null) {
            return Optional.empty();
        }
        return profileRepository.findById(userId).map(entity -> toDomain(entity, findSelectedInterests(userId)));
    }

    @Override
    public UserInterestProfile findOrCreateForUpdate(UUID userId, Instant now) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(now, "now must not be null");
        entityManager.createNativeQuery("""
                        INSERT INTO user_interest_profiles (user_id, created_at, updated_at)
                        VALUES (:userId, :now, :now)
                        ON CONFLICT (user_id) DO NOTHING
                        """)
                .setParameter("userId", userId)
                .setParameter("now", now)
                .executeUpdate();
        UserInterestProfileJpaEntity entity = profileRepository.findByUserIdForUpdate(userId)
                .orElseThrow(() -> new IllegalStateException("Perfil de interesses ausente após a criação"));
        return toDomain(entity, findSelectedInterests(userId));
    }

    @Override
    public UserInterestProfile save(UserInterestProfile profile) {
        Objects.requireNonNull(profile, "profile must not be null");
        UUID userId = profile.getUserId();

        UserInterestProfileJpaEntity entity = profileRepository.findById(userId)
                .orElseThrow(() -> new IllegalStateException("Perfil de interesses não travado nesta transação"));
        entity.setOnboardingCompletedAt(profile.getOnboardingCompletedAt());
        entity.setUpdatedAt(profile.getUpdatedAt());
        UserInterestProfileJpaEntity saved = profileRepository.saveAndFlush(entity);

        Set<InterestCategory> stored = findSelectedInterests(userId);
        Set<InterestCategory> removed = EnumSet.noneOf(InterestCategory.class);
        removed.addAll(stored);
        removed.removeAll(profile.getInterests());
        if (!removed.isEmpty()) {
            entityManager.createNativeQuery(
                            "DELETE FROM user_interests WHERE user_id = :userId AND category_code IN (:codes)")
                    .setParameter("userId", userId)
                    .setParameter("codes", removed.stream().map(InterestCategory::getCode).toList())
                    .executeUpdate();
        }
        for (InterestCategory interest : profile.getInterests()) {
            if (stored.contains(interest)) {
                continue;
            }
            // Peso 1.00: seleção explícita do usuário (o default da coluna, gravado de forma explícita)
            entityManager.createNativeQuery("""
                            INSERT INTO user_interests (user_id, interest_name, category_code, weight, created_at)
                            VALUES (:userId, :name, :code, 1.00, :now)
                            """)
                    .setParameter("userId", userId)
                    .setParameter("name", interest.getLabel())
                    .setParameter("code", interest.getCode())
                    .setParameter("now", profile.getUpdatedAt())
                    .executeUpdate();
        }

        return toDomain(saved, profile.getInterests());
    }

    private Set<InterestCategory> findSelectedInterests(UUID userId) {
        List<?> codes = entityManager.createNativeQuery(
                        "SELECT category_code FROM user_interests WHERE user_id = :userId")
                .setParameter("userId", userId)
                .getResultList();
        Set<InterestCategory> interests = EnumSet.noneOf(InterestCategory.class);
        for (Object code : codes) {
            interests.add(InterestCategory.valueOf((String) code));
        }
        return interests;
    }

    private static UserInterestProfile toDomain(UserInterestProfileJpaEntity entity,
                                                Collection<InterestCategory> interests) {
        return new UserInterestProfile(entity.getUserId(), interests, entity.getOnboardingCompletedAt(),
                entity.getCreatedAt(), entity.getUpdatedAt());
    }
}
