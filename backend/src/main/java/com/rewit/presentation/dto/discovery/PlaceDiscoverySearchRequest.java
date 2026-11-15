package com.rewit.presentation.dto.discovery;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

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
        Integer limit,

        // Região opcional (alternativa às coordenadas): restringe a busca à fronteira da cidade
        @Size(max = 100, message = "O nome da cidade deve conter até 100 caracteres")
        String city,

        // A sigla é validada no serviço (27 UFs oficiais), com o mesmo código INVALID_REGION
        @Size(max = 50, message = "A UF deve conter até 50 caracteres")
        String state
) {
    public PlaceDiscoverySearchRequest {
        if (limit == null) {
            limit = 10;
        }
    }
}
