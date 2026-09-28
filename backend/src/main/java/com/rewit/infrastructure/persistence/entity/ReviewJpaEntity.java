package com.rewit.infrastructure.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Entidade JPA mapeando a tabela reviews no PostgreSQL.
 * Valida com o schema gerado pela migration Flyway V1.
 */
@Entity
@Table(name = "reviews")
public class ReviewJpaEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "context_place_id")
    private UUID contextPlaceId;

    @Column(name = "experience_text", columnDefinition = "TEXT")
    private String experienceText;

    @Column(name = "is_anonymous", nullable = false)
    private boolean isAnonymous;

    @Column(name = "is_verified_on_site", nullable = false)
    private boolean isVerifiedOnSite;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "visibility", nullable = false, length = 32)
    private String visibility;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public ReviewJpaEntity() {}

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public UUID getContextPlaceId() {
        return contextPlaceId;
    }

    public void setContextPlaceId(UUID contextPlaceId) {
        this.contextPlaceId = contextPlaceId;
    }

    public String getExperienceText() {
        return experienceText;
    }

    public void setExperienceText(String experienceText) {
        this.experienceText = experienceText;
    }

    public boolean isAnonymous() {
        return isAnonymous;
    }

    public void setAnonymous(boolean anonymous) {
        isAnonymous = anonymous;
    }

    public boolean isVerifiedOnSite() {
        return isVerifiedOnSite;
    }

    public void setVerifiedOnSite(boolean verifiedOnSite) {
        isVerifiedOnSite = verifiedOnSite;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getVisibility() {
        return visibility;
    }

    public void setVisibility(String visibility) {
        this.visibility = visibility;
    }

    public static ReviewJpaEntity fromDomain(com.rewit.domain.model.Review domain) {
        if (domain == null) {
            return null;
        }
        ReviewJpaEntity entity = new ReviewJpaEntity();
        entity.setId(domain.getId());
        entity.setUserId(domain.getUserId());
        entity.setContextPlaceId(domain.getContextPlaceId());
        entity.setExperienceText(domain.getExperienceText());
        entity.setAnonymous(domain.isAnonymous());
        entity.setVerifiedOnSite(domain.isVerifiedOnSite());
        entity.setStatus(domain.getStatus() != null ? domain.getStatus().name() : "ACTIVE");
        entity.setVisibility(domain.getVisibility() != null ? domain.getVisibility() : "PUBLIC");
        entity.setCreatedAt(domain.getCreatedAt());
        entity.setUpdatedAt(domain.getUpdatedAt());
        return entity;
    }

    public void updateFromDomain(com.rewit.domain.model.Review domain) {
        if (domain == null) {
            return;
        }
        this.contextPlaceId = domain.getContextPlaceId();
        this.experienceText = domain.getExperienceText();
        this.isAnonymous = domain.isAnonymous();
        this.isVerifiedOnSite = domain.isVerifiedOnSite();
        this.status = domain.getStatus() != null ? domain.getStatus().name() : "ACTIVE";
        this.visibility = domain.getVisibility() != null ? domain.getVisibility() : "PUBLIC";
        this.updatedAt = domain.getUpdatedAt() != null ? domain.getUpdatedAt() : Instant.now();
    }

    public com.rewit.domain.model.Review toDomain() {
        return toDomain(java.util.Collections.emptyList());
    }

    public com.rewit.domain.model.Review toDomain(java.util.List<com.rewit.domain.model.ReviewTarget> targets) {
        com.rewit.domain.enums.ReviewStatus domainStatus = com.rewit.domain.enums.ReviewStatus.ACTIVE;
        if (this.status != null) {
            try {
                domainStatus = com.rewit.domain.enums.ReviewStatus.valueOf(this.status.toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException ignored) {}
        }

        com.rewit.domain.model.Review review = new com.rewit.domain.model.Review(
                this.id,
                this.userId,
                this.contextPlaceId,
                this.experienceText,
                this.isAnonymous,
                this.isVerifiedOnSite,
                domainStatus,
                this.visibility,
                null,
                null,
                null,
                this.createdAt,
                this.updatedAt
        );

        if (targets != null) {
            for (com.rewit.domain.model.ReviewTarget target : targets) {
                review.addRehydratedTarget(target);
            }
        }

        return review;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
