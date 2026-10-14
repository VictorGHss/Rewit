package com.rewit.presentation.dto.catalog;

import com.rewit.application.dto.catalog.PlaceAmenityDtos.AmenityView;
import com.rewit.application.dto.catalog.PlaceAmenityDtos.PlaceAmenitiesView;
import com.rewit.application.dto.catalog.PlaceAmenityDtos.UpdatePlaceAmenitiesCommand;
import com.rewit.domain.enums.PlaceAmenity;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Contratos REST das comodidades de locais (F2.2).
 */
public final class PlaceAmenityPresentationDtos {

    /** Teto defensivo do payload: o catálogo inteiro sem repetição cabe nele. */
    public static final int MAX_AMENITIES = 50;

    private PlaceAmenityPresentationDtos() {
    }

    @Schema(description = "Substituição integral das comodidades do estabelecimento")
    public record UpdatePlaceAmenitiesRequest(
            @NotNull(message = "O identificador da conta comercial é obrigatório")
            @Schema(description = "Conta comercial aprovada que reivindica o local")
            UUID businessAccountId,

            @NotNull(message = "A lista de comodidades é obrigatória")
            @Size(max = MAX_AMENITIES, message = "A lista de comodidades excede o tamanho máximo permitido")
            @ArraySchema(arraySchema = @Schema(description = "Códigos do catálogo, sem repetição; lista vazia declara "
                    + "que o local não oferece nenhuma comodidade"),
                    schema = @Schema(implementation = PlaceAmenity.class))
            List<@NotNull(message = "A comodidade não pode ser nula") String> amenities
    ) {
        public UpdatePlaceAmenitiesCommand toCommand(UUID actorUserId, UUID placeId) {
            return new UpdatePlaceAmenitiesCommand(actorUserId, placeId, businessAccountId, amenities);
        }
    }

    @Schema(description = "Comodidade do catálogo controlado")
    public record AmenityResponse(
            @Schema(example = "WIFI") String code,
            @Schema(example = "Wi-Fi para clientes") String label
    ) {
        public static AmenityResponse fromView(AmenityView view) {
            return new AmenityResponse(view.code(), view.label());
        }
    }

    @Schema(description = "Comodidades declaradas para o local")
    public record PlaceAmenitiesResponse(
            UUID placeId,

            @Schema(example = "true", description = "false quando não há configuração visível para o local")
            boolean configured,

            @Schema(description = "Comodidades na ordem do catálogo; vazia com configured=true significa que o local "
                    + "declarou não oferecer nenhuma")
            List<AmenityResponse> amenities
    ) {
        public static PlaceAmenitiesResponse fromView(PlaceAmenitiesView view) {
            return new PlaceAmenitiesResponse(view.placeId(), view.configured(),
                    view.amenities().stream().map(AmenityResponse::fromView).toList());
        }
    }

    @Schema(description = "Catálogo de comodidades aceitas pela API")
    public record AmenityCatalogResponse(List<AmenityResponse> options) {
        public static AmenityCatalogResponse fromViews(List<AmenityView> views) {
            return new AmenityCatalogResponse(views.stream().map(AmenityResponse::fromView).toList());
        }
    }
}
