package com.rewit.application.port;

/**
 * Porta de aplicação para geração de URLs e navegação em mapas externos.
 */
public interface MapProvider {

    String generateNavigationUrl(double latitude, double longitude);
}
