package com.rewit.application.port;

import com.rewit.application.dto.common.PageResult;
import com.rewit.domain.enums.ReportReason;
import com.rewit.domain.enums.ReportStatus;
import com.rewit.domain.model.DiscussionReport;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Porta de persistência das denúncias de discussões.
 */
public interface DiscussionReportRepository {

    DiscussionReport save(DiscussionReport report);

    Optional<DiscussionReport> findByDiscussionIdAndReporterUserId(UUID discussionId, UUID reporterUserId);

    /**
     * Denúncias PENDING da discussão. Cada denunciante tem no máximo uma denúncia por discussão
     * ({@code uq_discussion_report_reporter}), então a contagem é de denunciantes distintos.
     */
    long countPendingByDiscussionId(UUID discussionId);

    List<DiscussionReport> saveAll(List<DiscussionReport> reports);

    List<DiscussionReport> findPendingByDiscussionId(UUID discussionId);

    /** Todas as denúncias da discussão, em ordem cronológica. */
    List<DiscussionReport> findByDiscussionId(UUID discussionId);

    boolean existsByDiscussionIdAndReporterUserId(UUID discussionId, UUID reporterUserId);

    /** Fila administrativa com filtros opcionais, ordenada por created_at e id na direção informada. */
    PageResult<DiscussionReport> findAdminPage(ReportStatus status, ReportReason reason, UUID discussionId,
                                               int page, int size, String sortDirection);
}
