package com.rewit.infrastructure.integration.google;

import com.rewit.application.port.PlaceProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * Adaptador de infraestrutura implementando a porta PlaceProvider via Google Places API.
 * Respeita a política de não-espelhamento indiscriminado da base externa.
 */
@Component
public class GooglePlacesAdapter implements PlaceProvider {

    private static final Logger log = LoggerFactory.getLogger(GooglePlacesAdapter.class);

    @Override
    public List<ExternalPlaceSuggestion> searchNearby(double latitude, double longitude, int radiusMeters) {
        log.debug("PlaceProvider.searchNearby chamado para lat={}, lng={}, radius={}", latitude, longitude, radiusMeters);
        return Collections.emptyList();
    }
}
