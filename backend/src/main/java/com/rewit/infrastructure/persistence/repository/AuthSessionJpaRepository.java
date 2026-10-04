package com.rewit.infrastructure.persistence.repository;

import java.time.Instant;
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

    /**
     * Limpa ip_address e user_agent de sessões que deixaram de estar ativas (Step 29.3). Não toca nenhum
     * campo de segurança; sessões ativas (revoked_at nulo e expires_at >= :now) nunca são alcançadas.
     */
    @Modifying(clearAutomatically = true)
    @Query(value = """
        UPDATE auth_sessions
        SET ip_address = NULL,
            user_agent = NULL
        WHERE id IN (
            SELECT id
            FROM auth_sessions
            WHERE (revoked_at IS NOT NULL OR expires_at < :now)
              AND (ip_address IS NOT NULL OR user_agent IS NOT NULL)
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
        )
        """, nativeQuery = true)
    int clearInactiveMetadata(@Param("now") Instant now, @Param("limit") int limit);

    /**
     * Remove sessões expiradas sem sucessora (Step 29.3). Uma antecessora de rotação só fica elegível depois
     * que a sucessora é removida e o ON DELETE SET NULL limpa sua referência, em um lote posterior.
     */
    @Modifying(clearAutomatically = true)
    @Query(value = """
        DELETE FROM auth_sessions
        WHERE id IN (
            SELECT id
            FROM auth_sessions
            WHERE expires_at < :now
              AND replaced_by_session_id IS NULL
            ORDER BY expires_at, id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
        )
        """, nativeQuery = true)
    int purgeExpiredWithoutSuccessor(@Param("now") Instant now, @Param("limit") int limit);
}
