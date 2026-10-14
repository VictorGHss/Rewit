package com.rewit.domain.model;

import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.OfferAvailability;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Oferta oficial de um produto global num estabelecimento, publicada pela conta comercial titular (F2.3).
 *
 * <p>Independe da presença comunitária ({@link ProductPresence}) e não altera o produto global. O preço é opcional:
 * {@code null} é preço não divulgado e {@code 0.00} é gratuito; nunca negativo, no máximo duas casas e sempre em BRL.
 * A oferta pertence à conta que a gravou; quando o titular do local muda, a V28 a apaga (decisão T2).
 */
public final class PlaceProductOffer {

    public static final String CURRENCY_BRL = "BRL";
    /** Maior valor de {@code NUMERIC(12, 2)}. */
    public static final BigDecimal MAX_PRICE = new BigDecimal("9999999999.99");

    private final UUID id;
    private final UUID placeId;
    private final UUID productId;
    private final UUID businessAccountId;
    private final OfferAvailability availability;
    private final BigDecimal price;
    private final String currency;
    private final UUID updatedByUserId;
    private final Instant createdAt;
    private final Instant updatedAt;

    public PlaceProductOffer(UUID id, UUID placeId, UUID productId, UUID businessAccountId,
                             OfferAvailability availability, BigDecimal price, String currency, UUID updatedByUserId,
                             Instant createdAt, Instant updatedAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.placeId = require(placeId, "O local é obrigatório", "MISSING_PLACE_ID");
        this.productId = require(productId, "O produto é obrigatório", "MISSING_PRODUCT_ID");
        this.businessAccountId = require(businessAccountId, "A conta comercial é obrigatória",
                "MISSING_BUSINESS_ACCOUNT_ID");
        this.updatedByUserId = require(updatedByUserId, "O usuário responsável é obrigatório", "MISSING_ACTOR_USER_ID");
        // fromCode(null) sempre recusa com 400 INVALID_OFFER_AVAILABILITY
        this.availability = availability != null ? availability : OfferAvailability.fromCode(null);
        this.price = normalizePrice(price);
        this.currency = normalizeCurrency(currency);
        this.createdAt = createdAt != null ? createdAt : Instant.now();
        this.updatedAt = updatedAt != null ? updatedAt : this.createdAt;
    }

    /** Primeira oferta do produto no local, gravada agora pelo ator. */
    public static PlaceProductOffer create(UUID placeId, UUID productId, UUID businessAccountId, UUID actorUserId,
                                           OfferAvailability availability, BigDecimal price, String currency,
                                           Instant now) {
        return new PlaceProductOffer(UUID.randomUUID(), placeId, productId, businessAccountId, availability, price,
                currency, actorUserId, now, now);
    }

    /** Mesma oferta com novos termos; preserva id e criação. */
    public PlaceProductOffer revise(UUID newBusinessAccountId, UUID actorUserId, OfferAvailability newAvailability,
                                   BigDecimal newPrice, String newCurrency, Instant now) {
        return new PlaceProductOffer(id, placeId, productId, newBusinessAccountId, newAvailability, newPrice,
                newCurrency, actorUserId, createdAt, now);
    }

    /** Os termos públicos e o titular já são esses: regravar não muda nada (PUT idempotente). */
    public boolean hasTerms(UUID otherBusinessAccountId, OfferAvailability otherAvailability, BigDecimal otherPrice,
                            String otherCurrency) {
        BigDecimal normalizedPrice = normalizePrice(otherPrice);
        return businessAccountId.equals(otherBusinessAccountId)
                && availability == otherAvailability
                && (price == null ? normalizedPrice == null
                        : normalizedPrice != null && price.compareTo(normalizedPrice) == 0)
                && currency.equals(normalizeCurrency(otherCurrency));
    }

    /**
     * Preço com duas casas, ou {@code null} (não divulgado). Negativo, mais de duas casas decimais ou acima de
     * {@link #MAX_PRICE} responde {@code 400 INVALID_OFFER_PRICE}.
     */
    public static BigDecimal normalizePrice(BigDecimal price) {
        if (price == null) {
            return null;
        }
        if (price.signum() < 0) {
            throw invalidPrice("O preço não pode ser negativo");
        }
        if (price.stripTrailingZeros().scale() > 2) {
            throw invalidPrice("O preço aceita no máximo duas casas decimais");
        }
        if (price.compareTo(MAX_PRICE) > 0) {
            throw invalidPrice("O preço excede o valor máximo permitido");
        }
        return price.setScale(2);
    }

    /** Moeda ausente é BRL; qualquer outra responde {@code 400 UNSUPPORTED_CURRENCY}. */
    public static String normalizeCurrency(String currency) {
        if (currency == null || CURRENCY_BRL.equals(currency)) {
            return CURRENCY_BRL;
        }
        throw new BusinessException("Moeda não suportada: use BRL", HttpStatus.BAD_REQUEST, "UNSUPPORTED_CURRENCY");
    }

    private static BusinessException invalidPrice(String message) {
        return new BusinessException(message, HttpStatus.BAD_REQUEST, "INVALID_OFFER_PRICE");
    }

    private static UUID require(UUID value, String message, String code) {
        if (value == null) {
            throw new BusinessException(message, HttpStatus.BAD_REQUEST, code);
        }
        return value;
    }

    public UUID getId() {
        return id;
    }

    public UUID getPlaceId() {
        return placeId;
    }

    public UUID getProductId() {
        return productId;
    }

    public UUID getBusinessAccountId() {
        return businessAccountId;
    }

    public OfferAvailability getAvailability() {
        return availability;
    }

    /** {@code null} quando o preço não é divulgado. */
    public BigDecimal getPrice() {
        return price;
    }

    public String getCurrency() {
        return currency;
    }

    public UUID getUpdatedByUserId() {
        return updatedByUserId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
