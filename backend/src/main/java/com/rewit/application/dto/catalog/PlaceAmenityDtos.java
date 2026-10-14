package com.rewit.application.dto.catalog;

import com.rewit.domain.enums.PlaceAmenity;
import com.rewit.domain.model.PlaceAmenityConfiguration;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * DTOs da camada de aplicação para comodidades de locais (F2.2).
 */
public final class PlaceAmenityDtos {

    private PlaceAmenityDtos() {
    }

    public record UpdatePlaceAmenitiesCommand(
            UUID actorUserId,
            UUID placeId,
            UUID businessAccountId,
            List<String> amenities
    ) {
    }

    public record AmenityView(String code, String label) {
        public static AmenityView fromDomain(PlaceAmenity amenity) {
            return new AmenityView(amenity.getCode(), amenity.getLabel());
        }

        /** Catálogo completo, na ordem de declaração do enum. */
        public static List<AmenityView> catalog() {
            return Arrays.stream(PlaceAmenity.values()).map(AmenityView::fromDomain).toList();
        }
    }

    /**
     * Comodidades de um local. {@code configured = false} cobre tanto a ausência de configuração quanto a
     * configuração oculta por representação inelegível; {@code configured = true} com lista vazia é a declaração
     * deliberada de que o local não oferece nenhuma comodidade do catálogo.
     */
    public record PlaceAmenitiesView(UUID placeId, boolean configured, List<AmenityView> amenities) {
        public static PlaceAmenitiesView fromDomain(PlaceAmenityConfiguration configuration) {
            return new PlaceAmenitiesView(configuration.getPlaceId(), true,
                    configuration.getAmenities().stream().map(AmenityView::fromDomain).toList());
        }

        public static PlaceAmenitiesView unconfigured(UUID placeId) {
            return new PlaceAmenitiesView(placeId, false, List.of());
        }
    }
}
