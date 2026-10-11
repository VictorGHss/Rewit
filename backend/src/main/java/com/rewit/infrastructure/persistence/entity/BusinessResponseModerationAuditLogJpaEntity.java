package com.rewit.infrastructure.persistence.entity;

import com.rewit.domain.enums.BusinessResponseModerationAction;
import com.rewit.domain.enums.BusinessResponseStatus;
import com.rewit.domain.model.BusinessResponseModerationAuditLog;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Entidade JPA da tabela business_response_moderation_audit_logs (V24). Append-only: todas as colunas são
 * {@code updatable = false} e o banco rejeita UPDATE por trigger.
 */
@Entity
@Table(name = "business_response_moderation_audit_logs")
public class BusinessResponseModerationAuditLogJpaEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "response_id", nullable = false, updatable = false)
    private UUID responseId;

    @Column(name = "moderator_user_id", nullable = false, updatable = false)
    private UUID moderatorUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, updatable = false, length = 32)
    private BusinessResponseModerationAction action;

    @Column(name = "reason_code", nullable = false, updatable = false, length = 64)
    private String reasonCode;

    @Column(name = "justification", nullable = false, updatable = false, columnDefinition = "TEXT")
    private String justification;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_status", nullable = false, updatable = false, length = 16)
    private BusinessResponseStatus previousStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_status", nullable = false, updatable = false, length = 16)
    private BusinessResponseStatus newStatus;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected BusinessResponseModerationAuditLogJpaEntity() {
    }

    public static BusinessResponseModerationAuditLogJpaEntity fromDomain(BusinessResponseModerationAuditLog log) {
        BusinessResponseModerationAuditLogJpaEntity entity = new BusinessResponseModerationAuditLogJpaEntity();
        entity.id = log.getId();
        entity.responseId = log.getResponseId();
        entity.moderatorUserId = log.getModeratorUserId();
        entity.action = log.getAction();
        entity.reasonCode = log.getReasonCode();
        entity.justification = log.getJustification();
        entity.previousStatus = log.getPreviousStatus();
        entity.newStatus = log.getNewStatus();
        entity.createdAt = log.getCreatedAt();
        return entity;
    }

    public BusinessResponseModerationAuditLog toDomain() {
        return new BusinessResponseModerationAuditLog(id, responseId, moderatorUserId, action, reasonCode, justification,
                previousStatus, newStatus, createdAt);
    }
}
