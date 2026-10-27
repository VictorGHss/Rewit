package com.rewit.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Entidade JPA para a tabela user_interest_profiles (V40). As seleções ficam em user_interests, gravadas pelo adapter.
 */
@Entity
@Table(name = "user_interest_profiles")
public class UserInterestProfileJpaEntity {

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "onboarding_completed_at")
    private Instant onboardingCompletedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected UserInterestProfileJpaEntity() {
    }

    public UUID getUserId() {
        return userId;
    }

    public Instant getOnboardingCompletedAt() {
        return onboardingCompletedAt;
    }

    public void setOnboardingCompletedAt(Instant onboardingCompletedAt) {
        this.onboardingCompletedAt = onboardingCompletedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        UserInterestProfileJpaEntity that = (UserInterestProfileJpaEntity) o;
        return Objects.equals(userId, that.userId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId);
    }
}
