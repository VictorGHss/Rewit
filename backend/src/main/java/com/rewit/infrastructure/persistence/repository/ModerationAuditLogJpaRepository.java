package com.rewit.infrastructure.persistence.repository;

import com.rewit.infrastructure.persistence.entity.ModerationAuditLogJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Repositório Spring Data JPA para entidades ModerationAuditLogJpaEntity.
 */
public interface ModerationAuditLogJpaRepository extends JpaRepository<ModerationAuditLogJpaEntity, UUID> {

    List<ModerationAuditLogJpaEntity> findByReviewIdOrderByCreatedAtDesc(UUID reviewId);
}

