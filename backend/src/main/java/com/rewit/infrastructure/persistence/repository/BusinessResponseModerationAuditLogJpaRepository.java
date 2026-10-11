package com.rewit.infrastructure.persistence.repository;

import com.rewit.infrastructure.persistence.entity.BusinessResponseModerationAuditLogJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface BusinessResponseModerationAuditLogJpaRepository
        extends JpaRepository<BusinessResponseModerationAuditLogJpaEntity, UUID> {

    List<BusinessResponseModerationAuditLogJpaEntity> findByResponseIdOrderByCreatedAtAscIdAsc(UUID responseId);
}
