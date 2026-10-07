package com.rewit.infrastructure.persistence.repository;

import com.rewit.infrastructure.persistence.entity.DiscussionModerationAuditLogJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Repositório Spring Data JPA de {@code discussion_moderation_audit_logs}.
 */
public interface DiscussionModerationAuditLogJpaRepository extends JpaRepository<DiscussionModerationAuditLogJpaEntity, UUID> {

    List<DiscussionModerationAuditLogJpaEntity> findByDiscussionIdOrderByCreatedAtAscIdAsc(UUID discussionId);
}
