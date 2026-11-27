package com.rewit.infrastructure.persistence.repository;

import com.rewit.infrastructure.persistence.entity.AccountModerationAuditLogJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AccountModerationAuditLogJpaRepository extends JpaRepository<AccountModerationAuditLogJpaEntity, UUID> {

    List<AccountModerationAuditLogJpaEntity> findByTargetUserIdOrderByCreatedAtDescIdDesc(UUID targetUserId);
}
