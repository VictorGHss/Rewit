package com.rewit.infrastructure.persistence.repository;

import com.rewit.infrastructure.persistence.entity.UserReputationJpaEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Repositório Spring Data JPA para snapshots de reputação (user_reputation) - Step 23.0 / Step 23.1.
 * Oferece suporte a lock pessimista para serialização de recálculos concorrentes e inserção idempotente.
 */
public interface UserReputationJpaRepository extends JpaRepository<UserReputationJpaEntity, UUID> {

    /**
     * Garante que uma linha inicial exista para o usuário, inicializando com zeros caso ainda não exista.
     * Idempotente via ON CONFLICT (user_id) DO NOTHING.
     */
    @Modifying
    @Query(value = """
        INSERT INTO user_reputation
            (user_id, version, active_reviews, verified_reviews,
             helpful_votes_received, distinct_targets_reviewed, calculated_at)
        VALUES
            (:userId, 1, 0, 0, 0, 0, NOW())
        ON CONFLICT (user_id) DO NOTHING
    """, nativeQuery = true)
    void insertInitialRowIfNotExists(@Param("userId") UUID userId);

    /**
     * Adquire lock pessimista exclusivo (SELECT ... FOR UPDATE) na linha do usuário.
     * Previne lost updates e corrida em recálculos concorrentes do mesmo usuário.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM UserReputationJpaEntity r WHERE r.userId = :userId")
    Optional<UserReputationJpaEntity> findByUserIdForUpdate(@Param("userId") UUID userId);

    /**
     * INSERT ... ON CONFLICT DO UPDATE para upsert atômico do snapshot.
     * Garante que o userId existente seja sobrescrito sem violar a PK.
     */
    @Modifying
    @Query(value = """
        INSERT INTO user_reputation
            (user_id, version, active_reviews, verified_reviews,
             helpful_votes_received, distinct_targets_reviewed, calculated_at)
        VALUES
            (:userId, :version, :activeReviews, :verifiedReviews,
             :helpfulVotesReceived, :distinctTargetsReviewed, :calculatedAt)
        ON CONFLICT (user_id) DO UPDATE SET
            version                   = EXCLUDED.version,
            active_reviews            = EXCLUDED.active_reviews,
            verified_reviews          = EXCLUDED.verified_reviews,
            helpful_votes_received    = EXCLUDED.helpful_votes_received,
            distinct_targets_reviewed = EXCLUDED.distinct_targets_reviewed,
            calculated_at             = EXCLUDED.calculated_at
    """, nativeQuery = true)
    void upsert(
        @Param("userId") UUID userId,
        @Param("version") int version,
        @Param("activeReviews") int activeReviews,
        @Param("verifiedReviews") int verifiedReviews,
        @Param("helpfulVotesReceived") int helpfulVotesReceived,
        @Param("distinctTargetsReviewed") int distinctTargetsReviewed,
        @Param("calculatedAt") Instant calculatedAt
    );
}
