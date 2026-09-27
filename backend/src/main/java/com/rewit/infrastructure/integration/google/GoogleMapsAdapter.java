package com.rewit.infrastructure.integration.google;

import com.rewit.application.port.MapProvider;
import org.springframework.stereotype.Component;

/**
 * Adaptador de infraestrutura implementando a porta MapProvider para links de navegação.
 */
@Component
public class GoogleMapsAdapter implements MapProvider {

    @Override
    public String generateNavigationUrl(double latitude, double longitude) {
        return String.format("https://www.google.com/maps/search/?api=1&query=%f,%f", latitude, longitude);
    }
}
