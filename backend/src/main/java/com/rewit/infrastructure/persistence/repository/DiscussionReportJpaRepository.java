package com.rewit.infrastructure.persistence.repository;

import com.rewit.domain.enums.ReportReason;
import com.rewit.domain.enums.ReportStatus;
import com.rewit.infrastructure.persistence.entity.DiscussionReportJpaEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repositório Spring Data JPA de {@code discussion_reports}.
 */
public interface DiscussionReportJpaRepository extends JpaRepository<DiscussionReportJpaEntity, UUID> {

    Optional<DiscussionReportJpaEntity> findByDiscussionIdAndReporterUserId(UUID discussionId, UUID reporterUserId);

    long countByDiscussionIdAndStatus(UUID discussionId, ReportStatus status);

    List<DiscussionReportJpaEntity> findByDiscussionIdAndStatus(UUID discussionId, ReportStatus status);

    List<DiscussionReportJpaEntity> findByDiscussionIdOrderByCreatedAtAscIdAsc(UUID discussionId);

    boolean existsByDiscussionIdAndReporterUserId(UUID discussionId, UUID reporterUserId);

    @Query(value = """
        SELECT r FROM DiscussionReportJpaEntity r
        WHERE (:status IS NULL OR r.status = :status)
          AND (:reason IS NULL OR r.reason = :reason)
          AND (:discussionId IS NULL OR r.discussionId = :discussionId)
        """,
        countQuery = """
        SELECT COUNT(r) FROM DiscussionReportJpaEntity r
        WHERE (:status IS NULL OR r.status = :status)
          AND (:reason IS NULL OR r.reason = :reason)
          AND (:discussionId IS NULL OR r.discussionId = :discussionId)
        """)
    Page<DiscussionReportJpaEntity> findAdminPage(@Param("status") ReportStatus status,
                                                  @Param("reason") ReportReason reason,
                                                  @Param("discussionId") UUID discussionId,
                                                  Pageable pageable);
}
