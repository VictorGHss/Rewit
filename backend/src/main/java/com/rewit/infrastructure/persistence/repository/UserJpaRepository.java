package com.rewit.infrastructure.persistence.repository;

import com.rewit.domain.enums.AuthProvider;
import com.rewit.infrastructure.persistence.entity.UserJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repositório Spring Data JPA para a entidade UserJpaEntity.
 */
@Repository
public interface UserJpaRepository extends JpaRepository<UserJpaEntity, UUID> {

    @Query("SELECT u FROM UserJpaEntity u WHERE u.id = :id AND u.deletedAt IS NULL")
    Optional<UserJpaEntity> findActiveById(@Param("id") UUID id);

    @Query("SELECT u FROM UserJpaEntity u WHERE LOWER(u.email) = LOWER(:email) AND u.deletedAt IS NULL")
    Optional<UserJpaEntity> findActiveByEmail(@Param("email") String email);

    @Query("SELECT CASE WHEN COUNT(u) > 0 THEN true ELSE false END FROM UserJpaEntity u WHERE LOWER(u.email) = LOWER(:email)")
    boolean existsByEmailIgnoreCase(@Param("email") String email);

    @Query("SELECT u FROM UserJpaEntity u WHERE u.authProvider = :provider AND u.providerUserId = :providerUserId AND u.deletedAt IS NULL")
    Optional<UserJpaEntity> findActiveByAuthProviderAndProviderUserId(
            @Param("provider") AuthProvider provider,
            @Param("providerUserId") String providerUserId
    );
}
