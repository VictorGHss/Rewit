package com.rewit.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;

/**
 * Entidade JPA mapeando a tabela ranking_configurations no PostgreSQL (V42).
 */
@Entity
@Table(name = "ranking_configurations")
public class RankingConfigurationJpaEntity {

    @Id
    @Column(name = "key", nullable = false, length = 64)
    private String key;

    @Column(name = "value_timestamp", nullable = false)
    private Instant valueTimestamp;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public RankingConfigurationJpaEntity() {
    }

    public RankingConfigurationJpaEntity(String key, Instant valueTimestamp) {
        this.key = Objects.requireNonNull(key, "key must not be null");
        this.valueTimestamp = Objects.requireNonNull(valueTimestamp, "valueTimestamp must not be null");
        this.updatedAt = Instant.now();
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public Instant getValueTimestamp() {
        return valueTimestamp;
    }

    public void setValueTimestamp(Instant valueTimestamp) {
        this.valueTimestamp = valueTimestamp;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}

