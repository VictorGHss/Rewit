package com.rewit.domain.model;

import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.AccountStatus;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/**
 * Registro append-only criptograficamente autenticado de uma ação administrativa sobre o ciclo de vida
 * de uma conta de usuário ({@code account_moderation_audit_logs}, V45 / S3-A13-F2).
 */
public final class AccountModerationAuditLog {

    public enum Action {
        SUSPEND,
        REINSTATE,
        DELETE
    }

    public static final int MAX_REASON_LENGTH = 1000;

    private final UUID id;
    private final UUID targetUserId;
    private final UUID actorUserId;
    private final Action action;
    private final AccountStatus previousStatus;
    private final AccountStatus newStatus;
    private final String reason;
    private final Instant createdAt;
    private final String authenticityMac;

    public AccountModerationAuditLog(
            UUID id,
            UUID targetUserId,
            UUID actorUserId,
            Action action,
            AccountStatus previousStatus,
            AccountStatus newStatus,
            String reason,
            Instant createdAt,
            String authenticityMac
    ) {
        if (targetUserId == null) {
            throw new BusinessException("O identificador da conta alvo é obrigatório", "MISSING_TARGET_USER_ID");
        }
        if (actorUserId == null) {
            throw new BusinessException("O identificador do administrador ator é obrigatório", "MISSING_ACTOR_USER_ID");
        }
        if (targetUserId.equals(actorUserId)) {
            throw new BusinessException("O administrador não pode auditar ação na própria conta", "SELF_ACCOUNT_LIFECYCLE_FORBIDDEN");
        }
        if (action == null) {
            throw new BusinessException("A ação administrativa é obrigatória", "MISSING_ACCOUNT_ACTION");
        }
        if (previousStatus == null || newStatus == null) {
            throw new BusinessException("Os estados anterior e novo da conta são obrigatórios", "MISSING_ACCOUNT_STATUS");
        }
        if (reason == null || reason.isBlank()) {
            throw new BusinessException("O motivo da ação administrativa é obrigatório", "MISSING_REASON");
        }
        String trimmedReason = reason.trim();
        if (trimmedReason.length() > MAX_REASON_LENGTH) {
            throw new BusinessException("O motivo não pode exceder 1000 caracteres", "INVALID_REASON_LENGTH");
        }

        this.id = id != null ? id : UUID.randomUUID();
        this.targetUserId = targetUserId;
        this.actorUserId = actorUserId;
        this.action = action;
        this.previousStatus = previousStatus;
        this.newStatus = newStatus;
        this.reason = trimmedReason;
        this.createdAt = (createdAt != null ? createdAt : Instant.now()).truncatedTo(ChronoUnit.MICROS);
        this.authenticityMac = authenticityMac;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTargetUserId() {
        return targetUserId;
    }

    public UUID getActorUserId() {
        return actorUserId;
    }

    public Action getAction() {
        return action;
    }

    public AccountStatus getPreviousStatus() {
        return previousStatus;
    }

    public AccountStatus getNewStatus() {
        return newStatus;
    }

    public String getReason() {
        return reason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getAuthenticityMac() {
        return authenticityMac;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AccountModerationAuditLog that = (AccountModerationAuditLog) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
