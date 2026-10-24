package com.rewit.infrastructure.integration.geoapify;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Propriedades da integração com o Geoapify (geocoding e place details, dados do OpenStreetMap).
 * A chave fica só no backend ({@code GEOAPIFY_API_KEY}); nunca é enviada ao cliente.
 */
@Component
@ConfigurationProperties(prefix = "rewit.geoapify")
public class GeoapifyProperties {

    private String apiKey = "";
    private String baseUrl = "https://api.geoapify.com";
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
        this.baseUrl = baseUrl != null && !baseUrl.isBlank() ? baseUrl.trim() : "https://api.geoapify.com";
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
