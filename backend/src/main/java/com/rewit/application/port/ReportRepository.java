package com.rewit.application.port;

import com.rewit.application.dto.common.PageResult;
import com.rewit.domain.enums.ReportReason;
import com.rewit.domain.enums.ReportStatus;
import com.rewit.domain.model.Report;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Porta de persistência para a entidade Report (Step 19.0 / Step 26.2).
 */
public interface ReportRepository {

    Report save(Report report);

    List<Report> saveAll(List<Report> reports);

    Optional<Report> findByReviewIdAndReporterUserId(UUID reviewId, UUID reporterUserId);

    List<Report> findPendingByReviewId(UUID reviewId);

    /** Todas as denúncias da avaliação, em qualquer status, em ordem cronológica (created_at, id). */
    List<Report> findByReviewId(UUID reviewId);

    long countPendingByReviewId(UUID reviewId);

    boolean existsByReviewIdAndReporterUserId(UUID reviewId, UUID reporterUserId);

    PageResult<Report> findAllPaged(
            ReportStatus status,
            ReportReason reason,
            UUID reviewId,
            UUID reporterUserId,
            int page,
            int size,
            String sortDirection
    );
}

