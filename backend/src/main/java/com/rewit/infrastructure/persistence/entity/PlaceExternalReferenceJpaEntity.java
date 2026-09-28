package com.rewit.infrastructure.persistence.entity;

import com.rewit.domain.model.PlaceExternalReference;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Entidade JPA mapeando a tabela place_external_references no PostgreSQL.
 * Valida com o schema gerado pela migration Flyway V1.
 */
@Entity
@Table(name = "place_external_references")
public class PlaceExternalReferenceJpaEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "place_id", nullable = false)
    private UUID placeId;

    @Column(name = "provider", nullable = false, length = 64)
    private String provider;

    @Column(name = "external_id", nullable = false, columnDefinition = "TEXT")
    private String externalId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata_json", columnDefinition = "jsonb")
    private String metadataJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public PlaceExternalReferenceJpaEntity() {
    }

    public PlaceExternalReferenceJpaEntity(UUID id, UUID placeId, String provider, String externalId, String metadataJson, Instant createdAt) {
        this.id = id != null ? id : UUID.randomUUID();
        this.placeId = Objects.requireNonNull(placeId, "placeId must not be null");
        this.provider = Objects.requireNonNull(provider, "provider must not be null");
        this.externalId = Objects.requireNonNull(externalId, "externalId must not be null");
        this.metadataJson = metadataJson;
        this.createdAt = createdAt != null ? createdAt : Instant.now();
    }

    public PlaceExternalReference toDomain() {
        return new PlaceExternalReference(
                id,
                placeId,
                provider,
                externalId,
                metadataJson
        );
    }

    public static PlaceExternalReferenceJpaEntity fromDomain(PlaceExternalReference domain) {
        Objects.requireNonNull(domain, "domain external reference must not be null");
        return new PlaceExternalReferenceJpaEntity(
                domain.getId(),
                domain.getPlaceId(),
                domain.getProvider(),
                domain.getExternalId(),
                domain.getMetadataJson(),
                domain.getCreatedAt()
        );
    }

    // Getters and Setters
    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getPlaceId() {
        return placeId;
    }

    public void setPlaceId(UUID placeId) {
        this.placeId = placeId;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getExternalId() {
        return externalId;
    }

    public void setExternalId(String externalId) {
        this.externalId = externalId;
    }

    public String getMetadataJson() {
        return metadataJson;
    }

    public void setMetadataJson(String metadataJson) {
        this.metadataJson = metadataJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
