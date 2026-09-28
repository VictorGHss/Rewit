package com.rewit.infrastructure.persistence.entity;

import com.rewit.domain.model.Product;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Entidade JPA mapeando a tabela products no PostgreSQL.
 * Valida com o schema gerado pelas migrations Flyway V1 e V3.
 */
@Entity
@Table(name = "products")
public class ProductJpaEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "brand", length = 128)
    private String brand;

    @Column(name = "model", length = 128)
    private String model;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "category", length = 64)
    private String category = "GERAL";

    @Column(name = "image_url", columnDefinition = "TEXT")
    private String imageUrl;

    @Column(name = "status", nullable = false, length = 32)
    private String status = "ACTIVE";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public ProductJpaEntity() {
    }

    public Product toDomain() {
        return new Product(
                id,
                name,
                brand,
                model,
                description,
                category,
                imageUrl,
                status
        );
    }

    public static ProductJpaEntity fromDomain(Product domain) {
        Objects.requireNonNull(domain, "domain product must not be null");

        ProductJpaEntity entity = new ProductJpaEntity();
        entity.id = domain.getId();
        entity.name = domain.getName();
        entity.brand = domain.getBrand();
        entity.model = domain.getModel();
        entity.description = domain.getDescription();
        entity.category = domain.getCategory();
        entity.imageUrl = domain.getImageUrl();
        entity.status = domain.getStatus();
        entity.createdAt = domain.getCreatedAt() != null ? domain.getCreatedAt() : Instant.now();
        entity.updatedAt = domain.getUpdatedAt() != null ? domain.getUpdatedAt() : Instant.now();
        return entity;
    }

    public void updateFromDomain(Product domain) {
        Objects.requireNonNull(domain, "domain product must not be null");
        this.name = domain.getName();
        this.brand = domain.getBrand();
        this.model = domain.getModel();
        this.description = domain.getDescription();
        this.category = domain.getCategory();
        this.imageUrl = domain.getImageUrl();
        this.status = domain.getStatus();
        this.updatedAt = Instant.now();
    }

    // Getters and Setters
    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getBrand() {
        return brand;
    }

    public void setBrand(String brand) {
        this.brand = brand;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
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
