package com.rewit.presentation.dto.admin;

import com.rewit.application.dto.business.BusinessResponseDtos.BusinessResponseModerationResult;
import com.rewit.domain.enums.BusinessResponseModerationAction;
import com.rewit.domain.enums.BusinessResponseRemovalSource;
import com.rewit.domain.enums.BusinessResponseStatus;
import com.rewit.domain.model.BusinessResponseModerationAuditLog;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/**
 * DTOs HTTP da moderação administrativa de respostas oficiais de empresas (C11).
 */
public final class AdminBusinessResponseModerationDtos {

    private AdminBusinessResponseModerationDtos() {
    }

    /**
     * Payload de entrada para moderação de resposta oficial.
     * O moderador vem exclusivamente do token JWT.
     */
    public record ModerateBusinessResponseRequest(
            @NotNull(message = "A ação de moderação é obrigatória")
            BusinessResponseModerationAction action,

            @NotBlank(message = "O código do motivo da decisão é obrigatório")
            @Size(max = BusinessResponseModerationAuditLog.MAX_REASON_CODE_LENGTH, message = "O reasonCode não pode exceder 64 caracteres")
            String reasonCode,

            @NotBlank(message = "A justificativa da decisão é obrigatória")
            @Size(min = 15, max = 1000, message = "A justificativa deve ter entre 15 e 1000 caracteres")
            String justification
    ) {
    }

    /**
     * Projeção administrativa resultante da moderação de resposta oficial.
     * Não expõe authorUserId, taxId nem detalhes desnecessários de infraestrutura.
     */
    public record ModerateBusinessResponseResponse(
            UUID auditLogId,
            UUID responseId,
            UUID reviewId,
            UUID discussionId,
            UUID businessAccountId,
            UUID placeId,
            BusinessResponseModerationAction action,
            String reasonCode,
            String justification,
            BusinessResponseStatus previousStatus,
            BusinessResponseStatus newStatus,
            BusinessResponseRemovalSource removedBy,
            Instant moderatedAt
    ) {
        public static ModerateBusinessResponseResponse fromResult(BusinessResponseModerationResult result) {
            if (result == null) {
                return null;
            }
            return new ModerateBusinessResponseResponse(
                    result.auditLogId(),
                    result.responseId(),
                    result.reviewId(),
                    result.discussionId(),
                    result.businessAccountId(),
                    result.placeId(),
                    result.action(),
                    result.reasonCode(),
                    result.justification(),
                    result.previousStatus(),
                    result.newStatus(),
                    result.removedBy(),
                    result.moderatedAt()
            );
        }
    }
}
