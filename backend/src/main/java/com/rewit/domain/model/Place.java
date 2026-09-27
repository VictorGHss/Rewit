package com.rewit.domain.model;

import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.TargetType;

import java.time.Instant;
import java.util.UUID;

/**
 * Entidade de Domínio representando um local físico geolocalizado (Place).
 * Herda a identidade relacional de RateableTarget (ADR-009).
 */
public class Place extends RateableTarget {

    private final String name;
    private final String slug;
    private final String category;
    private final String description;
    private final String addressText;
    private final String streetNumber;
    private final String neighborhood;
    private final String city;
    private final String state;
    private final String country;
    private final double latitude;
    private final double longitude;
    private final int validationRadiusMeters;
    private final String origin;
    private boolean isVerified;
    private UUID claimedByBusinessId;
    private String status;
    private final Instant updatedAt;

    public Place(UUID id, String name, String slug, String category, String description,
                 String addressText, String streetNumber, String neighborhood,
                 String city, String state, String country,
                 double latitude, double longitude, int validationRadiusMeters,
                 String origin, boolean isVerified, UUID claimedByBusinessId, String status) {
        super(id, TargetType.PLACE);

        if (name == null || name.isBlank()) {
            throw new BusinessException("O nome do local é obrigatório", "INVALID_PLACE_NAME");
        }
        if (slug == null || slug.isBlank()) {
            throw new BusinessException("O slug do local é obrigatório", "INVALID_PLACE_SLUG");
        }
        if (latitude < -90.0 || latitude > 90.0 || longitude < -180.0 || longitude > 180.0) {
            throw new BusinessException("Coordenadas geográficas fora do elipsoide WGS 84", "INVALID_COORDINATES");
        }
        if (validationRadiusMeters <= 0) {
            throw new BusinessException("O raio de tolerância deve ser estritamente positivo", "INVALID_RADIUS");
        }

        this.name = name.trim();
        this.slug = slug.trim().toLowerCase();
        this.category = category != null ? category.trim() : "GERAL";
        this.description = description;
        this.addressText = addressText != null ? addressText.trim() : "";
        this.streetNumber = streetNumber != null ? streetNumber.trim() : null;
        this.neighborhood = neighborhood != null ? neighborhood.trim() : null;
        this.city = city != null ? city.trim() : "";
        this.state = state != null ? state.trim() : "";
        this.country = country != null ? country.trim() : "BR";
        this.latitude = latitude;
        this.longitude = longitude;
        this.validationRadiusMeters = validationRadiusMeters;
        this.origin = origin != null ? origin : "USER";
        this.isVerified = isVerified;
        this.claimedByBusinessId = claimedByBusinessId;
        this.status = status != null ? status : "ACTIVE";
        this.updatedAt = Instant.now();
    }

    /**
     * Construtor de compatibilidade para chamadas sem especificação de número e bairro.
     */
    public Place(UUID id, String name, String slug, String category, String description,
                 String addressText, String city, String state, String country,
                 double latitude, double longitude, int validationRadiusMeters,
                 String origin, boolean isVerified, UUID claimedByBusinessId, String status) {
        this(id, name, slug, category, description, addressText, null, null,
                city, state, country, latitude, longitude, validationRadiusMeters,
                origin, isVerified, claimedByBusinessId, status);
    }

    public String getName() {
        return name;
    }

    public String getSlug() {
        return slug;
    }

    public String getCategory() {
        return category;
    }

    public String getDescription() {
        return description;
    }

    public String getAddressText() {
        return addressText;
    }

    public String getStreetNumber() {
        return streetNumber;
    }

    public String getNeighborhood() {
        return neighborhood;
    }

    public String getCity() {
        return city;
    }

    public String getState() {
        return state;
    }

    public String getCountry() {
        return country;
    }

    public double getLatitude() {
        return latitude;
    }

    public double getLongitude() {
        return longitude;
    }

    public int getValidationRadiusMeters() {
        return validationRadiusMeters;
    }

    public String getOrigin() {
        return origin;
    }

    public boolean isVerified() {
        return isVerified;
    }

    public UUID getClaimedByBusinessId() {
        return claimedByBusinessId;
    }

    public String getStatus() {
        return status;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
