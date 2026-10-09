package com.rewit.presentation.dto.business;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Payload para solicitação de reivindicação de local por conta comercial (C9).
 */
public record RequestPlaceClaimRequest(
        @NotNull(message = "O local é obrigatório")
        UUID placeId,

        @NotBlank(message = "A descrição da evidência é obrigatória")
        @Size(min = 20, max = 1000, message = "A descrição da evidência deve conter entre 20 e 1000 caracteres")
        String evidenceDescription
) {
}
