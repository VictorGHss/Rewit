package com.rewit.domain.model;

import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.CheckInStatus;
import com.rewit.domain.enums.ReviewStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Entidade de Domínio e Raiz de Agregado representando uma publicação de avaliação no Rewit.
 * Suporta contexto físico opcional (contextPlaceId) e localização sob demanda.
 *
 * Invariantes essenciais:
 * 1. Deve possuir pelo menos um alvo avaliado com nota (ReviewTarget).
 * 2. CheckIn.status == VERIFIED é a ÚNICA fonte de verdade para "verificado no local".
 *    A coluna/propriedade isVerifiedOnSite na Review é estritamente uma projeção/cache de leitura,
 *    nunca podendo ser alterada de forma avulsa ou independente de um CheckIn VERIFIED.
 * 3. CheckIn só é admitido quando contextPlaceId for preenchido (não nulo).
 * 4. Validação rigorosa de coordenadas e raio/precisão da localização.
 */
public class Review {

    private final UUID id;
    private final UUID userId;
    private final UUID contextPlaceId;
    private final String experienceText;
    private final boolean isAnonymous;
    private boolean isVerifiedOnSite; // Projeção/cache de leitura sincronizada por CheckIn
    private final Double userLatitude;
    private final Double userLongitude;
    private final Double locationAccuracyMeters;
    private ReviewStatus status;
    private String visibility;
    private final Instant createdAt;
    private Instant updatedAt;

    private final List<ReviewTarget> targets = new ArrayList<>();
    private CheckIn checkIn;

