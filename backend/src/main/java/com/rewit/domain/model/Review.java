package com.rewit.domain.model;

import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.CheckInStatus;
import com.rewit.domain.enums.ReviewStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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
    private String experienceText;
    private boolean isAnonymous;
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

    public void markUnderReview() {
        if (this.status != ReviewStatus.ACTIVE) {
            throw new BusinessException("Apenas avaliações ativas podem ser colocadas sob moderação", "INVALID_REVIEW_STATUS_TRANSITION");
        }
        this.status = ReviewStatus.UNDER_REVIEW;
        this.updatedAt = Instant.now();
    }

    /**
     * Transiciona o status da avaliação para REMOVED quando solicitado pelo autor (Soft Delete).
     * Permite transição a partir de ACTIVE ou UNDER_REVIEW.
     * Rejeita se já estiver REMOVED ou em estado inconsistente.
     *
     * @param now instante explícito da mutação
     */
    public void markRemovedByAuthor(Instant now) {
        validateTimestamp(now);
        if (this.status == ReviewStatus.REMOVED) {
            throw new BusinessException("A avaliação já se encontra removida", "REVIEW_ALREADY_REMOVED");
        }
        if (this.status != ReviewStatus.ACTIVE && this.status != ReviewStatus.UNDER_REVIEW) {
            throw new BusinessException("Transição de estado inválida para remoção", "INVALID_REVIEW_STATUS_TRANSITION");
        }
        this.status = ReviewStatus.REMOVED;
        this.updatedAt = now;
    }

    /**
     * Edição controlada de propriedades editáveis pelo autor (texto, notas dos alvos existentes, anonimato e visibilidade).
     * Rejeita se a avaliação não estiver ACTIVE (ex: REMOVED ou UNDER_REVIEW).
     * Rejeita targetId não associado a esta avaliação (targets são estruturalmente imutáveis).
     *
     * @param experienceText novo texto de experiência
     * @param targetRatings mapa de targetId para nova nota (pode ser nulo ou vazio)
     * @param isAnonymous novo valor de anonimato (se nulo, mantém o atual)
     * @param visibility novo valor de visibilidade (se nulo, mantém o atual)
     * @param now timestamp explícito da mutação
     */
    public void editContent(String experienceText,
                            Map<UUID, BigDecimal> targetRatings,
                            Boolean isAnonymous,
                            String visibility,
                            Instant now) {
        validateActiveForEdit();
        validateTimestamp(now);

        this.experienceText = experienceText;

        if (targetRatings != null && !targetRatings.isEmpty()) {
            for (Map.Entry<UUID, BigDecimal> entry : targetRatings.entrySet()) {
                UUID targetId = entry.getKey();
                BigDecimal newRating = entry.getValue();

                if (targetId == null) {
                    throw new BusinessException("O identificador do alvo avaliado é obrigatório", "MISSING_TARGET_ID");
                }

                ReviewTarget target = this.targets.stream()
                        .filter(t -> t.getTargetId().equals(targetId))
                        .findFirst()
                        .orElseThrow(() -> new BusinessException("Alvo avaliado não pertence a esta avaliação", "TARGET_NOT_FOUND"));

                target.updateRating(newRating);
            }
        }

        if (isAnonymous != null) {
            this.isAnonymous = isAnonymous;
        }

        if (visibility != null) {
            this.visibility = normalizeAndValidateVisibility(visibility);
        }

        this.updatedAt = now;
    }

    /**
     * Atualiza o texto da experiência da avaliação de forma controlada.
     */
    public void updateExperienceText(String experienceText, Instant now) {
        validateActiveForEdit();
        validateTimestamp(now);
        this.experienceText = experienceText;
        this.updatedAt = now;
    }

    /**
     * Atualiza a nota de um alvo avaliado específico pertencente a esta publicação.
     */
    public void updateTargetRating(UUID targetId, BigDecimal newRating, Instant now) {
        validateActiveForEdit();
        validateTimestamp(now);

        if (targetId == null) {
            throw new BusinessException("O identificador do alvo avaliado é obrigatório", "MISSING_TARGET_ID");
        }

        ReviewTarget target = this.targets.stream()
                .filter(t -> t.getTargetId().equals(targetId))
                .findFirst()
                .orElseThrow(() -> new BusinessException("Alvo avaliado não pertence a esta avaliação", "TARGET_NOT_FOUND"));

        target.updateRating(newRating);
        this.updatedAt = now;
    }

    /**
     * Atualiza a opção de anonimato da publicação.
     */
    public void updateAnonymous(boolean isAnonymous, Instant now) {
        validateActiveForEdit();
        validateTimestamp(now);
        this.isAnonymous = isAnonymous;
        this.updatedAt = now;
    }

    /**
     * Atualiza a visibilidade da publicação.
     */
    public void updateVisibility(String visibility, Instant now) {
        validateActiveForEdit();
        validateTimestamp(now);
        this.visibility = normalizeAndValidateVisibility(visibility);
        this.updatedAt = now;
    }

    private void validateActiveForEdit() {
        if (this.status == ReviewStatus.REMOVED) {
            throw new BusinessException("Avaliações removidas não podem ser editadas", "REVIEW_ALREADY_REMOVED");
        }
        if (this.status != ReviewStatus.ACTIVE) {
            throw new BusinessException("Apenas avaliações ativas podem ser editadas", "INVALID_REVIEW_STATUS_FOR_EDIT");
        }
    }

    private static void validateTimestamp(Instant timestamp) {
        if (timestamp == null) {
            throw new BusinessException("O timestamp de atualização é obrigatório", "MISSING_UPDATE_TIMESTAMP");
        }
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
