package com.rewit.application.port;

import com.rewit.domain.model.ModerationAuditLog;

/**
 * Porta de persistência append-only para registros de auditoria administrativa (Step 26.1).
 */
public interface ModerationAuditLogRepository {

    /**
     * Persiste um novo registro de auditoria de moderação.
     * Não há suporte a update ou delete neste contrato.
     */
    ModerationAuditLog save(ModerationAuditLog auditLog);
}
