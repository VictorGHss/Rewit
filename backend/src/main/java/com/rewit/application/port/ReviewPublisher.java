package com.rewit.application.port;

/**
 * Porta de aplicação para redirecionamento assistido para fluxos oficiais de avaliação externa.
 */
public interface ReviewPublisher {

    String getOfficialReviewFormUrl(String externalPlaceId);
}
