package com.rewit.application.dto.report;

import com.rewit.domain.enums.ModerationAction;
import com.rewit.domain.enums.ReportReason;
import com.rewit.domain.enums.ReportStatus;
import com.rewit.domain.enums.ReviewStatus;
import com.rewit.domain.model.ModerationAuditLog;
import com.rewit.domain.model.Report;
import com.rewit.domain.model.Review;

import java.time.Instant;
import java.util.UUID;

/**
 * DTOs da camada de aplicação para o subsistema de Denúncias e Moderação Administrativa (Step 19.0 / Step 26.2).
 */
public final class ReportDtos {

    private ReportDtos() {}

    public record CreateReportCommand(
            UUID reporterUserId,
            UUID reviewId,
            ReportReason reason,
            String detail
    ) {}

    public record ReportView(
            UUID id,
            UUID reviewId,
            ReportReason reason,
            ReportStatus status,
            Instant createdAt
    ) {
        public static ReportView fromDomain(Report report) {
            return new ReportView(
                    report.getId(),
                    report.getReviewId(),
                    report.getReason(),
                    report.getStatus(),
                    report.getCreatedAt()
            );
        }
    }

    /**
     * DTO representacional de denúncia para triagem administrativa (Backoffice).
     * Expõe o autor interno da avaliação sem expor dados sensíveis do usuário.
     */
    public record AdminReportView(
            UUID id,
            UUID reviewId,
            UUID reviewAuthorUserId,
            ReviewStatus reviewStatus,
            UUID reporterUserId,
            ReportReason reason,
            String detail,
            ReportStatus status,
            Instant createdAt,
            Instant updatedAt
    ) {}

    /**
     * Comando de entrada para moderação administrativa de avaliação.
     */
    public record ModerateReviewCommand(
            UUID reviewId,
            UUID moderatorUserId,
            ModerationAction action,
            String reasonCode,
            String justification,
            Instant now
    ) {
        public ModerateReviewCommand(
                UUID reviewId,
                UUID moderatorUserId,
                ModerationAction action,
                String reasonCode,
                String justification
        ) {
            this(reviewId, moderatorUserId, action, reasonCode, justification, null);
        }
    }

    /**
     * Resultado da execução da moderação administrativa.
     */
    public record ModerateReviewResult(
            ModerationAuditLog auditLog,
            Review review
    ) {}

    /**
     * Filtros para consulta e triagem administrativa da fila de denúncias.
     */
    public record QueryAdminReportsFilter(
            Integer page,
            Integer size,
            ReportStatus status,
            ReportReason reason,
            UUID reviewId,
            UUID reporterUserId,
            String sortDirection
    ) {
        public static QueryAdminReportsFilter ofDefault() {
            return new QueryAdminReportsFilter(0, 20, null, null, null, null, "asc");
        }
    }
}

