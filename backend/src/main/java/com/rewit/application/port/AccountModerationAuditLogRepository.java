package com.rewit.application.port;

import com.rewit.domain.model.AccountModerationAuditLog;

import java.util.List;
import java.util.UUID;

/**
 * Porta de persistência append-only para {@link AccountModerationAuditLog}.
 */
public interface AccountModerationAuditLogRepository {

    AccountModerationAuditLog save(AccountModerationAuditLog log);

    List<AccountModerationAuditLog> findByTargetUserId(UUID targetUserId);
}
