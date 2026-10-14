package com.rewit.presentation.dto.catalog;

import com.rewit.application.dto.catalog.PlaceProductOfferDtos.OfferedProductView;
import com.rewit.application.dto.catalog.PlaceProductOfferDtos.PlaceProductOfferView;
import com.rewit.application.dto.catalog.PlaceProductOfferDtos.UpsertPlaceProductOfferCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Contratos REST das ofertas comerciais de produtos (F2.3).
 */
public final class PlaceProductOfferPresentationDtos {

    private PlaceProductOfferPresentationDtos() {
    }

    @Schema(description = "Oferta do produto no estabelecimento; substitui os termos anteriores dessa oferta")
    public record UpsertPlaceProductOfferRequest(
            @NotNull(message = "O identificador da conta comercial é obrigatório")
            @Schema(description = "Conta comercial aprovada que reivindica o local")
            UUID businessAccountId,

            @NotNull(message = "A disponibilidade é obrigatória")
            @Schema(example = "AVAILABLE", allowableValues = {"AVAILABLE", "OUT_OF_STOCK"})
            String availability,

            @Schema(example = "12.90", nullable = true,
                    description = "Preço em BRL com até duas casas; null ou ausente = não divulgado; 0.00 = gratuito")
            BigDecimal price,

            @Schema(example = "BRL", nullable = true, description = "Ausente equivale a BRL; outra moeda é recusada")
            String currency
    ) {
        public UpsertPlaceProductOfferCommand toCommand(UUID actorUserId, UUID placeId, UUID productId) {
            return new UpsertPlaceProductOfferCommand(actorUserId, placeId, productId, businessAccountId, availability,
                    price, currency);
        }
    }

    @Schema(description = "Resumo do produto global ofertado")
    public record OfferedProductResponse(UUID id, String name, String brand, String model, String category,
                                         String imageUrl) {
        public static OfferedProductResponse fromView(OfferedProductView view) {
            return new OfferedProductResponse(view.id(), view.name(), view.brand(), view.model(), view.category(),
                    view.imageUrl());
        }
    }

    @Schema(description = "Oferta comercial visível do produto no estabelecimento")
    public record PlaceProductOfferResponse(
            OfferedProductResponse product,

            @Schema(example = "AVAILABLE")
            String availability,

            @Schema(example = "12.90", nullable = true, description = "null quando o preço não é divulgado")
            BigDecimal price,

            @Schema(example = "BRL")
            String currency
    ) {
        public static PlaceProductOfferResponse fromView(PlaceProductOfferView view) {
            return new PlaceProductOfferResponse(OfferedProductResponse.fromView(view.product()),
                    view.availability().name(), view.price(), view.currency());
        }
    }
}
