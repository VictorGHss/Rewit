package com.rewit.presentation.dto.admin;

import com.rewit.application.dto.discussion.AdminDiscussionModerationDtos.AdminDiscussionContextView;
import com.rewit.application.dto.discussion.AdminDiscussionModerationDtos.AdminDiscussionReportView;
import com.rewit.application.dto.discussion.AdminDiscussionModerationDtos.AdminDiscussionView;
import com.rewit.domain.enums.DiscussionModerationAction;
import com.rewit.domain.enums.DiscussionStatus;
import com.rewit.domain.enums.ModerationDecision;
import com.rewit.domain.enums.ReportReason;
import com.rewit.domain.enums.ReportStatus;
import com.rewit.domain.model.DiscussionModerationAuditLog;
import com.rewit.domain.model.DiscussionReport;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * DTOs HTTP da moderação administrativa de discussões (MODERATOR/ADMIN).
 */
public final class AdminDiscussionModerationDtos {

    private AdminDiscussionModerationDtos() {
    }

    /** O moderador vem exclusivamente do token JWT. */
    public record ModerateDiscussionRequest(
            @NotNull(message = "A ação de moderação é obrigatória")
            DiscussionModerationAction action,

            @NotBlank(message = "O código do motivo da decisão é obrigatório")
            @Size(max = 64, message = "O reasonCode não pode exceder 64 caracteres")
            String reasonCode,

            @NotBlank(message = "A justificativa da decisão é obrigatória")
            @Size(min = 15, max = 1000, message = "A justificativa deve ter entre 15 e 1000 caracteres")
            String justification
    ) {}

    public record ModerateDiscussionResponse(
            UUID auditLogId,
            UUID discussionId,
            DiscussionModerationAction action,
            ModerationDecision decision,
            String reasonCode,
            String justification,
            DiscussionStatus previousStatus,
            DiscussionStatus newStatus,
            int resolvedReportsCount,
            Instant moderatedAt
    ) {
        public static ModerateDiscussionResponse fromAuditLog(DiscussionModerationAuditLog log) {
            return new ModerateDiscussionResponse(log.getId(), log.getDiscussionId(), log.getAction(), log.getDecision(),
                    log.getReasonCode(), log.getJustification(), log.getPreviousStatus(), log.getNewStatus(),
                    log.getReportsAffectedCount(), log.getCreatedAt());
        }
    }

    public record AdminDiscussionReportResponse(
            UUID id,
            UUID discussionId,
            UUID reviewId,
            UUID parentId,
            UUID discussionAuthorUserId,
            DiscussionStatus discussionStatus,
            UUID reporterUserId,
            ReportReason reason,
            String detail,
            ReportStatus status,
            Instant createdAt,
            Instant updatedAt
    ) {
        public static AdminDiscussionReportResponse fromView(AdminDiscussionReportView view) {
            return new AdminDiscussionReportResponse(view.id(), view.discussionId(), view.reviewId(), view.parentId(),
                    view.discussionAuthorUserId(), view.discussionStatus(), view.reporterUserId(), view.reason(),
                    view.detail(), view.status(), view.createdAt(), view.updatedAt());
        }
    }

    public record AdminDiscussionResponse(
            UUID id,
            UUID reviewId,
            UUID parentId,
            UUID authorUserId,
            String content,
            DiscussionStatus status,
            boolean isFromOwner,
            Instant createdAt,
            Instant updatedAt
    ) {
        static AdminDiscussionResponse fromView(AdminDiscussionView view) {
            return view == null ? null : new AdminDiscussionResponse(view.id(), view.reviewId(), view.parentId(),
                    view.authorUserId(), view.content(), view.status(), view.isFromOwner(), view.createdAt(), view.updatedAt());
        }
    }

    public record DiscussionReportEntry(
            UUID id,
            UUID reporterUserId,
            ReportReason reason,
            String detail,
            ReportStatus status,
            Instant createdAt,
            Instant updatedAt
    ) {
        static DiscussionReportEntry fromDomain(DiscussionReport report) {
            return new DiscussionReportEntry(report.getId(), report.getReporterUserId(), report.getReason(),
                    report.getDetail(), report.getStatus(), report.getCreatedAt(), report.getUpdatedAt());
        }
    }

    public record DiscussionAuditEntry(
            UUID id,
            UUID moderatorUserId,
            DiscussionModerationAction action,
            ModerationDecision decision,
            String reasonCode,
            String justification,
            DiscussionStatus previousStatus,
            DiscussionStatus newStatus,
            int reportsAffectedCount,
            Instant createdAt
    ) {
        static DiscussionAuditEntry fromDomain(DiscussionModerationAuditLog log) {
            return new DiscussionAuditEntry(log.getId(), log.getModeratorUserId(), log.getAction(), log.getDecision(),
                    log.getReasonCode(), log.getJustification(), log.getPreviousStatus(), log.getNewStatus(),
                    log.getReportsAffectedCount(), log.getCreatedAt());
        }
    }

    public record AdminDiscussionContextResponse(
            AdminDiscussionResponse discussion,
            AdminDiscussionResponse parent,
            long pendingReportCount,
            List<DiscussionReportEntry> reports,
            List<DiscussionAuditEntry> auditHistory
    ) {
        public static AdminDiscussionContextResponse fromView(AdminDiscussionContextView view) {
            return new AdminDiscussionContextResponse(
                    AdminDiscussionResponse.fromView(view.discussion()),
                    AdminDiscussionResponse.fromView(view.parent()),
                    view.pendingReportCount(),
                    view.reports().stream().map(DiscussionReportEntry::fromDomain).toList(),
                    view.auditHistory().stream().map(DiscussionAuditEntry::fromDomain).toList());
        }
    }
}
