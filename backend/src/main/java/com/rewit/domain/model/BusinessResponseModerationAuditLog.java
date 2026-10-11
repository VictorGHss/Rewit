package com.rewit.domain.model;

import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.BusinessResponseModerationAction;
import com.rewit.domain.enums.BusinessResponseStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Registro imutável (append-only) de uma decisão de moderação sobre uma resposta oficial de empresa (C11).
 * A decisão é a própria ação; respostas não têm denúncias nesta versão.
 */
public final class BusinessResponseModerationAuditLog {

    public static final int MAX_REASON_CODE_LENGTH = 64;

    private final UUID id;
    private final UUID responseId;
    private final UUID moderatorUserId;
    private final BusinessResponseModerationAction action;
    private final String reasonCode;
    private final String justification;
    private final BusinessResponseStatus previousStatus;
    private final BusinessResponseStatus newStatus;
    private final Instant createdAt;

    public BusinessResponseModerationAuditLog(UUID id, UUID responseId, UUID moderatorUserId,
                                              BusinessResponseModerationAction action, String reasonCode,
                                              String justification, BusinessResponseStatus previousStatus,
                                              BusinessResponseStatus newStatus, Instant createdAt) {
        if (responseId == null) {
            throw new BusinessException("O identificador da resposta auditada é obrigatório", "MISSING_BUSINESS_RESPONSE_ID");
        }
        if (moderatorUserId == null) {
            throw new BusinessException("O identificador do moderador responsável é obrigatório", "MISSING_MODERATOR_ID");
        }
        if (action == null) {
            throw new BusinessException("A ação de moderação é obrigatória", "MISSING_MODERATION_ACTION");
        }
        if (reasonCode == null || reasonCode.isBlank()) {
            throw new BusinessException("O código do motivo da decisão é obrigatório", "MISSING_REASON_CODE");
        }
        if (reasonCode.trim().length() > MAX_REASON_CODE_LENGTH) {
            throw new BusinessException("O código do motivo não pode exceder 64 caracteres", "INVALID_REASON_CODE_LENGTH");
        }
        if (justification == null || justification.isBlank()) {
            throw new BusinessException("A justificativa da decisão é obrigatória", "MISSING_JUSTIFICATION");
        }
        if (previousStatus == null || newStatus == null) {
            throw new BusinessException("Os status anterior e novo da resposta são obrigatórios", "MISSING_BUSINESS_RESPONSE_STATUS");
        }
        this.id = id != null ? id : UUID.randomUUID();
        this.responseId = responseId;
        this.moderatorUserId = moderatorUserId;
        this.action = action;
        this.reasonCode = reasonCode.trim();
        this.justification = justification.trim();
        this.previousStatus = previousStatus;
        this.newStatus = newStatus;
        this.createdAt = createdAt != null ? createdAt : Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getResponseId() {
        return responseId;
    }

    public UUID getModeratorUserId() {
        return moderatorUserId;
    }

    public BusinessResponseModerationAction getAction() {
        return action;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public String getJustification() {
        return justification;
    }

    public BusinessResponseStatus getPreviousStatus() {
        return previousStatus;
    }

    public BusinessResponseStatus getNewStatus() {
        return newStatus;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
