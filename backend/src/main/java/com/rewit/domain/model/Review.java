package com.rewit.domain.model;

import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.ReviewStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Entidade de Domínio representando uma publicação de avaliação no Rewit.
 * Suporta contexto físico opcional (contextPlaceId) e localização sob demanda.
 */
public class Review {

    private final UUID id;
    private final UUID userId;
    private final UUID contextPlaceId;
    private final String experienceText;
    private final boolean isAnonymous;
    private boolean isVerifiedOnSite;
    private final Double userLatitude;
    private final Double userLongitude;
    private final Double locationAccuracyMeters;
    private ReviewStatus status;
    private String visibility;
    private final Instant createdAt;
    private Instant updatedAt;

    public Review(UUID id, UUID userId, UUID contextPlaceId, String experienceText,
                  boolean isAnonymous, boolean isVerifiedOnSite,
                  Double userLatitude, Double userLongitude, Double locationAccuracyMeters) {
        if (userId == null) {
            throw new BusinessException("O autor da avaliação é obrigatório", "MISSING_USER_ID");
        }
        if (userLatitude != null && (userLatitude < -90.0 || userLatitude > 90.0)) {
            throw new BusinessException("Latitude inválida", "INVALID_LATITUDE");
        }
        if (userLongitude != null && (userLongitude < -180.0 || userLongitude > 180.0)) {
            throw new BusinessException("Longitude inválida", "INVALID_LONGITUDE");
        }

        this.id = id != null ? id : UUID.randomUUID();
        this.userId = userId;
        this.contextPlaceId = contextPlaceId;
        this.experienceText = experienceText;
        this.isAnonymous = isAnonymous;
        this.isVerifiedOnSite = isVerifiedOnSite;
        this.userLatitude = userLatitude;
        this.userLongitude = userLongitude;
        this.locationAccuracyMeters = locationAccuracyMeters;
        this.status = ReviewStatus.ACTIVE;
        this.visibility = "PUBLIC";
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void markAsVerifiedOnSite() {
        this.isVerifiedOnSite = true;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getContextPlaceId() {
        return contextPlaceId;
    }

    public String getExperienceText() {
        return experienceText;
    }

    public boolean isAnonymous() {
        return isAnonymous;
    }

    public boolean isVerifiedOnSite() {
        return isVerifiedOnSite;
    }

    public Double getUserLatitude() {
        return userLatitude;
    }

    public Double getUserLongitude() {
        return userLongitude;
    }

    public Double getLocationAccuracyMeters() {
        return locationAccuracyMeters;
    }

    public ReviewStatus getStatus() {
        return status;
    }

    public String getVisibility() {
        return visibility;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
