package com.rewit.presentation.dto.business;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Payload de solicitação de abertura de conta comercial (C9).
 */
public record CreateBusinessAccountRequest(
        @NotBlank(message = "A razão social é obrigatória")
        @Size(max = 255, message = "A razão social não pode exceder 255 caracteres")
        String corporateName,

        @NotBlank(message = "O documento fiscal é obrigatório")
        String taxId
) {
}
