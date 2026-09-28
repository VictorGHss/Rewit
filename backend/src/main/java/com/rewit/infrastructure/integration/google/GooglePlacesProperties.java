package com.rewit.infrastructure.integration.google;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Propriedades de configuração para integração com Google Places API (New).
 */
@Component
@ConfigurationProperties(prefix = "rewit.google.places")
public class GooglePlacesProperties {

    private String apiKey = "";
    private String baseUrl = "https://places.googleapis.com";
    private int connectTimeoutMs = 3000;
    private int readTimeoutMs = 5000;

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank() && !"change-me".equalsIgnoreCase(apiKey.trim());
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey != null ? apiKey.trim() : "";
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl != null ? baseUrl.trim() : "https://places.googleapis.com";
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public void setReadTimeoutMs(int readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
    }
}
