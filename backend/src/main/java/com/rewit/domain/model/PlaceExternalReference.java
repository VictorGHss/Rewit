package com.rewit.domain.model;

import com.rewit.common.exception.BusinessException;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * Entidade de Domínio que encapsula identificadores e referências de provedores externos (ex: Google Places).
 * Previne que identificadores externos fiquem espalhados pelo modelo de domínio principal.
 */
public class PlaceExternalReference {

    private final UUID id;
    private final UUID placeId;
    private final String provider;
    private final String externalId;
    private final String metadataJson;
    private final Instant createdAt;

    public PlaceExternalReference(UUID id, UUID placeId, String provider, String externalId, String metadataJson) {
        if (placeId == null) {
            throw new BusinessException("O identificador do local é obrigatório", "MISSING_PLACE_ID");
        }
        if (provider == null || provider.isBlank()) {
            throw new BusinessException("O provedor da referência externa é obrigatório", "MISSING_PROVIDER");
        }
        if (externalId == null || externalId.isBlank()) {
            throw new BusinessException("O identificador externo é obrigatório", "MISSING_EXTERNAL_ID");
        }

        this.id = id != null ? id : UUID.randomUUID();
        this.placeId = placeId;
        this.provider = provider.trim().toUpperCase(Locale.ROOT);
        this.externalId = externalId.trim();
        this.metadataJson = metadataJson;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getPlaceId() {
        return placeId;
    }

    public String getProvider() {
        return provider;
    }

    public String getExternalId() {
        return externalId;
    }

    public String getMetadataJson() {
        return metadataJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
