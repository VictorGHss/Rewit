package com.rewit.domain.model;

import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.CheckInStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Entidade de Domínio representando a confirmação de presença física verificada durante uma avaliação.
 * Indissociável da entidade Review (Seção 19).
 */
public class CheckIn {

    private final UUID id;
    private final UUID reviewId;
    private final UUID userId;
    private final UUID placeId;
    private final double latitude;
    private final double longitude;
    private final double distanceToCentroidMeters;
    private CheckInStatus status;
    private final Instant verifiedAt;

    public CheckIn(UUID id, UUID reviewId, UUID userId, UUID placeId,
                   double latitude, double longitude, double distanceToCentroidMeters,
                   CheckInStatus status) {
        if (reviewId == null) {
            throw new BusinessException("Check-in exige uma avaliação associada", "MISSING_REVIEW_ID");
        }
        if (userId == null) {
            throw new BusinessException("O usuário é obrigatório", "MISSING_USER_ID");
        }
        if (placeId == null) {
            throw new BusinessException("O local de contexto do check-in é obrigatório", "MISSING_PLACE_ID");
        }
        if (distanceToCentroidMeters < 0) {
            throw new BusinessException("A distância não pode ser negativa", "INVALID_DISTANCE");
        }

        this.id = id != null ? id : UUID.randomUUID();
        this.reviewId = reviewId;
        this.userId = userId;
        this.placeId = placeId;
        this.latitude = latitude;
        this.longitude = longitude;
        this.distanceToCentroidMeters = distanceToCentroidMeters;
        this.status = status != null ? status : CheckInStatus.VERIFIED;
        this.verifiedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getReviewId() {
        return reviewId;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getPlaceId() {
        return placeId;
    }

    public double getLatitude() {
        return latitude;
    }

    public double getLongitude() {
        return longitude;
    }

    public double getDistanceToCentroidMeters() {
        return distanceToCentroidMeters;
    }

    public CheckInStatus getStatus() {
        return status;
    }

    public Instant getVerifiedAt() {
        return verifiedAt;
    }
}
