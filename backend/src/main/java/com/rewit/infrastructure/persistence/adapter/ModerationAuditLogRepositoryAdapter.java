package com.rewit.infrastructure.persistence.adapter;

import com.rewit.application.port.ModerationAuditLogRepository;
import com.rewit.domain.model.ModerationAuditLog;
import com.rewit.infrastructure.persistence.entity.ModerationAuditLogJpaEntity;
import com.rewit.infrastructure.persistence.repository.ModerationAuditLogJpaRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Adaptador de persistência para ModerationAuditLogRepository (Step 26.1).
 */
@Component
public class ModerationAuditLogRepositoryAdapter implements ModerationAuditLogRepository {

    private final ModerationAuditLogJpaRepository jpaRepository;

    public ModerationAuditLogRepositoryAdapter(ModerationAuditLogJpaRepository jpaRepository) {
        this.jpaRepository = Objects.requireNonNull(jpaRepository, "ModerationAuditLogJpaRepository must not be null");
    }

    @Override
    public ModerationAuditLog save(ModerationAuditLog auditLog) {
        Objects.requireNonNull(auditLog, "ModerationAuditLog must not be null");
        ModerationAuditLogJpaEntity entity = ModerationAuditLogJpaEntity.fromDomain(auditLog);
        ModerationAuditLogJpaEntity saved = jpaRepository.saveAndFlush(entity);
        return saved.toDomain();
    }

    @Override
    public List<ModerationAuditLog> findByReviewId(UUID reviewId) {
        if (reviewId == null) {
            return List.of();
        }
        return jpaRepository.findByReviewIdOrderByCreatedAtAscIdAsc(reviewId).stream()
                .map(entity -> entity.toDomain())
                .toList();
    }
}
