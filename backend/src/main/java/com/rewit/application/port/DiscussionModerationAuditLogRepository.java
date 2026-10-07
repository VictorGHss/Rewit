package com.rewit.application.port;

import com.rewit.domain.model.DiscussionModerationAuditLog;

import java.util.List;
import java.util.UUID;

/**
 * Porta da trilha de auditoria append-only da moderação de discussões: só inclusão e leitura.
 */
public interface DiscussionModerationAuditLogRepository {

    DiscussionModerationAuditLog save(DiscussionModerationAuditLog auditLog);

    /** Histórico da discussão em ordem cronológica. */
    List<DiscussionModerationAuditLog> findByDiscussionId(UUID discussionId);
}
