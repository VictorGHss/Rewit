package com.rewit.infrastructure.persistence.repository;

import com.rewit.infrastructure.persistence.entity.ReportJpaEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
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

    @Query("SELECT r FROM ReportJpaEntity r WHERE r.reviewId = :reviewId AND r.status = 'PENDING'")
    List<ReportJpaEntity> findPendingByReviewId(@Param("reviewId") UUID reviewId);

    List<ReportJpaEntity> findByReviewIdOrderByCreatedAtAscIdAsc(UUID reviewId);

    @Query(value = """
        SELECT r FROM ReportJpaEntity r
        WHERE (:status IS NULL OR r.status = :status)
          AND (:reason IS NULL OR r.reason = :reason)
          AND (:reviewId IS NULL OR r.reviewId = :reviewId)
          AND (:reporterUserId IS NULL OR r.reporterUserId = :reporterUserId)
    """,
    countQuery = """
        SELECT COUNT(r) FROM ReportJpaEntity r
        WHERE (:status IS NULL OR r.status = :status)
          AND (:reason IS NULL OR r.reason = :reason)
          AND (:reviewId IS NULL OR r.reviewId = :reviewId)
          AND (:reporterUserId IS NULL OR r.reporterUserId = :reporterUserId)
    """)
    Page<ReportJpaEntity> findAdminReports(
            @Param("status") String status,
            @Param("reason") String reason,
            @Param("reviewId") UUID reviewId,
            @Param("reporterUserId") UUID reporterUserId,
            Pageable pageable
    );
}

