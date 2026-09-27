package com.rewit.infrastructure.integration.google;

import com.rewit.application.port.ReviewPublisher;
import org.springframework.stereotype.Component;

/**
 * Adaptador de infraestrutura implementando a porta ReviewPublisher para redirecionamento assistido.
 */
@Component
public class GoogleReviewRedirectAdapter implements ReviewPublisher {

    @Override
    public String getOfficialReviewFormUrl(String externalPlaceId) {
        if (externalPlaceId == null || externalPlaceId.isBlank()) {
            return null;
        }
        return "https://search.google.com/local/writereview?placeid=" + externalPlaceId;
    }
}
