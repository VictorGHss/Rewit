package com.rewit.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Entidade JPA para a tabela place_opening_hours_schedules (V26).
 */
@Entity
@Table(name = "place_opening_hours_schedules")
public class PlaceOpeningHoursScheduleJpaEntity {

    @Id
    @Column(name = "place_id", nullable = false, updatable = false)
    private UUID placeId;

    @Column(name = "business_account_id", nullable = false)
    private UUID businessAccountId;

    @Column(name = "updated_by_user_id", nullable = false)
    private UUID updatedByUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public PlaceOpeningHoursScheduleJpaEntity() {
    }

    public PlaceOpeningHoursScheduleJpaEntity(
            UUID placeId,
            UUID businessAccountId,
            UUID updatedByUserId,
            Instant createdAt,
            Instant updatedAt
    ) {
        this.placeId = placeId;
        this.businessAccountId = businessAccountId;
        this.updatedByUserId = updatedByUserId;
        this.createdAt = createdAt != null ? createdAt : Instant.now();
        this.updatedAt = updatedAt != null ? updatedAt : this.createdAt;
    }

    public UUID getPlaceId() {
        return placeId;
    }

    public void setPlaceId(UUID placeId) {
        this.placeId = placeId;
    }

    public UUID getBusinessAccountId() {
        return businessAccountId;
    }

    public void setBusinessAccountId(UUID businessAccountId) {
        this.businessAccountId = businessAccountId;
    }

    public UUID getUpdatedByUserId() {
        return updatedByUserId;
    }

    public void setUpdatedByUserId(UUID updatedByUserId) {
        this.updatedByUserId = updatedByUserId;
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PlaceOpeningHoursScheduleJpaEntity that = (PlaceOpeningHoursScheduleJpaEntity) o;
        return Objects.equals(placeId, that.placeId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(placeId);
    }
}

