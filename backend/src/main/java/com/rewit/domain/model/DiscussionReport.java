package com.rewit.domain.model;

import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.ReportReason;
import com.rewit.domain.enums.ReportStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Denúncia comunitária de uma discussão (comentário ou resposta). Agregado próprio, separado da denúncia
 * de avaliação: uma por usuário por discussão, resolvida somente pela moderação.
 */
public class DiscussionReport {

    public static final int MAX_DETAIL_LENGTH = 500;

    private final UUID id;
    private final UUID discussionId;
    private final UUID reporterUserId;
    private final ReportReason reason;
    private final String detail;
    private ReportStatus status;
    private final Instant createdAt;
    private Instant updatedAt;

    public DiscussionReport(UUID id, UUID discussionId, UUID reporterUserId, ReportReason reason, String detail) {
        this(id, discussionId, reporterUserId, reason, detail, ReportStatus.PENDING, Instant.now(), Instant.now());
    }

    public DiscussionReport(UUID id, UUID discussionId, UUID reporterUserId, ReportReason reason, String detail,
                            ReportStatus status, Instant createdAt, Instant updatedAt) {
        if (discussionId == null) {
            throw new BusinessException("A discussão denunciada é obrigatória", "MISSING_DISCUSSION_ID");
        }
        if (reporterUserId == null) {
            throw new BusinessException("O denunciante é obrigatório", "MISSING_REPORTER_ID");
        }
        if (reason == null) {
            throw new BusinessException("O motivo da denúncia é obrigatório", "INVALID_REPORT_REASON");
        }
        String normalizedDetail = detail != null && !detail.isBlank() ? detail.trim() : null;
        if (normalizedDetail != null && normalizedDetail.length() > MAX_DETAIL_LENGTH) {
            throw new BusinessException("O detalhe da denúncia não pode exceder 500 caracteres", "INVALID_REPORT_DETAIL");
        }
        this.id = id != null ? id : UUID.randomUUID();
        this.discussionId = discussionId;
        this.reporterUserId = reporterUserId;
        this.reason = reason;
        this.detail = normalizedDetail;
        this.status = status != null ? status : ReportStatus.PENDING;
        this.createdAt = createdAt != null ? createdAt : Instant.now();
        this.updatedAt = updatedAt != null ? updatedAt : Instant.now();
    }

    /** Denúncia procedente: a discussão foi removida pela moderação. */
    public void resolveAsAccepted(Instant now) {
        validateResolution(now);
        this.status = ReportStatus.ACCEPTED;
        this.updatedAt = now;
    }

    /** Denúncia improcedente: a discussão foi restaurada pela moderação. */
    public void resolveAsRejected(Instant now) {
        validateResolution(now);
        this.status = ReportStatus.REJECTED;
        this.updatedAt = now;
    }

    public boolean isPending() {
        return status == ReportStatus.PENDING;
    }

    private void validateResolution(Instant now) {
        if (now == null) {
            throw new BusinessException("O timestamp de atualização é obrigatório", "MISSING_UPDATE_TIMESTAMP");
        }
        if (this.status != ReportStatus.PENDING) {
            throw new BusinessException("A denúncia já se encontra resolvida", "REPORT_ALREADY_RESOLVED");
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getDiscussionId() {
        return discussionId;
    }

    public UUID getReporterUserId() {
        return reporterUserId;
    }

    public ReportReason getReason() {
        return reason;
    }

    public String getDetail() {
        return detail;
    }

    public ReportStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
