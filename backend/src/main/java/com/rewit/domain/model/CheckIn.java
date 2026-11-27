package com.rewit.domain.model;

import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.CheckInStatus;
import com.rewit.domain.enums.PresenceVerificationOutcome;
import com.rewit.domain.enums.VerificationMethod;

import java.time.Instant;
import java.util.UUID;

/**
 * Entidade de Domínio representando o registro de verificação de presença física vinculada a uma avaliação.
 * Indissociável da entidade Review (Seção 19).
 *
 * Invariantes essenciais:
 * 1. Novo CheckIn inicia como PENDING por padrão (nunca VERIFIED por default).
 * 2. verifiedAt deve ser estritamente null enquanto o status não for VERIFIED.
 * 3. verifiedAt é preenchido exclusivamente no momento em que transiciona para VERIFIED.
 * 4. Um CheckIn REJECTED ou PENDING não pode possuir verifiedAt.
 * 5. Coordenadas persistidas podem estar ausentes (ambas null); quando informadas transitoriamente,
 *    latitude deve estar em [-90, 90] e longitude em [-180, 180].
 * 6. Apenas check-ins VERIFIED podem possuir distanceToCentroidMeters (quando presente, >= 0 e finita);
 *    PENDING e REJECTED devem manter distanceToCentroidMeters estritamente null.
 * 7. rejectionReason só é admitido para status REJECTED, limitado aos motivos reais de
 *    {@link PresenceVerificationOutcome}.
 * 8. check_in.review_id = review.id, check_in.user_id = review.user_id, check_in.place_id = review.context_place_id.
 */
public class CheckIn {

    private static final double EARTH_RADIUS_METERS = 6_371_000.0;

    private final UUID id;
    private final UUID reviewId;
    private final UUID userId;
    private final UUID placeId;
    private final Double latitude;
    private final Double longitude;
    private Double distanceToCentroidMeters;
    private CheckInStatus status;
    private VerificationMethod verificationMethod;
    private Instant verifiedAt;
    private PresenceVerificationOutcome rejectionReason;

    /**
     * Construtor completo com controle explícito de status, timestamp de verificação e motivo de rejeição.
     */
    public CheckIn(UUID id, UUID reviewId, UUID userId, UUID placeId,
                   Double latitude, Double longitude, Double distanceToCentroidMeters,
                   CheckInStatus status, VerificationMethod verificationMethod,
                   Instant verifiedAt, PresenceVerificationOutcome rejectionReason) {
        if (reviewId == null) {
            throw new BusinessException("Check-in exige uma avaliação associada", "MISSING_REVIEW_ID");
        }
        if (userId == null) {
            throw new BusinessException("O usuário é obrigatório", "MISSING_USER_ID");
        }
        if (placeId == null) {
            throw new BusinessException("O local de contexto do check-in é obrigatório", "MISSING_PLACE_ID");
        }
        if ((latitude == null) != (longitude == null)) {
            throw new BusinessException("Latitude e longitude devem ser ambas informadas ou ambas nulas", "INCOMPLETE_COORDINATES");
        }
        if (latitude != null && (!Double.isFinite(latitude) || latitude < -90.0 || latitude > 90.0)) {
            throw new BusinessException("Latitude inválida para o check-in", "INVALID_LATITUDE");
        }
        if (longitude != null && (!Double.isFinite(longitude) || longitude < -180.0 || longitude > 180.0)) {
            throw new BusinessException("Longitude inválida para o check-in", "INVALID_LONGITUDE");
        }
        if (distanceToCentroidMeters != null && (!Double.isFinite(distanceToCentroidMeters) || distanceToCentroidMeters < 0)) {
            throw new BusinessException("A distância não pode ser negativa", "INVALID_DISTANCE");
        }

        CheckInStatus resolvedStatus = status != null ? status : CheckInStatus.PENDING;
        validateRejectionReason(resolvedStatus, rejectionReason);

        this.id = id != null ? id : UUID.randomUUID();
        this.reviewId = reviewId;
        this.userId = userId;
        this.placeId = placeId;
        this.latitude = latitude;
        this.longitude = longitude;
        this.distanceToCentroidMeters = distanceToCentroidMeters;
        this.status = resolvedStatus;
        this.verificationMethod = verificationMethod != null ? verificationMethod : VerificationMethod.GPS;
        this.rejectionReason = rejectionReason;

        if (this.status == CheckInStatus.VERIFIED) {
            this.verifiedAt = verifiedAt != null ? verifiedAt : Instant.now();
        } else {
            if (verifiedAt != null) {
                throw new BusinessException("Check-in pendente ou rejeitado não pode possuir verifiedAt", "INVALID_VERIFIED_AT");
            }
            this.verifiedAt = null;
        }
        if (this.status != CheckInStatus.VERIFIED && this.distanceToCentroidMeters != null) {
            throw new BusinessException(
                    "Apenas check-ins verificados podem manter distância até o centróide",
                    "NON_VERIFIED_CHECKIN_DISTANCE_FORBIDDEN");
        }
    }

