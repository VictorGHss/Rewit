package com.rewit.domain.model;

import com.rewit.common.exception.BusinessException;

import java.time.Instant;
import java.util.UUID;

/**
 * Entidade de Domínio representando conteúdo promocional comercial vinculado a um Place.
 * Sem motor promocional prematuro (Seção 30).
 */
public class Promotion {

    private final UUID id;
    private final UUID placeId;
    private final UUID businessAccountId;
    private final String title;
    private final String description;
    private final String discountCode;
    private final Instant startAt;
    private final Instant endAt;
    private boolean isActive;
    private final Instant createdAt;
    private Instant updatedAt;

    public Promotion(UUID id, UUID placeId, UUID businessAccountId, String title,
                     String description, String discountCode, Instant startAt, Instant endAt) {
        if (placeId == null) {
            throw new BusinessException("O local vinculado à promoção é obrigatório", "MISSING_PLACE_ID");
        }
        if (title == null || title.isBlank()) {
            throw new BusinessException("O título da promoção é obrigatório", "INVALID_PROMOTION_TITLE");
        }
        if (startAt == null || endAt == null) {
            throw new BusinessException("O período da promoção é obrigatório", "MISSING_PROMOTION_DATES");
        }
        if (endAt.isBefore(startAt)) {
            throw new BusinessException("A data de término não pode ser anterior à data de início", "INVALID_PROMOTION_DATES");
        }

        this.id = id != null ? id : UUID.randomUUID();
        this.placeId = placeId;
        this.businessAccountId = businessAccountId;
        this.title = title.trim();
        this.description = description;
        this.discountCode = discountCode != null ? discountCode.trim().toUpperCase() : null;
        this.startAt = startAt;
        this.endAt = endAt;
        this.isActive = true;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getPlaceId() {
        return placeId;
    }

    public UUID getBusinessAccountId() {
        return businessAccountId;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public String getDiscountCode() {
        return discountCode;
    }

    public Instant getStartAt() {
        return startAt;
    }

    public Instant getEndAt() {
        return endAt;
    }

    public boolean isActive() {
        return isActive;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
