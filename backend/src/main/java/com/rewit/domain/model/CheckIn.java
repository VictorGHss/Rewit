package com.rewit.domain.model;

import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.CheckInStatus;
import com.rewit.domain.enums.VerificationMethod;

import java.time.Instant;
import java.util.UUID;

/**
 * Entidade de Domínio representando a confirmação de presença física verificada durante uma avaliação.
 * Indissociável da entidade Review (Seção 19).
 *
 * Invariantes essenciais:
 * 1. Novo CheckIn inicia como PENDING por padrão (nunca VERIFIED por default).
 * 2. verifiedAt deve ser estritamente null enquanto o status não for VERIFIED.
 * 3. verifiedAt é preenchido exclusivamente no momento em que transiciona para VERIFIED.
 * 4. Um CheckIn REJECTED ou PENDING não pode possuir verifiedAt.
 * 5. Latitude entre -90 e 90, Longitude entre -180 e 180, Distância >= 0.
 * 6. check_in.review_id = review.id, check_in.user_id = review.user_id, check_in.place_id = review.context_place_id.
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
    private VerificationMethod verificationMethod;
    private Instant verifiedAt;

    /**
     * Construtor completo com controle explícito de status e timestamp de verificação (reconstituição/persistência).
     */
    public CheckIn(UUID id, UUID reviewId, UUID userId, UUID placeId,
                   double latitude, double longitude, double distanceToCentroidMeters,
                   CheckInStatus status, VerificationMethod verificationMethod, Instant verifiedAt) {
        if (reviewId == null) {
            throw new BusinessException("Check-in exige uma avaliação associada", "MISSING_REVIEW_ID");
        }
        if (userId == null) {
            throw new BusinessException("O usuário é obrigatório", "MISSING_USER_ID");
        }
        if (placeId == null) {
            throw new BusinessException("O local de contexto do check-in é obrigatório", "MISSING_PLACE_ID");
        }
        if (latitude < -90.0 || latitude > 90.0) {
            throw new BusinessException("Latitude inválida para o check-in", "INVALID_LATITUDE");
        }
        if (longitude < -180.0 || longitude > 180.0) {
            throw new BusinessException("Longitude inválida para o check-in", "INVALID_LONGITUDE");
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
        this.status = status != null ? status : CheckInStatus.PENDING;
        this.verificationMethod = verificationMethod != null ? verificationMethod : VerificationMethod.GPS;

        if (this.status == CheckInStatus.VERIFIED) {
            this.verifiedAt = verifiedAt != null ? verifiedAt : Instant.now();
        } else {
            if (verifiedAt != null) {
                throw new BusinessException("Check-in pendente ou rejeitado não pode possuir verifiedAt", "INVALID_VERIFIED_AT");
            }
            this.verifiedAt = null;
        }
    }

    /**
     * Construtor com status e método de verificação (compatibilidade).
     */
    public CheckIn(UUID id, UUID reviewId, UUID userId, UUID placeId,
                   double latitude, double longitude, double distanceToCentroidMeters,
                   CheckInStatus status, VerificationMethod verificationMethod) {
        this(id, reviewId, userId, placeId, latitude, longitude, distanceToCentroidMeters, status, verificationMethod,
             (status == CheckInStatus.VERIFIED ? Instant.now() : null));
    }

    /**
     * Construtor de conveniência com método GPS padrão (compatibilidade).
     */
    public CheckIn(UUID id, UUID reviewId, UUID userId, UUID placeId,
                   double latitude, double longitude, double distanceToCentroidMeters,
                   CheckInStatus status) {
        this(id, reviewId, userId, placeId, latitude, longitude, distanceToCentroidMeters, status, VerificationMethod.GPS);
    }

    /**
     * Construtor para novo CheckIn vinculado a uma Review (inicia sempre como PENDING).
     */
    public CheckIn(UUID id, Review review,
                   double latitude, double longitude, double distanceToCentroidMeters,
                   VerificationMethod verificationMethod) {
        this(id,
             review != null ? review.getId() : null,
             review != null ? review.getUserId() : null,
             review != null ? review.getContextPlaceId() : null,
             latitude, longitude, distanceToCentroidMeters,
             CheckInStatus.PENDING, verificationMethod, null);
        if (review != null && review.getContextPlaceId() == null) {
            throw new BusinessException("Check-in não é permitido para uma avaliação sem local de contexto", "CHECKIN_REQUIRES_CONTEXT_PLACE");
        }
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
        this.status = CheckInStatus.VERIFIED;
        this.verifiedAt = verifiedInstant != null ? verifiedInstant : Instant.now();
    }

    /**
     * Transição de domínio explícita: rejeita o check-in (ex: coordenadas fora do raio do local).
     */
    public void reject() {
        this.status = CheckInStatus.REJECTED;
        this.verifiedAt = null;
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

    public VerificationMethod getVerificationMethod() {
        return verificationMethod;
    }

    public Instant getVerifiedAt() {
        return verifiedAt;
    }
}
