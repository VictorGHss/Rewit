package com.rewit.domain.enums;

/**
 * Status de verificação de presença física de um check-in.
 */
public enum CheckInStatus {
    VERIFIED_ON_SITE,   // Presença validada com sucesso pelo PostGIS
    REJECTED_DISTANCE,  // Coordenadas além do raio de tolerância do local
    LOCATION_UNAVAILABLE // Avaliação submetida sem dados de GPS
}
