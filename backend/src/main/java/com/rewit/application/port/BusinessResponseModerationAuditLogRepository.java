package com.rewit.application.port;

import com.rewit.domain.model.BusinessResponseModerationAuditLog;

import java.util.List;
import java.util.UUID;

/**
 * Porta da trilha de auditoria append-only da moderação de respostas oficiais: só inclusão e leitura.
 */
public interface BusinessResponseModerationAuditLogRepository {

    BusinessResponseModerationAuditLog save(BusinessResponseModerationAuditLog auditLog);

    /** Histórico da resposta em ordem cronológica. */
    List<BusinessResponseModerationAuditLog> findByResponseId(UUID responseId);
}
