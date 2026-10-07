package com.rewit.application.port;

import com.rewit.domain.model.DiscussionReport;

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
}
