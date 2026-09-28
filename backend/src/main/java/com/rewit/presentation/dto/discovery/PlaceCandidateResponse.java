package com.rewit.presentation.dto.discovery;

import com.rewit.domain.model.PlaceCandidate;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * DTO de resposta para candidato externo de local retornado em operações de descoberta.
 */
public record PlaceCandidateResponse(
        String provider,
        String externalId,
        String displayName,
        String formattedAddress,
        Double latitude,
        Double longitude,
        List<String> types,
        List<PlaceAttributionResponse> attributions
) {
    public static PlaceCandidateResponse fromDomain(PlaceCandidate candidate) {
        if (candidate == null) {
            return null;
        }

        List<PlaceAttributionResponse> attrResponses = candidate.getAttributions() != null
                ? candidate.getAttributions().stream()
                .filter(Objects::nonNull)
                .map(PlaceAttributionResponse::fromDomain)
                .toList()
                : Collections.emptyList();

        return new PlaceCandidateResponse(
                candidate.getProvider(),
                candidate.getExternalId(),
                candidate.getDisplayName(),
                candidate.getFormattedAddress(),
                candidate.getLatitude(),
                candidate.getLongitude(),
                candidate.getTypes(),
                attrResponses
        );
    }
}
