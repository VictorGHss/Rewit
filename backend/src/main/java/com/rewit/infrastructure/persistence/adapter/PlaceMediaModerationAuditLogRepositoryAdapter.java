package com.rewit.infrastructure.persistence.adapter;

import com.rewit.application.port.PlaceMediaModerationAuditLogRepository;
import com.rewit.domain.model.PlaceMediaModerationAuditLog;
import com.rewit.infrastructure.persistence.entity.PlaceMediaModerationAuditLogJpaEntity;
import com.rewit.infrastructure.persistence.repository.PlaceMediaModerationAuditLogJpaRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Component
public class PlaceMediaModerationAuditLogRepositoryAdapter implements PlaceMediaModerationAuditLogRepository {

    private final PlaceMediaModerationAuditLogJpaRepository jpaRepository;

    public PlaceMediaModerationAuditLogRepositoryAdapter(PlaceMediaModerationAuditLogJpaRepository jpaRepository) {
        this.jpaRepository = Objects.requireNonNull(jpaRepository, "PlaceMediaModerationAuditLogJpaRepository must not be null");
    }

    @Override
    @Transactional
    public PlaceMediaModerationAuditLog save(PlaceMediaModerationAuditLog auditLog) {
        Objects.requireNonNull(auditLog, "PlaceMediaModerationAuditLog cannot be null");
        PlaceMediaModerationAuditLogJpaEntity entity = PlaceMediaModerationAuditLogJpaEntity.fromDomain(auditLog);
        PlaceMediaModerationAuditLogJpaEntity saved = jpaRepository.save(entity);
        return saved != null ? saved.toDomain() : auditLog;
    }

    @Override
    @Transactional(readOnly = true)
    public List<PlaceMediaModerationAuditLog> findByPlaceMediaId(UUID placeMediaId) {
        if (placeMediaId == null) return List.of();
        return jpaRepository.findByPlaceMediaIdOrderByCreatedAtAsc(placeMediaId)
                .stream()
                .map(PlaceMediaModerationAuditLogJpaEntity::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<PlaceMediaModerationAuditLog> findByPlaceId(UUID placeId) {
        if (placeId == null) return List.of();
        return jpaRepository.findByPlaceIdOrderByCreatedAtAsc(placeId)
                .stream()
                .map(PlaceMediaModerationAuditLogJpaEntity::toDomain)
                .toList();
    }
}

