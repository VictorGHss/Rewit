package com.rewit.application.port;

import com.rewit.domain.model.PlaceClaimModerationAuditLog;

import java.util.List;
import java.util.UUID;

/**
 * Porta de persistência append-only para {@link PlaceClaimModerationAuditLog}.
 */
public interface PlaceClaimModerationAuditLogRepository {

    PlaceClaimModerationAuditLog save(PlaceClaimModerationAuditLog log);

    List<PlaceClaimModerationAuditLog> findByClaimId(UUID claimId);
}
