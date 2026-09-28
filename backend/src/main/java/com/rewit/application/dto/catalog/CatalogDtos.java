package com.rewit.application.dto.catalog;

import java.util.UUID;

/**
 * Comandos e resultados para operações da camada de aplicação do catálogo (Step 7).
 */
public final class CatalogDtos {

    private CatalogDtos() {
    }

    public record CreatePlaceCommand(
            String name,
            String slug,
            String category,
            String description,
            String addressText,
            String streetNumber,
            String neighborhood,
            String city,
            String state,
            String country,
            double latitude,
            double longitude,
            Integer validationRadiusMeters,
            String origin,
            UUID claimedByBusinessId
    ) {}

    public record CreateProductCommand(
            String name,
            String brand,
            String model,
            String description,
            String category,
            String imageUrl
    ) {}

    public record AddProductIdentifierCommand(
            UUID productId,
            String identifierType,
            String identifierValue
    ) {}

    public record AssociateProductPresenceCommand(
            UUID productId,
            UUID placeId,
            UUID reportedByUserId
    ) {}
}
