package com.rewit.infrastructure.persistence.adapter;

import com.rewit.application.port.BusinessResponseModerationAuditLogRepository;
import com.rewit.domain.model.BusinessResponseModerationAuditLog;
import com.rewit.infrastructure.persistence.entity.BusinessResponseModerationAuditLogJpaEntity;
import com.rewit.infrastructure.persistence.repository.BusinessResponseModerationAuditLogJpaRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Component
public class BusinessResponseModerationAuditLogRepositoryAdapter implements BusinessResponseModerationAuditLogRepository {

    private final BusinessResponseModerationAuditLogJpaRepository jpaRepository;

    public BusinessResponseModerationAuditLogRepositoryAdapter(BusinessResponseModerationAuditLogJpaRepository jpaRepository) {
        this.jpaRepository = Objects.requireNonNull(jpaRepository,
                "BusinessResponseModerationAuditLogJpaRepository must not be null");
    }

    @Override
    @Transactional
    public BusinessResponseModerationAuditLog save(BusinessResponseModerationAuditLog auditLog) {
        Objects.requireNonNull(auditLog, "BusinessResponseModerationAuditLog cannot be null");
        return jpaRepository.saveAndFlush(BusinessResponseModerationAuditLogJpaEntity.fromDomain(auditLog)).toDomain();
    }

    @Override
    @Transactional(readOnly = true)
    public List<BusinessResponseModerationAuditLog> findByResponseId(UUID responseId) {
        if (responseId == null) {
            return List.of();
        }
        return jpaRepository.findByResponseIdOrderByCreatedAtAscIdAsc(responseId).stream()
                .map(entity -> entity.toDomain())
                .toList();
    }
}
