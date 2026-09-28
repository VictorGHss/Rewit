package com.rewit.presentation.dto.discovery;

import com.rewit.domain.model.PlaceAttribution;

/**
 * DTO de resposta pública para metadados de atribuição e crédito legal de provedores externos.
 * Utilizado por aplicações clientes (web/mobile) para renderizar menções obrigatórias de licença
 * sem acoplamento a modelos proprietários.
 */
public record PlaceAttributionResponse(
        String provider,
        String providerUri
) {
    public static PlaceAttributionResponse fromDomain(PlaceAttribution attribution) {
        if (attribution == null) {
            return null;
        }
        return new PlaceAttributionResponse(attribution.provider(), attribution.providerUri());
    }
}