    /**
     * Construtor completo sem motivo de rejeição (reconstituição/persistência ou criação sem motivo).
     */
    public CheckIn(UUID id, UUID reviewId, UUID userId, UUID placeId,
                   Double latitude, Double longitude, Double distanceToCentroidMeters,
                   CheckInStatus status, VerificationMethod verificationMethod, Instant verifiedAt) {
        this(id, reviewId, userId, placeId, latitude, longitude, distanceToCentroidMeters,
                status, verificationMethod, verifiedAt, null);
    }

    /**
     * Construtor com status e método de verificação (compatibilidade).
     */
    public CheckIn(UUID id, UUID reviewId, UUID userId, UUID placeId,
                   Double latitude, Double longitude, Double distanceToCentroidMeters,
                   CheckInStatus status, VerificationMethod verificationMethod) {
        this(id, reviewId, userId, placeId, latitude, longitude, distanceToCentroidMeters, status, verificationMethod,
             (status == CheckInStatus.VERIFIED ? Instant.now() : null), null);
    }

    /**
     * Construtor de conveniência com método GPS padrão (compatibilidade).
     */
    public CheckIn(UUID id, UUID reviewId, UUID userId, UUID placeId,
                   Double latitude, Double longitude, Double distanceToCentroidMeters,
                   CheckInStatus status) {
        this(id, reviewId, userId, placeId, latitude, longitude, distanceToCentroidMeters, status, VerificationMethod.GPS);
    }

    /**
     * Construtor para novo CheckIn vinculado a uma Review (inicia sempre como PENDING).
     */
    public CheckIn(UUID id, Review review,
                   Double latitude, Double longitude, Double distanceToCentroidMeters,
                   VerificationMethod verificationMethod) {
        this(id,
             review != null ? review.getId() : null,
             review != null ? review.getUserId() : null,
             review != null ? review.getContextPlaceId() : null,
             latitude, longitude, distanceToCentroidMeters,
             CheckInStatus.PENDING, verificationMethod, null, null);
        if (review != null && review.getContextPlaceId() == null) {
            throw new BusinessException("Check-in não é permitido para uma avaliação sem local de contexto", "CHECKIN_REQUIRES_CONTEXT_PLACE");
        }
    }

    private static void validateRejectionReason(CheckInStatus status, PresenceVerificationOutcome reason) {
        if (reason == null) {
            return;
        }
        if (status != CheckInStatus.REJECTED) {
            throw new BusinessException("Apenas check-ins rejeitados podem possuir motivo de rejeição", "INVALID_REJECTION_REASON");
        }
        if (!isSupportedRejectionReason(reason)) {
            throw new BusinessException("Motivo de rejeição inválido para check-in: " + reason, "INVALID_REJECTION_REASON");
        }
    }

    public static boolean isSupportedRejectionReason(PresenceVerificationOutcome outcome) {
        return outcome == PresenceVerificationOutcome.ACCURACY_UNAVAILABLE
                || outcome == PresenceVerificationOutcome.ACCURACY_TOO_LOW
                || outcome == PresenceVerificationOutcome.OUTSIDE_RADIUS
                || outcome == PresenceVerificationOutcome.RECENT_CHECK_IN;
    }

    /**
     * Transição de domínio explícita: efetua a verificação de presença física.
     */
    public void verify() {
        verify(Instant.now());
    }

    /**
     * Transição de domínio explícita com timestamp especificado de verificação.
     */
    public void verify(Instant verifiedInstant) {
        verify(verifiedInstant, this.distanceToCentroidMeters);
    }

