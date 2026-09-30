package com.rewit.infrastructure.persistence.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.rewit.infrastructure.persistence.entity.AuthSessionJpaEntity;

import jakarta.persistence.LockModeType;

/**
 * Repositório Spring Data JPA para a entidade AuthSessionJpaEntity.
 */
public interface AuthSessionJpaRepository extends JpaRepository<AuthSessionJpaEntity, UUID> {

    Optional<AuthSessionJpaEntity> findByTokenHash(String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM AuthSessionJpaEntity s WHERE s.tokenHash = :tokenHash")
    Optional<AuthSessionJpaEntity> findByTokenHashWithLock(@Param("tokenHash") String tokenHash);

    @Modifying
    @Query("UPDATE AuthSessionJpaEntity s SET s.revokedAt = CURRENT_TIMESTAMP WHERE s.user.id = :userId AND s.revokedAt IS NULL")
    void revokeAllActiveByUserId(@Param("userId") UUID userId);
}
