package com.rewit.infrastructure.persistence.entity;

import com.rewit.domain.enums.AccountStatus;
import com.rewit.domain.model.AccountModerationAuditLog;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Entidade JPA append-only para {@code account_moderation_audit_logs} (V45 / S3-A13-F2).
 */
@Entity
@Table(name = "account_moderation_audit_logs")
public class AccountModerationAuditLogJpaEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "target_user_id", nullable = false, updatable = false)
    private UUID targetUserId;

    @Column(name = "actor_user_id", nullable = false, updatable = false)
    private UUID actorUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, updatable = false, length = 32)
    private AccountModerationAuditLog.Action action;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_status", nullable = false, updatable = false, length = 16)
    private AccountStatus previousStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_status", nullable = false, updatable = false, length = 16)
    private AccountStatus newStatus;

    @Column(name = "reason", nullable = false, updatable = false, columnDefinition = "TEXT")
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "authenticity_mac", nullable = false, updatable = false, length = 128)
    private String authenticityMac;

    protected AccountModerationAuditLogJpaEntity() {
    }

    public static AccountModerationAuditLogJpaEntity fromDomain(AccountModerationAuditLog log) {
        AccountModerationAuditLogJpaEntity entity = new AccountModerationAuditLogJpaEntity();
        entity.id = log.getId();
        entity.targetUserId = log.getTargetUserId();
        entity.actorUserId = log.getActorUserId();
        entity.action = log.getAction();
        entity.previousStatus = log.getPreviousStatus();
        entity.newStatus = log.getNewStatus();
        entity.reason = log.getReason();
        entity.createdAt = log.getCreatedAt();
        entity.authenticityMac = log.getAuthenticityMac();
        return entity;
    }

    public AccountModerationAuditLog toDomain() {
        return new AccountModerationAuditLog(
                id, targetUserId, actorUserId, action,
                previousStatus, newStatus, reason, createdAt, authenticityMac);
    }
}