    /**
     * Construtor de criação de nova publicação de avaliação com visibilidade explícita.
     */
    public Review(UUID id, UUID userId, UUID contextPlaceId, String experienceText,
                  boolean isAnonymous, String visibility,
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
        if (locationAccuracyMeters != null && locationAccuracyMeters < 0) {
            throw new BusinessException("A precisão da localização não pode ser negativa", "INVALID_LOCATION_ACCURACY");
        }

        this.id = id != null ? id : UUID.randomUUID();
        this.userId = userId;
        this.contextPlaceId = contextPlaceId;
        this.experienceText = experienceText;
        this.isAnonymous = isAnonymous;
        this.isVerifiedOnSite = false; // Novo review nunca inicia como verificado no local
        this.userLatitude = userLatitude;
        this.userLongitude = userLongitude;
        this.locationAccuracyMeters = locationAccuracyMeters;
        this.status = ReviewStatus.ACTIVE;
        this.visibility = normalizeAndValidateVisibility(visibility);
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    /**
     * Construtor de criação com visibilidade default PUBLIC.
     */
    public Review(UUID id, UUID userId, UUID contextPlaceId, String experienceText,
                  boolean isAnonymous,
                  Double userLatitude, Double userLongitude, Double locationAccuracyMeters) {
        this(id, userId, contextPlaceId, experienceText, isAnonymous, "PUBLIC", userLatitude, userLongitude, locationAccuracyMeters);
    }

    /**
     * Construtor para reconstituição a partir da persistência (cache desnormalizado de leitura).
     * Não deve ser utilizado pelo domínio para aprovação manual arbitrária.
     */
    public Review(UUID id, UUID userId, UUID contextPlaceId, String experienceText,
                  boolean isAnonymous, boolean isVerifiedOnSite,
                  Double userLatitude, Double userLongitude, Double locationAccuracyMeters) {
        this(id, userId, contextPlaceId, experienceText, isAnonymous, userLatitude, userLongitude, locationAccuracyMeters);
        this.isVerifiedOnSite = isVerifiedOnSite;
    }

    /**
     * Construtor completo para reconstituição fidedigna da camada de persistência.
     */
    public Review(UUID id, UUID userId, UUID contextPlaceId, String experienceText,
                  boolean isAnonymous, boolean isVerifiedOnSite,
                  ReviewStatus status, String visibility,
                  Double userLatitude, Double userLongitude, Double locationAccuracyMeters,
                  Instant createdAt, Instant updatedAt) {
        if (userId == null) {
            throw new BusinessException("O autor da avaliação é obrigatório", "MISSING_USER_ID");
        }
        this.id = id != null ? id : UUID.randomUUID();
        this.userId = userId;
        this.contextPlaceId = contextPlaceId;
        this.experienceText = experienceText;
        this.isAnonymous = isAnonymous;
        this.isVerifiedOnSite = isVerifiedOnSite;
        this.status = status != null ? status : ReviewStatus.ACTIVE;
        this.visibility = normalizeAndValidateVisibility(visibility);
        this.userLatitude = userLatitude;
        this.userLongitude = userLongitude;
        this.locationAccuracyMeters = locationAccuracyMeters;
        this.createdAt = createdAt != null ? createdAt : Instant.now();
        this.updatedAt = updatedAt != null ? updatedAt : Instant.now();
    }

    private static String normalizeAndValidateVisibility(String visibility) {
        if (visibility == null || visibility.isBlank()) {
            return "PUBLIC";
        }
        String normalized = visibility.trim().toUpperCase(java.util.Locale.ROOT);
        if (!"PUBLIC".equals(normalized) && !"PRIVATE".equals(normalized) && !"FOLLOWERS".equals(normalized)) {
            throw new BusinessException("Nível de visibilidade inválido", "INVALID_VISIBILITY");
        }
        return normalized;
    }

    /**
     * Adiciona um alvo avaliado com nota ao agregado.
     * Invariante multi-target: o mesmo RateableTarget não pode ser avaliado mais de uma vez na mesma Review.
     */
    public void addTarget(ReviewTarget target) {
        if (target == null) {
            throw new BusinessException("O alvo avaliado (ReviewTarget) é obrigatório", "MISSING_TARGET");
        }
        if (!this.id.equals(target.getReviewId())) {
            throw new BusinessException("O alvo avaliado não pertence a esta Review", "INCONSISTENT_REVIEW_TARGET");
        }
        boolean duplicate = this.targets.stream()
                .anyMatch(t -> t.getTargetId().equals(target.getTargetId()));
        if (duplicate) {
            throw new BusinessException("O mesmo alvo não pode ser avaliado mais de uma vez na mesma publicação", "DUPLICATE_REVIEW_TARGET");
        }
        this.targets.add(target);
        this.updatedAt = Instant.now();
    }

    /**
     * Adiciona alvos durante a reconstituição a partir do repositório sem modificar timestamps.
     */
    public void addRehydratedTarget(ReviewTarget target) {
        if (target != null && this.id.equals(target.getReviewId())) {
            this.targets.add(target);
        }
    }

    /**
     * Valida que a publicação possui pelo menos um alvo avaliado com nota (ReviewTarget).
     * Review = publicação contendo pelo menos uma avaliação em estrelas.
     */
    public void validateHasAtLeastOneTarget() {
        if (this.targets == null || this.targets.isEmpty()) {
            throw new BusinessException("Uma Review deve possuir pelo menos um alvo avaliado (ReviewTarget)", "REVIEW_WITHOUT_TARGET");
        }
    }

    /**
     * Vincula o CheckIn à Review e sincroniza a projeção de presença no local.
     * CheckIn.status == VERIFIED é a única fonte de verdade.
     */
    public void attachCheckIn(CheckIn checkIn) {
        if (checkIn == null) {
            this.checkIn = null;
            this.isVerifiedOnSite = false;
            this.updatedAt = Instant.now();
            return;
        }

        checkIn.validateConsistencyWith(this);
        this.checkIn = checkIn;
        this.isVerifiedOnSite = (checkIn.getStatus() == CheckInStatus.VERIFIED);
        this.updatedAt = Instant.now();
    }

    public List<ReviewTarget> getTargets() {
        return Collections.unmodifiableList(targets);
    }

    public CheckIn getCheckIn() {
        return checkIn;
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

    /**
     * Fonte da verdade: deriva prioritariamente do CheckIn vinculado no agregado quando presente.
     * Caso contrário, reflete a projeção persistida sincronizada.
     */
    public boolean isVerifiedOnSite() {
        if (this.checkIn != null) {
            return this.checkIn.getStatus() == CheckInStatus.VERIFIED;
        }
        return this.isVerifiedOnSite;
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
