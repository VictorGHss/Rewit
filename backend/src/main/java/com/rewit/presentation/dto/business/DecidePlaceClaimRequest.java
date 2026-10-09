package com.rewit.presentation.dto.business;

import com.rewit.domain.enums.PlaceClaimDecision;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Payload para decisão de solicitação de reivindicação por moderador ou administrador (C9).
 */
public record DecidePlaceClaimRequest(
        @NotNull(message = "A decisão é obrigatória")
        PlaceClaimDecision decision,

        @NotBlank(message = "A justificativa é obrigatória")
        @Size(min = 15, max = 1000, message = "A justificativa deve conter entre 15 e 1000 caracteres")
        String justification
) {
}
