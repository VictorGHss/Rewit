package com.rewit.domain.model;

import com.rewit.common.exception.BusinessException;
import org.springframework.http.HttpStatus;

import java.util.Collections;
import java.util.List;

/**
 * Modelo de domínio puro representando um candidato externo de local (PlaceCandidate).
 * O candidato é transitório e representa a resposta de descoberta (Google Places New API)
 * antes de qualquer decisão de persistência ou importação no catálogo Rewit.
 */
public class PlaceCandidate {

    private final String provider;
    private final String externalId;
    private final String displayName;
    private final String formattedAddress;
    private final Double latitude;
    private final Double longitude;
    private final List<String> types;
    private final List<PlaceAttribution> attributions;

    public PlaceCandidate(
            String provider,
            String externalId,
            String displayName,
            String formattedAddress,
            Double latitude,
            Double longitude,
            List<String> types
    ) {
        this(provider, externalId, displayName, formattedAddress, latitude, longitude, types, Collections.emptyList());
    }

    public PlaceCandidate(
            String provider,
            String externalId,
            String displayName,
            String formattedAddress,
            Double latitude,
            Double longitude,
            List<String> types,
            List<PlaceAttribution> attributions
    ) {
        if (provider == null || provider.isBlank()) {
            throw new BusinessException("O provedor externo é obrigatório", HttpStatus.BAD_REQUEST, "INVALID_PROVIDER");
        }
        if (externalId == null || externalId.isBlank()) {
            throw new BusinessException("O identificador externo do local é obrigatório", HttpStatus.BAD_REQUEST, "INVALID_EXTERNAL_ID");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new BusinessException("O nome de exibição do local é obrigatório", HttpStatus.BAD_REQUEST, "INVALID_DISPLAY_NAME");
        }
        if (latitude != null && (latitude < -90.0 || latitude > 90.0)) {
            throw new BusinessException("Latitude fora do elipsoide WGS 84 (-90 a 90)", HttpStatus.BAD_REQUEST, "INVALID_COORDINATES");
        }
        if (longitude != null && (longitude < -180.0 || longitude > 180.0)) {
            throw new BusinessException("Longitude fora do elipsoide WGS 84 (-180 a 180)", HttpStatus.BAD_REQUEST, "INVALID_COORDINATES");
        }

        this.provider = provider.trim().toUpperCase();
        this.externalId = externalId.trim();
        this.displayName = displayName.trim();
        this.formattedAddress = formattedAddress != null ? formattedAddress.trim() : "";
        this.latitude = latitude;
        this.longitude = longitude;
        this.types = types != null ? List.copyOf(types) : Collections.emptyList();
        this.attributions = attributions != null ? List.copyOf(attributions) : Collections.emptyList();
    }

    public String getProvider() {
        return provider;
    }

    public String getExternalId() {
        return externalId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getFormattedAddress() {
        return formattedAddress;
    }

    public Double getLatitude() {
        return latitude;
    }

    public Double getLongitude() {
        return longitude;
    }

    public List<String> getTypes() {
        return types;
    }

    public List<PlaceAttribution> getAttributions() {
        return attributions;
    }
}
