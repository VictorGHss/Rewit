package com.rewit.infrastructure.persistence.entity;

import com.rewit.domain.model.ProductIdentifier;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Entidade JPA mapeando a tabela product_identifiers no PostgreSQL.
 * Valida com o schema gerado pela migration Flyway V1.
 */
@Entity
@Table(name = "product_identifiers")
public class ProductIdentifierJpaEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "identifier_type", nullable = false, length = 32)
    private String identifierType;

    @Column(name = "identifier_value", nullable = false, length = 128)
    private String identifierValue;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public ProductIdentifierJpaEntity() {
    }

    public ProductIdentifierJpaEntity(UUID id, UUID productId, String identifierType, String identifierValue, Instant createdAt) {
        this.id = id != null ? id : UUID.randomUUID();
        this.productId = Objects.requireNonNull(productId, "productId must not be null");
        this.identifierType = Objects.requireNonNull(identifierType, "identifierType must not be null");
        this.identifierValue = Objects.requireNonNull(identifierValue, "identifierValue must not be null");
        this.createdAt = createdAt != null ? createdAt : Instant.now();
    }

    public ProductIdentifier toDomain() {
        return new ProductIdentifier(
                id,
                productId,
                identifierType,
                identifierValue
        );
    }

    public static ProductIdentifierJpaEntity fromDomain(ProductIdentifier domain) {
        Objects.requireNonNull(domain, "domain identifier must not be null");
        return new ProductIdentifierJpaEntity(
                domain.getId(),
                domain.getProductId(),
                domain.getIdentifierType(),
                domain.getIdentifierValue(),
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

    public UUID getProductId() {
        return productId;
    }

    public void setProductId(UUID productId) {
        this.productId = productId;
    }

    public String getIdentifierType() {
        return identifierType;
    }

    public void setIdentifierType(String identifierType) {
        this.identifierType = identifierType;
    }

    public String getIdentifierValue() {
        return identifierValue;
    }

    public void setIdentifierValue(String identifierValue) {
        this.identifierValue = identifierValue;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
