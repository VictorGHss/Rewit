package com.rewit.infrastructure.persistence.adapter;

import com.rewit.application.port.DiscussionModerationAuditLogRepository;
import com.rewit.domain.model.DiscussionModerationAuditLog;
import com.rewit.infrastructure.persistence.entity.DiscussionModerationAuditLogJpaEntity;
import com.rewit.infrastructure.persistence.repository.DiscussionModerationAuditLogJpaRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Adaptador da trilha de auditoria da moderação de discussões. Somente inclusão: não há atualização nem remoção.
 */
@Component
public class DiscussionModerationAuditLogRepositoryAdapter implements DiscussionModerationAuditLogRepository {

    private final DiscussionModerationAuditLogJpaRepository jpaRepository;

    public DiscussionModerationAuditLogRepositoryAdapter(DiscussionModerationAuditLogJpaRepository jpaRepository) {
        this.jpaRepository = Objects.requireNonNull(jpaRepository, "DiscussionModerationAuditLogJpaRepository must not be null");
    }

    @Override
    @Transactional
    public DiscussionModerationAuditLog save(DiscussionModerationAuditLog auditLog) {
        Objects.requireNonNull(auditLog, "DiscussionModerationAuditLog cannot be null");
        return jpaRepository.saveAndFlush(DiscussionModerationAuditLogJpaEntity.fromDomain(auditLog)).toDomain();
    }

    @Override
    @Transactional(readOnly = true)
    public List<DiscussionModerationAuditLog> findByDiscussionId(UUID discussionId) {
        if (discussionId == null) {
            return List.of();
        }
        return jpaRepository.findByDiscussionIdOrderByCreatedAtAscIdAsc(discussionId).stream()
                .map(DiscussionModerationAuditLogJpaEntity::toDomain)
                .toList();
    }
}
