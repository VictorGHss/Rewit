package com.rewit.infrastructure.persistence.adapter;

import com.rewit.application.port.PlaceClaimModerationAuditLogRepository;
import com.rewit.domain.model.PlaceClaimModerationAuditLog;
import com.rewit.infrastructure.persistence.entity.PlaceClaimModerationAuditLogJpaEntity;
import com.rewit.infrastructure.persistence.repository.PlaceClaimModerationAuditLogJpaRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Component
public class PlaceClaimModerationAuditLogRepositoryAdapter implements PlaceClaimModerationAuditLogRepository {

    private final PlaceClaimModerationAuditLogJpaRepository repository;

    public PlaceClaimModerationAuditLogRepositoryAdapter(PlaceClaimModerationAuditLogJpaRepository repository) {
        this.repository = Objects.requireNonNull(repository, "PlaceClaimModerationAuditLogJpaRepository must not be null");
    }

    @Override
    public PlaceClaimModerationAuditLog save(PlaceClaimModerationAuditLog log) {
        Objects.requireNonNull(log, "PlaceClaimModerationAuditLog must not be null");
        return repository.saveAndFlush(PlaceClaimModerationAuditLogJpaEntity.fromDomain(log)).toDomain();
    }

    @Override
    public List<PlaceClaimModerationAuditLog> findByClaimId(UUID claimId) {
        if (claimId == null) {
            return List.of();
        }
        return repository.findByClaimIdOrderByCreatedAtDescIdDesc(claimId).stream()
                .map(PlaceClaimModerationAuditLogJpaEntity::toDomain)
                .toList();
    }
}
