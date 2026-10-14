package com.rewit.domain.enums;

import com.rewit.common.exception.BusinessException;
import org.springframework.http.HttpStatus;

/**
 * Disponibilidade de uma oferta comercial de produto num estabelecimento (F2.3). Sem quantidade de estoque: retirar a
 * oferta é feito apagando-a, não por um terceiro estado. Os códigos são espelhados em
 * {@code chk_place_product_offer_availability} (V28).
 */
public enum OfferAvailability {
    AVAILABLE,
    OUT_OF_STOCK;

    /** Código exato da API; ausente ou desconhecido responde {@code 400 INVALID_OFFER_AVAILABILITY}. */
    public static OfferAvailability fromCode(String code) {
        if (code != null) {
            for (OfferAvailability availability : values()) {
                if (availability.name().equals(code)) {
                    return availability;
                }
            }
        }
        throw new BusinessException("Disponibilidade inválida: use AVAILABLE ou OUT_OF_STOCK", HttpStatus.BAD_REQUEST,
                "INVALID_OFFER_AVAILABILITY");
    }
}
