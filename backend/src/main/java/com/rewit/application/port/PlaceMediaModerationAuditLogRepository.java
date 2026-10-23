package com.rewit.application.port;

import com.rewit.domain.model.PlaceMediaModerationAuditLog;

import java.util.List;
import java.util.UUID;

/**
 * Porta de saída para persistência e recuperação da trilha de auditoria de moderação de fotos de locais.
 */
public interface PlaceMediaModerationAuditLogRepository {

    PlaceMediaModerationAuditLog save(PlaceMediaModerationAuditLog auditLog);

    List<PlaceMediaModerationAuditLog> findByPlaceMediaId(UUID placeMediaId);

    List<PlaceMediaModerationAuditLog> findByPlaceId(UUID placeId);
}

