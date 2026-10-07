package com.rewit.application.dto.discussion;

import com.rewit.domain.enums.DiscussionModerationAction;
import com.rewit.domain.enums.DiscussionStatus;
import com.rewit.domain.enums.ReportReason;
import com.rewit.domain.enums.ReportStatus;
import com.rewit.domain.model.DiscussionModerationAuditLog;
import com.rewit.domain.model.DiscussionReport;
import com.rewit.domain.model.ReviewDiscussion;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Comandos e visões da moderação administrativa de discussões. Visões administrativas expõem o status interno
 * e o conteúdo, inclusive de comentários removidos: são acessíveis somente a MODERATOR e ADMIN.
 */
public final class AdminDiscussionModerationDtos {

    private AdminDiscussionModerationDtos() {
    }

    public record ModerateDiscussionCommand(
            UUID discussionId,
            UUID moderatorUserId,
            DiscussionModerationAction action,
            String reasonCode,
            String justification,
            Instant now
    ) {}

    public record ModerateDiscussionResult(DiscussionModerationAuditLog auditLog, ReviewDiscussion discussion) {}

    /** Denúncia na fila administrativa, com o contexto mínimo da discussão denunciada. */
    public record AdminDiscussionReportView(
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
    ) {}

    public record AdminDiscussionView(
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
        public static AdminDiscussionView fromDomain(ReviewDiscussion discussion) {
            return new AdminDiscussionView(discussion.getId(), discussion.getReviewId(), discussion.getParentId(),
                    discussion.getUserId(), discussion.getContent(), discussion.getStatus(), discussion.isFromOwner(),
                    discussion.getCreatedAt(), discussion.getUpdatedAt());
        }
    }

    /**
     * Contexto completo para decidir uma moderação.
     *
     * @param parent comentário raiz quando a discussão é uma resposta; {@code null} quando é raiz
     */
    public record AdminDiscussionContextView(
            AdminDiscussionView discussion,
            AdminDiscussionView parent,
            long pendingReportCount,
            List<DiscussionReport> reports,
            List<DiscussionModerationAuditLog> auditHistory
    ) {}
}
