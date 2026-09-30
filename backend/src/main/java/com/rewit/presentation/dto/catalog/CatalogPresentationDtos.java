package com.rewit.presentation.dto.catalog;

import java.util.UUID;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * DTOs de apresentação (Requests e Responses) para a API de catálogo (Step 7).
 */
public final class CatalogPresentationDtos {

    private CatalogPresentationDtos() {}

    public record CreatePlaceRequest(
            @NotBlank(message = "O nome do local é obrigatório")
            @Size(max = 255, message = "O nome deve conter até 255 caracteres")
            String name,

            @NotBlank(message = "O slug é obrigatório")
            @Pattern(regexp = "^[a-z0-9]+(?:-[a-z0-9]+)*$", message = "Slug deve conter apenas letras minúsculas, números e hífens")
            String slug,

            @NotBlank(message = "A categoria é obrigatória")
            @Size(max = 64, message = "A categoria deve conter até 64 caracteres")
            String category,

            String description,

            @NotBlank(message = "O endereço é obrigatório")
            String addressText,

            String streetNumber,
            String neighborhood,

            @NotBlank(message = "A cidade é obrigatória")
            String city,

            @NotBlank(message = "O estado (UF) é obrigatório")
            String state,

            String country,

            @NotNull(message = "A latitude é obrigatória")
            @DecimalMin(value = "-90.0", message = "Latitude mínima é -90.0")
            @DecimalMax(value = "90.0", message = "Latitude máxima é 90.0")
            Double latitude,

            @NotNull(message = "A longitude é obrigatória")
            @DecimalMin(value = "-180.0", message = "Longitude mínima é -180.0")
            @DecimalMax(value = "180.0", message = "Longitude máxima é 180.0")
            Double longitude,

            @Min(value = 1, message = "O raio de validação deve ser maior que zero")
            Integer validationRadiusMeters
    ) {}

    public record AdoptPlaceRequest(
            @NotBlank(message = "O nome do local é obrigatório")
            @Size(max = 255, message = "O nome deve conter até 255 caracteres")
            String name,

            String slug,

            String category,

            String description,

            @NotBlank(message = "O endereço é obrigatório")
            String addressText,

            String streetNumber,
            String neighborhood,

            @NotBlank(message = "A cidade é obrigatória")
            String city,

            @NotBlank(message = "O estado (UF) é obrigatório")
            String state,

            String country,

            @NotNull(message = "A latitude é obrigatória")
            @DecimalMin(value = "-90.0", message = "Latitude mínima é -90.0")
            @DecimalMax(value = "90.0", message = "Latitude máxima é 90.0")
            Double latitude,

            @NotNull(message = "A longitude é obrigatória")
            @DecimalMin(value = "-180.0", message = "Longitude mínima é -180.0")
            @DecimalMax(value = "180.0", message = "Longitude máxima é 180.0")
            Double longitude,

            @Min(value = 1, message = "O raio de validação deve ser maior que zero")
            Integer validationRadiusMeters,

            @jakarta.validation.Valid
            ExternalReferenceRequest externalReference
    ) {
        public record ExternalReferenceRequest(
                @NotBlank(message = "O provedor é obrigatório")
                String provider,

                @NotBlank(message = "O identificador externo é obrigatório")
                String externalId
        ) {}
    }

    public record PlaceResponse(
            UUID id,
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
            int validationRadiusMeters,
            String origin,
            boolean isVerified,
            String status
    ) {}

    public record NearbyPlaceItemResponse(
            UUID id,
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
            int validationRadiusMeters,
            String origin,
            boolean isVerified,
            String status,
            double distanceMeters
    ) {}

    public record NearbyPlacesResponse(
            java.util.List<NearbyPlaceItemResponse> items,
            int limit
    ) {}

    public record CreateProductRequest(
            @NotBlank(message = "O nome do produto é obrigatório")
            @Size(max = 255, message = "O nome deve conter até 255 caracteres")
            String name,

            String brand,
            String model,
            String description,
            String category,
            String imageUrl
    ) {}

    public record ProductResponse(
            UUID id,
            String name,
            String brand,
            String model,
            String description,
            String category,
            String imageUrl,
            String status
    ) {}

    public record AddProductIdentifierRequest(
            @NotBlank(message = "O tipo de identificador é obrigatório (ex: EAN, UPC, GTIN)")
            String identifierType,

            @NotBlank(message = "O código/valor do identificador é obrigatório")
            String identifierValue
    ) {}

    public record ProductIdentifierResponse(
            UUID id,
            UUID productId,
            String identifierType,
            String identifierValue
    ) {}

    public record AssociateProductPresenceRequest(
            @NotNull(message = "O identificador do local é obrigatório")
            UUID placeId
    ) {}

    public record ProductPresenceResponse(
            UUID id,
            UUID productId,
            UUID placeId,
            UUID reportedByUserId,
            String verificationStatus,
            String status
    ) {}

    public record SearchResultResponse(
            UUID id,
            String name,
            String slug,
            String category,
            String targetType,
            String status
    ) {
        public static SearchResultResponse fromDomain(com.rewit.application.dto.catalog.CatalogDtos.CatalogSearchResult result) {
            return new SearchResultResponse(
                    result.id(),
                    result.name(),
                    result.slug(),
                    result.category(),
                    result.targetType(),
                    result.status()
            );
        }
    }
}
