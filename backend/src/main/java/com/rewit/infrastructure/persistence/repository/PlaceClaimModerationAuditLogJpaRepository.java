package com.rewit.infrastructure.persistence.repository;

import com.rewit.infrastructure.persistence.entity.PlaceClaimModerationAuditLogJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PlaceClaimModerationAuditLogJpaRepository extends JpaRepository<PlaceClaimModerationAuditLogJpaEntity, UUID> {

    List<PlaceClaimModerationAuditLogJpaEntity> findByClaimIdOrderByCreatedAtDescIdDesc(UUID claimId);
}
