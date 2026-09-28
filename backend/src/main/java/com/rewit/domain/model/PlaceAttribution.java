package com.rewit.domain.model;

/**
 * Representação agnóstica e imutável de atribuição legal de dados de provedores externos.
 * Utilizada para exibir créditos e termos de licença exigidos por provedores (ex: Google Places API New)
 * sem vazar classes de transporte proprietárias para o domínio ou clientes da API.
 */
public record PlaceAttribution(
        String provider,
        String providerUri
) {
    public PlaceAttribution {
        provider = provider != null ? provider.trim() : "";
        providerUri = providerUri != null ? providerUri.trim() : "";
    }
}
