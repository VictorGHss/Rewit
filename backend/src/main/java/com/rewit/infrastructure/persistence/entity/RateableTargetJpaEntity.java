package com.rewit.infrastructure.persistence.entity;

import com.rewit.domain.enums.TargetType;
import com.rewit.domain.model.RateableTarget;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Entidade JPA mapeando a raiz polimórfica relacional rateable_targets (ADR-009).
 * Valida com o schema gerado pela migration Flyway V1.
 */
@Entity
@Table(name = "rateable_targets")
public class RateableTargetJpaEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 32)
    private TargetType targetType;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public RateableTargetJpaEntity() {
    }

    public RateableTargetJpaEntity(UUID id, TargetType targetType) {
        this.id = id != null ? id : UUID.randomUUID();
        this.targetType = Objects.requireNonNull(targetType, "targetType must not be null");
        this.createdAt = Instant.now();
    }

    public RateableTargetJpaEntity(UUID id, TargetType targetType, Instant createdAt) {
        this.id = id != null ? id : UUID.randomUUID();
        this.targetType = Objects.requireNonNull(targetType, "targetType must not be null");
        this.createdAt = createdAt != null ? createdAt : Instant.now();
    }

    public RateableTarget toDomain() {
        return new RateableTarget(id, targetType);
    }

    public static RateableTargetJpaEntity fromDomain(RateableTarget domain) {
        Objects.requireNonNull(domain, "domain target must not be null");
        return new RateableTargetJpaEntity(domain.getId(), domain.getTargetType(), domain.getCreatedAt());
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public TargetType getTargetType() {
        return targetType;
    }

    public void setTargetType(TargetType targetType) {
        this.targetType = targetType;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
