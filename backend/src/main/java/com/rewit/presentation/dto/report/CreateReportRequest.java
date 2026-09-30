package com.rewit.presentation.dto.report;

import com.rewit.domain.enums.ReportReason;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Payload de entrada para criação de denúncia comunitária (POST /api/v1/reports).
 * O denunciante NUNCA é recebido no corpo, sendo extraído exclusivamente do token JWT.
 */
public record CreateReportRequest(
        @NotNull(message = "O ID da avaliação é obrigatório")
        UUID reviewId,

        @NotNull(message = "O motivo da denúncia é obrigatório")
        ReportReason reason,

        @Size(max = 500, message = "O detalhe da denúncia não pode exceder 500 caracteres")
        String detail
) {}
