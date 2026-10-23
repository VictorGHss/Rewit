package com.rewit.presentation.dto.catalog;

import java.util.List;
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

            @Size(max = 255, message = "O slug deve conter até 255 caracteres")
            @Pattern(regexp = "^$|^[a-z0-9]+(?:-[a-z0-9]+)*$", message = "Slug deve conter apenas letras minúsculas, números e hífens")
            String slug,

            @Size(max = 64, message = "A categoria deve conter até 64 caracteres")
            String category,

            @Size(max = 2000, message = "A descrição deve conter até 2000 caracteres")
            String description,

            @NotBlank(message = "O endereço é obrigatório")
            @Size(max = 500, message = "O endereço deve conter até 500 caracteres")
            String addressText,

            @Size(max = 32, message = "O número deve conter até 32 caracteres")
            String streetNumber,

            @Size(max = 128, message = "O bairro deve conter até 128 caracteres")
            String neighborhood,

            @Size(max = 100, message = "A cidade deve conter até 100 caracteres")
            String city,

            @Size(max = 50, message = "O estado (UF) deve conter até 50 caracteres")
            String state,

            @Size(max = 10, message = "O país deve conter até 10 caracteres")
            String country,

            @DecimalMin(value = "-90.0", message = "Latitude mínima é -90.0")
            @DecimalMax(value = "90.0", message = "Latitude máxima é 90.0")
            Double latitude,

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

            @Size(max = 255, message = "O slug deve conter até 255 caracteres")
            @Pattern(regexp = "^$|^[a-z0-9]+(?:-[a-z0-9]+)*$", message = "Slug deve conter apenas letras minúsculas, números e hífens")
            String slug,

            @Size(max = 64, message = "A categoria deve conter até 64 caracteres")
            String category,

            @Size(max = 2000, message = "A descrição deve conter até 2000 caracteres")
            String description,

            @NotBlank(message = "O endereço é obrigatório")
            @Size(max = 500, message = "O endereço deve conter até 500 caracteres")
            String addressText,

            @Size(max = 32, message = "O número deve conter até 32 caracteres")
            String streetNumber,

            @Size(max = 128, message = "O bairro deve conter até 128 caracteres")
            String neighborhood,

            @NotBlank(message = "A cidade é obrigatória")
            @Size(max = 100, message = "A cidade deve conter até 100 caracteres")
            String city,

            @NotBlank(message = "O estado (UF) é obrigatório")
            @Size(max = 50, message = "O estado (UF) deve conter até 50 caracteres")
            String state,

            @Size(max = 10, message = "O país deve conter até 10 caracteres")
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
                @Size(max = 64, message = "O provedor deve conter até 64 caracteres")
                String provider,

                @NotBlank(message = "O identificador externo é obrigatório")
                @Size(max = 255, message = "O identificador externo deve conter até 255 caracteres")
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
            Double latitude,
            Double longitude,
            int validationRadiusMeters,
            String origin,
            boolean isVerified,
            String status,
            String photoUrl,
            // Contatos do perfil comercial; null quando não informados
            String phone,
            String websiteUrl,
            String instagramUrl,
            String facebookUrl
    ) {
        public PlaceResponse(
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
                Double latitude,
                Double longitude,
                int validationRadiusMeters,
                String origin,
                boolean isVerified,
                String status
        ) {
            this(id, name, slug, category, description, addressText, streetNumber, neighborhood,
                    city, state, country, latitude, longitude, validationRadiusMeters, origin,
                    isVerified, status, null, null, null, null, null);
        }

        /**
         * Leitura pública do local: os contatos são os que a política de representação libera
         * ({@code BusinessRepresentationPolicy.publicContactInfo}), nunca os gravados diretamente.
         */
        public static PlaceResponse publicView(com.rewit.domain.model.Place place, String photoUrl,
                                               com.rewit.domain.model.PlaceContactInfo visibleContact) {
            return of(place, photoUrl, visibleContact != null ? visibleContact
                    : com.rewit.domain.model.PlaceContactInfo.EMPTY);
        }

        /** Resposta do fluxo autorizado de edição do perfil: o representante vê os contatos que acabou de gravar. */
        public static PlaceResponse representativeView(com.rewit.domain.model.Place place, String photoUrl) {
            return of(place, photoUrl, place.getContactInfo());
        }

        private static PlaceResponse of(com.rewit.domain.model.Place place, String photoUrl,
                                        com.rewit.domain.model.PlaceContactInfo contact) {
            return new PlaceResponse(
                    place.getId(),
                    place.getName(),
                    place.getSlug(),
                    place.getCategory(),
                    place.getDescription(),
                    place.getAddressText(),
                    place.getStreetNumber(),
                    place.getNeighborhood(),
                    place.getCity(),
                    place.getState(),
                    place.getCountry(),
                    place.getLatitude(),
                    place.getLongitude(),
                    place.getValidationRadiusMeters(),
                    place.getOrigin(),
                    place.isVerified(),
                    place.getStatus(),
                    photoUrl,
                    contact.phone(),
                    contact.websiteUrl(),
                    contact.instagramUrl(),
                    contact.facebookUrl()
            );
        }
    }

    public record PlaceMediaResponse(
            UUID id,
            UUID placeId,
            String url,
            String mediaType,
            String mimeType,
            long sizeBytes,
            Integer width,
            Integer height,
            String status,
            boolean isPrimary,
            java.time.Instant createdAt
    ) {
        public static PlaceMediaResponse fromView(com.rewit.application.dto.media.MediaDtos.PlaceMediaView view) {
            return new PlaceMediaResponse(
                    view.id(),
                    view.placeId(),
                    view.url(),
                    view.mediaType(),
                    view.mimeType(),
                    view.sizeBytes(),
                    view.width(),
                    view.height(),
                    view.status(),
                    view.isPrimary(),
                    view.createdAt()
            );
        }
    }

    public record CreatePlaceMediaReportRequest(
            @NotBlank(message = "O motivo da denúncia é obrigatório")
            @Size(max = 64, message = "O motivo deve conter até 64 caracteres")
            String reason,

            @Size(max = 500, message = "O detalhe da denúncia deve conter até 500 caracteres")
            String detail
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
    ) {
        public static ProductResponse fromDomain(com.rewit.domain.model.Product product) {
            return new ProductResponse(
                    product.getId(),
                    product.getName(),
                    product.getBrand(),
                    product.getModel(),
                    product.getDescription(),
                    product.getCategory(),
                    product.getImageUrl(),
                    product.getStatus()
            );
        }
    }

    /** Identificador público de produto: só tipo e valor, sem id de linha, produto ou datas (C5.3). */
    public record PublicProductIdentifierResponse(
            String identifierType,
            String identifierValue
    ) {}

    public record ProductIdentifiersResponse(
            List<PublicProductIdentifierResponse> identifiers
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
