package com.rewit.domain.model;

import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.OfferAvailability;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Domínio: oferta comercial de produto (F2.3)")
class PlaceProductOfferTest {

    private final UUID placeId = UUID.randomUUID();
    private final UUID productId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-10-14T12:00:00Z");

    @Test
    @DisplayName("Preço nulo é não divulgado; zero é gratuito; positivo ganha duas casas")
    void priceNormalization() {
        assertNull(PlaceProductOffer.normalizePrice(null));
        assertEquals(new BigDecimal("0.00"), PlaceProductOffer.normalizePrice(BigDecimal.ZERO));
        assertEquals(new BigDecimal("12.90"), PlaceProductOffer.normalizePrice(new BigDecimal("12.9")));
        assertEquals(new BigDecimal("12.90"), PlaceProductOffer.normalizePrice(new BigDecimal("12.9000")));
        assertEquals(PlaceProductOffer.MAX_PRICE, PlaceProductOffer.normalizePrice(PlaceProductOffer.MAX_PRICE));
    }

    @Test
    @DisplayName("Preço negativo, com mais de duas casas ou acima do máximo responde 400 INVALID_OFFER_PRICE")
    void invalidPrices() {
        for (String value : new String[]{"-0.01", "-10", "1.001", "0.005", "10000000000.00"}) {
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> PlaceProductOffer.normalizePrice(new BigDecimal(value)));
            assertEquals("INVALID_OFFER_PRICE", ex.getErrorCode());
            assertEquals(400, ex.getStatus().value());
        }
    }

    @Test
    @DisplayName("Moeda ausente vira BRL; qualquer outra responde 400 UNSUPPORTED_CURRENCY")
    void currency() {
        assertEquals("BRL", PlaceProductOffer.normalizeCurrency(null));
        assertEquals("BRL", PlaceProductOffer.normalizeCurrency("BRL"));
        for (String currency : new String[]{"USD", "brl", "", " BRL"}) {
            assertEquals("UNSUPPORTED_CURRENCY", assertThrows(BusinessException.class,
                    () -> PlaceProductOffer.normalizeCurrency(currency)).getErrorCode());
        }
    }

    @Test
    @DisplayName("Disponibilidade aceita só AVAILABLE e OUT_OF_STOCK, com o código exato")
    void availability() {
        assertEquals(OfferAvailability.AVAILABLE, OfferAvailability.fromCode("AVAILABLE"));
        assertEquals(OfferAvailability.OUT_OF_STOCK, OfferAvailability.fromCode("OUT_OF_STOCK"));
        for (String code : Arrays.asList(null, "", "available", "SOLD_OUT", "RESERVED")) {
            assertEquals("INVALID_OFFER_AVAILABILITY", assertThrows(BusinessException.class,
                    () -> OfferAvailability.fromCode(code)).getErrorCode());
        }
    }

    @Test
    @DisplayName("Revisão preserva id e criação; hasTerms compara preço por valor e titular")
    void reviseAndTerms() {
        PlaceProductOffer offer = PlaceProductOffer.create(placeId, productId, accountId, actorId,
                OfferAvailability.AVAILABLE, new BigDecimal("5"), null, now);
        assertEquals(new BigDecimal("5.00"), offer.getPrice());
        assertEquals("BRL", offer.getCurrency());
        assertTrue(offer.hasTerms(accountId, OfferAvailability.AVAILABLE, new BigDecimal("5.0"), "BRL"));
        assertFalse(offer.hasTerms(accountId, OfferAvailability.AVAILABLE, null, null));
        assertFalse(offer.hasTerms(accountId, OfferAvailability.OUT_OF_STOCK, new BigDecimal("5"), null));
        assertFalse(offer.hasTerms(UUID.randomUUID(), OfferAvailability.AVAILABLE, new BigDecimal("5"), null));

        Instant later = now.plusSeconds(60);
        UUID otherActor = UUID.randomUUID();
        PlaceProductOffer revised = offer.revise(accountId, otherActor, OfferAvailability.OUT_OF_STOCK, null, null,
                later);
        assertEquals(offer.getId(), revised.getId());
        assertEquals(now, revised.getCreatedAt());
        assertEquals(later, revised.getUpdatedAt());
        assertEquals(otherActor, revised.getUpdatedByUserId());
        assertNull(revised.getPrice());
        assertTrue(revised.hasTerms(accountId, OfferAvailability.OUT_OF_STOCK, null, "BRL"));
    }

    @Test
    @DisplayName("Oferta exige local, produto, conta, autor e disponibilidade")
    void requiredFields() {
        assertEquals("MISSING_PLACE_ID", assertThrows(BusinessException.class, () -> PlaceProductOffer.create(
                null, productId, accountId, actorId, OfferAvailability.AVAILABLE, null, null, now)).getErrorCode());
        assertEquals("MISSING_PRODUCT_ID", assertThrows(BusinessException.class, () -> PlaceProductOffer.create(
                placeId, null, accountId, actorId, OfferAvailability.AVAILABLE, null, null, now)).getErrorCode());
        assertEquals("MISSING_BUSINESS_ACCOUNT_ID", assertThrows(BusinessException.class, () -> PlaceProductOffer.create(
                placeId, productId, null, actorId, OfferAvailability.AVAILABLE, null, null, now)).getErrorCode());
        assertEquals("MISSING_ACTOR_USER_ID", assertThrows(BusinessException.class, () -> PlaceProductOffer.create(
                placeId, productId, accountId, null, OfferAvailability.AVAILABLE, null, null, now)).getErrorCode());
        assertEquals("INVALID_OFFER_AVAILABILITY", assertThrows(BusinessException.class, () -> PlaceProductOffer.create(
                placeId, productId, accountId, actorId, null, null, null, now)).getErrorCode());
    }
}
