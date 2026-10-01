package com.rewit.domain.model;

import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.ReportReason;
import com.rewit.domain.enums.ReportStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Entidade de Domínio representando a denúncia comunitária de uma avaliação (Step 19.0).
 */
public class Report {

    private final UUID id;
    private final UUID reviewId;
    private final UUID reporterUserId;
    private final ReportReason reason;
    private final String detail;
    private ReportStatus status;
    private final Instant createdAt;
    private Instant updatedAt;

    public Report(UUID id, UUID reviewId, UUID reporterUserId, ReportReason reason, String detail) {
        this(id, reviewId, reporterUserId, reason, detail, ReportStatus.PENDING, Instant.now(), Instant.now());
    }

    public Report(UUID id, UUID reviewId, UUID reporterUserId, ReportReason reason, String detail,
                  ReportStatus status, Instant createdAt, Instant updatedAt) {
        if (reviewId == null) {
            throw new BusinessException("A avaliação denunciada é obrigatória", "MISSING_REVIEW_ID");
        }
        if (reporterUserId == null) {
            throw new BusinessException("O denunciante é obrigatório", "MISSING_REPORTER_ID");
        }
        if (reason == null) {
            throw new BusinessException("O motivo da denúncia é obrigatório", "INVALID_REPORT_REASON");
        }

        String normalizedDetail = detail != null && !detail.isBlank() ? detail.trim() : null;
        if (normalizedDetail != null && normalizedDetail.length() > 500) {
            throw new BusinessException("O detalhe da denúncia não pode exceder 500 caracteres", "INVALID_REPORT_DETAIL");
        }

        this.id = id != null ? id : UUID.randomUUID();
        this.reviewId = reviewId;
        this.reporterUserId = reporterUserId;
        this.reason = reason;
        this.detail = normalizedDetail;
        this.status = status != null ? status : ReportStatus.PENDING;
        this.createdAt = createdAt != null ? createdAt : Instant.now();
        this.updatedAt = updatedAt != null ? updatedAt : Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getReviewId() {
        return reviewId;
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

    /**
     * Resolve a denúncia como deferida/aceita (ACCEPTED).
     * Rejeita se a denúncia já estiver resolvida (ACCEPTED ou REJECTED).
     *
     * @param now instante explícito da resolução
     */
    public void resolveAsAccepted(Instant now) {
        validateResolution(now);
        this.status = ReportStatus.ACCEPTED;
        this.updatedAt = now;
    }

    /**
     * Resolve a denúncia como indeferida/rejeitada (REJECTED).
     * Rejeita se a denúncia já estiver resolvida (ACCEPTED ou REJECTED).
     *
     * @param now instante explícito da resolução
     */
    public void resolveAsRejected(Instant now) {
        validateResolution(now);
        this.status = ReportStatus.REJECTED;
        this.updatedAt = now;
    }

    private void validateResolution(Instant now) {
        if (now == null) {
            throw new BusinessException("O timestamp de atualização é obrigatório", "MISSING_UPDATE_TIMESTAMP");
        }
        if (this.status != ReportStatus.PENDING) {
            throw new BusinessException("A denúncia já se encontra resolvida", "REPORT_ALREADY_RESOLVED");
        }
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
