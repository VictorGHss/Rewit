package com.rewit.infrastructure.persistence.repository;

import com.rewit.domain.enums.ReportStatus;
import com.rewit.infrastructure.persistence.entity.DiscussionReportJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repositório Spring Data JPA de {@code discussion_reports}.
 */
public interface DiscussionReportJpaRepository extends JpaRepository<DiscussionReportJpaEntity, UUID> {

    Optional<DiscussionReportJpaEntity> findByDiscussionIdAndReporterUserId(UUID discussionId, UUID reporterUserId);

    long countByDiscussionIdAndStatus(UUID discussionId, ReportStatus status);
}
