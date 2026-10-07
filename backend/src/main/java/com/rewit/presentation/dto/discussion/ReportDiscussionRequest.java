package com.rewit.presentation.dto.discussion;

import com.rewit.domain.enums.ReportReason;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Payload da denúncia de uma discussão. O denunciante vem exclusivamente do token JWT.
 */
public record ReportDiscussionRequest(
        @NotNull(message = "O motivo da denúncia é obrigatório")
        ReportReason reason,

        @Size(max = 500, message = "O detalhe da denúncia não pode exceder 500 caracteres")
        String detail
) {}
