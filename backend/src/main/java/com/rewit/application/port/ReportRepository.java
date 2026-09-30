package com.rewit.application.port;

import com.rewit.domain.model.Report;

import java.util.Optional;
import java.util.UUID;

/**
 * Porta de persistência para a entidade Report.
 */
public interface ReportRepository {

    Report save(Report report);

    Optional<Report> findByReviewIdAndReporterUserId(UUID reviewId, UUID reporterUserId);

    long countPendingByReviewId(UUID reviewId);

    boolean existsByReviewIdAndReporterUserId(UUID reviewId, UUID reporterUserId);
}
