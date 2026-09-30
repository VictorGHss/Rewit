package com.rewit.infrastructure.persistence.repository;

import com.rewit.infrastructure.persistence.entity.RateableTargetStatsJpaEntity;
import jakarta.persistence.LockModeType;
import jakarta.persistence.Tuple;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * Repositório Spring Data JPA para rateable_target_stats com suporte a lock pessimista e agregação atômica.
 */
public interface RateableTargetStatsJpaRepository extends JpaRepository<RateableTargetStatsJpaEntity, UUID> {

    @Modifying
    @Query(value = """
        INSERT INTO rateable_target_stats (target_id, average_rating, reviews_count, last_calculated_at)
        VALUES (:targetId, 0.00, 0, NOW())
        ON CONFLICT (target_id) DO NOTHING
    """, nativeQuery = true)
    void insertInitialRowIfNotExists(@Param("targetId") UUID targetId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM RateableTargetStatsJpaEntity s WHERE s.targetId = :targetId")
    Optional<RateableTargetStatsJpaEntity> findByTargetIdForUpdate(@Param("targetId") UUID targetId);

    @Query(value = """
        SELECT
            COALESCE(ROUND(AVG(rt.rating), 2), 0.00) AS average_rating,
            COUNT(rt.id) AS reviews_count
        FROM review_targets rt
        JOIN reviews r ON r.id = rt.review_id
        WHERE rt.target_id = :targetId
          AND r.status = 'ACTIVE'
    """, nativeQuery = true)
    Tuple calculateAggregateStats(@Param("targetId") UUID targetId);
}
