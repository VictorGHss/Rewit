package com.rewit.presentation.dto.admin;

import com.rewit.application.dto.report.ReportDtos.AdminReviewAuditView;
import com.rewit.application.dto.report.ReportDtos.AdminReviewContextView;
import com.rewit.application.dto.report.ReportDtos.AdminReviewReportView;
import com.rewit.application.dto.report.ReportDtos.AdminReviewTargetView;
import com.rewit.application.dto.report.ReportDtos.AdminReviewView;
import com.rewit.domain.enums.ModerationAction;
import com.rewit.domain.enums.ReportReason;
import com.rewit.domain.enums.ReportStatus;
import com.rewit.domain.enums.ReviewStatus;
import com.rewit.domain.enums.TargetType;
import com.rewit.domain.model.ModerationAuditLog;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * DTOs da camada de apresentação para os endpoints administrativos de moderação (Step 26.3).
 * Mantém separação estrita entre a representação HTTP e os DTOs da camada de aplicação.
 */
public final class AdminModerationDtos {

    private AdminModerationDtos() {}

    /**
     * Payload de entrada para a ação administrativa de moderação de avaliação.
     * O moderador NUNCA é recebido no corpo — é extraído exclusivamente do token JWT.
     */
    public record ModerateReviewRequest(
            @NotNull(message = "A ação de moderação é obrigatória")
            ModerationAction action,

            @NotBlank(message = "O código do motivo da decisão é obrigatório")
            @Size(max = ModerationAuditLog.MAX_REASON_CODE_LENGTH, message = "O reasonCode não pode exceder 64 caracteres")
            String reasonCode,

            @NotBlank(message = "A justificativa da decisão é obrigatória")
            @Size(min = 15, max = 1000, message = "A justificativa deve ter entre 15 e 1000 caracteres")
            String justification
    ) {}

    /**
     * Resposta da ação administrativa de moderação.
     * Expõe o registro de auditoria e o novo status da avaliação.
     */
    public record ModerateReviewResponse(
            UUID auditLogId,
            UUID reviewId,
            ModerationAction action,
            String reasonCode,
            String justification,
            ReviewStatus previousStatus,
            ReviewStatus newStatus,
            int resolvedReportsCount,
            Instant moderatedAt
    ) {}

    /**
     * Visão administrativa de uma denúncia para triagem backoffice.
     * Preserva privacidade: sem exposição de PII, senhas ou dados sensíveis.
     */
    public record AdminReportResponse(
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
     * Contexto de moderação de uma avaliação (GET /api/v1/admin/reviews/{reviewId}/context). Sem identidades: nem o
     * autor, nem denunciantes, nem moderadores aparecem, mesmo para MODERATOR/ADMIN.
     */
    public record AdminReviewContextResponse(
            AdminReviewContextReviewResponse review,
            long pendingReportCount,
            List<AdminReviewContextReportResponse> reports,
            List<AdminReviewContextAuditResponse> auditHistory
    ) {
        public static AdminReviewContextResponse fromView(AdminReviewContextView view) {
            AdminReviewView review = view.review();
            return new AdminReviewContextResponse(
                    new AdminReviewContextReviewResponse(
                            review.id(),
                            review.experienceText(),
                            review.status(),
                            review.visibility(),
                            review.isAnonymous(),
                            review.isVerifiedOnSite(),
                            review.createdAt(),
                            review.updatedAt(),
                            review.targets().stream().map(target -> AdminReviewContextTargetResponse.fromView(target)).toList()),
                    view.pendingReportCount(),
                    view.reports().stream().map(report -> AdminReviewContextReportResponse.fromView(report)).toList(),
                    view.auditHistory().stream().map(entry -> AdminReviewContextAuditResponse.fromView(entry)).toList());
        }
    }

    public record AdminReviewContextReviewResponse(
            UUID id,
            String experienceText,
            ReviewStatus status,
            String visibility,
            boolean isAnonymous,
            boolean isVerifiedOnSite,
            Instant createdAt,
            Instant updatedAt,
            List<AdminReviewContextTargetResponse> targets
    ) {}

    public record AdminReviewContextTargetResponse(
            UUID targetId,
            TargetType type,
            String displayName,
            BigDecimal rating,
            String specificComment
    ) {
        static AdminReviewContextTargetResponse fromView(AdminReviewTargetView view) {
            return new AdminReviewContextTargetResponse(view.targetId(), view.type(), view.displayName(), view.rating(),
                    view.specificComment());
        }
    }

    public record AdminReviewContextReportResponse(
            UUID id,
            ReportReason reason,
            String detail,
            ReportStatus status,
            Instant createdAt,
            Instant updatedAt
    ) {
        static AdminReviewContextReportResponse fromView(AdminReviewReportView view) {
            return new AdminReviewContextReportResponse(view.id(), view.reason(), view.detail(), view.status(),
                    view.createdAt(), view.updatedAt());
        }
    }

    public record AdminReviewContextAuditResponse(
            ModerationAction action,
            String reasonCode,
            String justification,
            ReviewStatus previousStatus,
            ReviewStatus newStatus,
            Instant createdAt
    ) {
        static AdminReviewContextAuditResponse fromView(AdminReviewAuditView view) {
            return new AdminReviewContextAuditResponse(view.action(), view.reasonCode(), view.justification(),
                    view.previousStatus(), view.newStatus(), view.createdAt());
        }
    }
}
