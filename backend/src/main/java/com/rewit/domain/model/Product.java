package com.rewit.domain.model;

import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.TargetType;

import java.time.Instant;
import java.util.UUID;

/**
 * Entidade de Domínio representando um produto global independente de local (Product).
 * Herda a identidade relacional de RateableTarget (ADR-009).
 */
public class Product extends RateableTarget {

    private final String name;
    private final String brand;
    private final String model;
    private final String description;
    private final String category;
    private final String imageUrl;
    private final String status;
    private final Instant updatedAt;

    public Product(UUID id, String name, String brand, String model, String description,
                   String category, String imageUrl, String status) {
        super(id, TargetType.PRODUCT);

        if (name == null || name.isBlank()) {
            throw new BusinessException("O nome do produto é obrigatório", "INVALID_PRODUCT_NAME");
        }

        this.name = name.trim();
        this.brand = brand != null ? brand.trim() : null;
        this.model = model != null ? model.trim() : null;
        this.description = description;
        this.category = category != null ? category.trim() : "GERAL";
        this.imageUrl = imageUrl;
        this.status = status != null ? status : "ACTIVE";
        this.updatedAt = Instant.now();
    }

    public String getName() {
        return name;
    }

    public String getBrand() {
        return brand;
    }

    public String getModel() {
        return model;
    }

    public String getDescription() {
        return description;
    }

    public String getCategory() {
        return category;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public String getStatus() {
        return status;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
