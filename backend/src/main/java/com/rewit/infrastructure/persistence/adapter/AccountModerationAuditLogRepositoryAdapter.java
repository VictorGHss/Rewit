package com.rewit.infrastructure.persistence.adapter;

import com.rewit.application.port.AccountModerationAuditLogRepository;
import com.rewit.domain.model.AccountModerationAuditLog;
import com.rewit.infrastructure.persistence.entity.AccountModerationAuditLogJpaEntity;
import com.rewit.infrastructure.persistence.repository.AccountModerationAuditLogJpaRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Component
public class AccountModerationAuditLogRepositoryAdapter implements AccountModerationAuditLogRepository {

    private final AccountModerationAuditLogJpaRepository repository;

    public AccountModerationAuditLogRepositoryAdapter(AccountModerationAuditLogJpaRepository repository) {
        this.repository = Objects.requireNonNull(repository, "AccountModerationAuditLogJpaRepository must not be null");
    }

    @Override
    public AccountModerationAuditLog save(AccountModerationAuditLog log) {
        Objects.requireNonNull(log, "AccountModerationAuditLog must not be null");
        return repository.saveAndFlush(AccountModerationAuditLogJpaEntity.fromDomain(log)).toDomain();
    }

    @Override
    public List<AccountModerationAuditLog> findByTargetUserId(UUID targetUserId) {
        if (targetUserId == null) {
            return List.of();
        }
        return repository.findByTargetUserIdOrderByCreatedAtDescIdDesc(targetUserId).stream()
                .map(AccountModerationAuditLogJpaEntity::toDomain)
                .toList();
    }
}
