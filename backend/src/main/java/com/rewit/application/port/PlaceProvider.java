package com.rewit.application.port;

import java.util.List;

/**
 * Porta de aplicação para descoberta de locais em fontes externas (bootstrap).
 */
public interface PlaceProvider {

    record ExternalPlaceSuggestion(
            String externalId,
            String providerName,
            String name,
            double latitude,
            double longitude,
            String addressText
    ) {}

    List<ExternalPlaceSuggestion> searchNearby(double latitude, double longitude, int radiusMeters);
}