    /**
     * Transição de domínio explícita com timestamp e distância medida dentro do raio de verificação.
     */
    public void verify(Instant verifiedInstant, Double distanceToCentroidMeters) {
        if (distanceToCentroidMeters != null && (!Double.isFinite(distanceToCentroidMeters) || distanceToCentroidMeters < 0)) {
            throw new BusinessException("A distância não pode ser negativa", "INVALID_DISTANCE");
        }
        this.status = CheckInStatus.VERIFIED;
        this.verifiedAt = verifiedInstant != null ? verifiedInstant : Instant.now();
        this.distanceToCentroidMeters = distanceToCentroidMeters;
        this.rejectionReason = null;
    }

    /**
     * Transição de domínio explícita: rejeita o check-in sem motivo especificado.
     */
    public void reject() {
        reject(null);
    }

    /**
     * Transição de domínio explícita: rejeita o check-in com motivo real de {@link PresenceVerificationOutcome},
     * descartando qualquer distância transitória associada.
     */
    public void reject(PresenceVerificationOutcome reason) {
        validateRejectionReason(CheckInStatus.REJECTED, reason);
        this.status = CheckInStatus.REJECTED;
        this.verifiedAt = null;
        this.distanceToCentroidMeters = null;
        this.rejectionReason = reason;
    }

    public boolean hasCoordinates() {
        return latitude != null && longitude != null;
    }

    /**
     * Calcula a distância geodésica aproximada (Haversine) até um par de coordenadas alvo somente quando há
     * coordenadas transitórias válidas presentes neste CheckIn; recusa operar sobre coordenadas ausentes.
     */
    public double distanceMetersTo(Double targetLatitude, Double targetLongitude) {
        if (!hasCoordinates()) {
            throw new BusinessException("Coordenadas do check-in indisponíveis para cálculo geográfico",
                    "CHECKIN_COORDINATES_UNAVAILABLE");
        }
        if (targetLatitude == null || targetLongitude == null
                || !Double.isFinite(targetLatitude) || !Double.isFinite(targetLongitude)
                || targetLatitude < -90.0 || targetLatitude > 90.0
                || targetLongitude < -180.0 || targetLongitude > 180.0) {
            throw new BusinessException("Coordenadas de destino inválidas ou indisponíveis para cálculo geográfico",
                    "INVALID_TARGET_COORDINATES");
        }
        double dLat = Math.toRadians(targetLatitude - this.latitude);
        double dLon = Math.toRadians(targetLongitude - this.longitude);
        double lat1 = Math.toRadians(this.latitude);
        double lat2 = Math.toRadians(targetLatitude);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_METERS * c;
    }

    /**
     * Calcula a distância geodésica aproximada até o local informado, exigindo coordenadas transitórias válidas
     * tanto no check-in quanto no local.
     */
    public double distanceMetersTo(Place place) {
        if (place == null || !place.hasCoordinates()) {
            throw new BusinessException("O local não possui coordenadas válidas para cálculo geográfico",
                    "PLACE_COORDINATES_UNAVAILABLE");
        }
        return distanceMetersTo(place.getLatitude(), place.getLongitude());
    }

    /**
     * Valida consistência de integridade relacional entre o CheckIn e a Review vinculada.
     */
    public void validateConsistencyWith(Review review) {
        if (review == null) {
            throw new BusinessException("A avaliação vinculada é obrigatória", "MISSING_REVIEW");
        }
        if (!this.reviewId.equals(review.getId())) {
            throw new BusinessException("O review_id do check-in não coincide com o id da avaliação", "INCONSISTENT_CHECKIN_REVIEW");
        }
        if (!this.userId.equals(review.getUserId())) {
            throw new BusinessException("O user_id do check-in não coincide com o autor da avaliação", "INCONSISTENT_CHECKIN_USER");
        }
        if (review.getContextPlaceId() == null) {
            throw new BusinessException("Check-in não é permitido para uma avaliação sem context_place_id", "CHECKIN_REQUIRES_CONTEXT_PLACE");
        }
        if (!this.placeId.equals(review.getContextPlaceId())) {
            throw new BusinessException("O place_id do check-in não coincide com o context_place_id da avaliação", "INCONSISTENT_CHECKIN_PLACE");
        }
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

    public Double getLatitude() {
        return latitude;
    }

    public Double getLongitude() {
        return longitude;
    }

    public Double getDistanceToCentroidMeters() {
        return distanceToCentroidMeters;
    }

    public CheckInStatus getStatus() {
        return status;
    }

    public VerificationMethod getVerificationMethod() {
        return verificationMethod;
    }

    public Instant getVerifiedAt() {
        return verifiedAt;
    }

    public PresenceVerificationOutcome getRejectionReason() {
        return rejectionReason;
    }
}
