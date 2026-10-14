package com.rewit.application.dto.catalog;

import com.rewit.domain.enums.OfferAvailability;
import com.rewit.domain.model.PlaceProductOffer;
import com.rewit.domain.model.Product;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * DTOs da camada de aplicação para ofertas comerciais de produtos (F2.3).
 */
public final class PlaceProductOfferDtos {

    private PlaceProductOfferDtos() {
    }

    public record UpsertPlaceProductOfferCommand(
            UUID actorUserId,
            UUID placeId,
            UUID productId,
            UUID businessAccountId,
            String availability,
            BigDecimal price,
            String currency
    ) {
    }

    public record DeletePlaceProductOfferCommand(
            UUID actorUserId,
            UUID placeId,
            UUID productId,
            UUID businessAccountId
    ) {
    }

    /** Resumo público do produto global exibido junto da oferta. */
    public record OfferedProductView(UUID id, String name, String brand, String model, String category,
                                     String imageUrl) {
        public static OfferedProductView fromDomain(Product product) {
            return new OfferedProductView(product.getId(), product.getName(), product.getBrand(), product.getModel(),
                    product.getCategory(), product.getImageUrl());
        }
    }

    /** Oferta visível: produto resumido e termos comerciais, sem conta, autor ou datas. */
    public record PlaceProductOfferView(OfferedProductView product, OfferAvailability availability, BigDecimal price,
                                        String currency) {
        public static PlaceProductOfferView of(PlaceProductOffer offer, Product product) {
            return new PlaceProductOfferView(OfferedProductView.fromDomain(product), offer.getAvailability(),
                    offer.getPrice(), offer.getCurrency());
        }
    }
}
