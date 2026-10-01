package com.rewit.presentation.dto.admin;

import com.rewit.domain.enums.ModerationAction;
import com.rewit.domain.enums.ReportReason;
import com.rewit.domain.enums.ReportStatus;
import com.rewit.domain.enums.ReviewStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
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
            @Size(max = 100, message = "O reasonCode não pode exceder 100 caracteres")
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
}
