package com.rewit.presentation.dto.discovery;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * Parâmetros de requisição para busca textual na API de descoberta de lugares.
 */
public record PlaceDiscoverySearchRequest(
        @NotBlank(message = "O termo de busca (query) é obrigatório")
        String query,

        Double latitude,

        Double longitude,

        Double radius,

        @Min(value = 1, message = "O limite deve ser no mínimo 1")
        @Max(value = 20, message = "O limite deve ser no máximo 20")
        Integer limit
) {
    public PlaceDiscoverySearchRequest {
        if (limit == null) {
            limit = 10;
        }
    }
}
