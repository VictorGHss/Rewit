package com.rewit.domain.model;

import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.TargetType;

import java.time.Instant;
import java.util.UUID;

/**
 * Entidade de Domínio representando um serviço associado a um local (atendimento, delivery, Wi-Fi, etc.).
 * Herda a identidade relacional de RateableTarget (ADR-009).
 */
public class PlaceService extends RateableTarget {

    private final UUID placeId;
    private final String name;
    private final String category;
    private final String description;
    private final String status;
    private final Instant updatedAt;

    public PlaceService(UUID id, UUID placeId, String name, String category, String description, String status) {
        super(id, TargetType.SERVICE);

        if (placeId == null) {
            throw new BusinessException("O local vinculado ao serviço é obrigatório", "MISSING_PLACE_ID");
        }
        if (name == null || name.isBlank()) {
            throw new BusinessException("O nome do serviço é obrigatório", "INVALID_SERVICE_NAME");
        }

        this.placeId = placeId;
        this.name = name.trim();
        this.category = category != null ? category.trim() : "GERAL";
        this.description = description;
        this.status = status != null ? status : "ACTIVE";
        this.updatedAt = Instant.now();
    }

    public UUID getPlaceId() {
        return placeId;
    }

    public String getName() {
        return name;
    }

    public String getCategory() {
        return category;
    }

    public String getDescription() {
        return description;
    }

    public String getStatus() {
        return status;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
