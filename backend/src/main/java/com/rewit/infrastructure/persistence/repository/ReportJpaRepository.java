package com.rewit.infrastructure.persistence.repository;

import com.rewit.infrastructure.persistence.entity.ReportJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * Repositório Spring Data JPA para entidades ReportJpaEntity.
 */
public interface ReportJpaRepository extends JpaRepository<ReportJpaEntity, UUID> {

    Optional<ReportJpaEntity> findByReviewIdAndReporterUserId(UUID reviewId, UUID reporterUserId);

    boolean existsByReviewIdAndReporterUserId(UUID reviewId, UUID reporterUserId);

    @Query("SELECT COUNT(r) FROM ReportJpaEntity r WHERE r.reviewId = :reviewId AND r.status = 'PENDING'")
    long countPendingByReviewId(@Param("reviewId") UUID reviewId);
}
