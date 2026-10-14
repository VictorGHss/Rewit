package com.rewit.infrastructure.persistence.entity;

import com.rewit.domain.enums.OfferAvailability;
import com.rewit.domain.model.PlaceProductOffer;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Entidade JPA para a tabela place_product_offers (V28).
 */
@Entity
@Table(name = "place_product_offers")
public class PlaceProductOfferJpaEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "place_id", nullable = false, updatable = false)
    private UUID placeId;

    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    @Column(name = "business_account_id", nullable = false)
    private UUID businessAccountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "availability", nullable = false, length = 32)
    private OfferAvailability availability;

    @Column(name = "price", precision = 12, scale = 2)
    private BigDecimal price;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "updated_by_user_id", nullable = false)
    private UUID updatedByUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PlaceProductOfferJpaEntity() {
    }

    public static PlaceProductOfferJpaEntity fromDomain(PlaceProductOffer offer) {
        PlaceProductOfferJpaEntity entity = new PlaceProductOfferJpaEntity();
        entity.id = offer.getId();
        entity.placeId = offer.getPlaceId();
        entity.productId = offer.getProductId();
        entity.createdAt = offer.getCreatedAt();
        entity.updateFromDomain(offer);
        return entity;
    }

    /** Copia os campos mutáveis; local, produto, id e criação não mudam. */
    public void updateFromDomain(PlaceProductOffer offer) {
        this.businessAccountId = offer.getBusinessAccountId();
        this.availability = offer.getAvailability();
        this.price = offer.getPrice();
        this.currency = offer.getCurrency();
        this.updatedByUserId = offer.getUpdatedByUserId();
        this.updatedAt = offer.getUpdatedAt();
    }

    public PlaceProductOffer toDomain() {
        return new PlaceProductOffer(id, placeId, productId, businessAccountId, availability, price, currency,
                updatedByUserId, createdAt, updatedAt);
    }

    public UUID getId() {
        return id;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        return Objects.equals(id, ((PlaceProductOfferJpaEntity) o).id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
