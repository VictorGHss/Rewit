package com.rewit.domain.model;

import com.rewit.common.exception.BusinessException;

import java.time.Instant;
import java.util.UUID;

/**
 * Entidade de Domínio representando um identificador de produto estruturado (EAN, UPC, GTIN, etc.).
 * Evita concatenações frágeis de múltiplos códigos em uma única coluna.
 */
public class ProductIdentifier {

    private final UUID id;
    private final UUID productId;
    private final String identifierType;
    private final String identifierValue;
    private final Instant createdAt;

    public ProductIdentifier(UUID id, UUID productId, String identifierType, String identifierValue) {
        if (productId == null) {
            throw new BusinessException("O produto vinculado ao identificador é obrigatório", "MISSING_PRODUCT_ID");
        }
        if (identifierType == null || identifierType.isBlank()) {
            throw new BusinessException("O tipo de identificador é obrigatório (ex: EAN, UPC, GTIN)", "MISSING_IDENTIFIER_TYPE");
        }
        if (identifierValue == null || identifierValue.isBlank()) {
            throw new BusinessException("O valor do código é obrigatório", "MISSING_IDENTIFIER_VALUE");
        }

        this.id = id != null ? id : UUID.randomUUID();
        this.productId = productId;
        this.identifierType = identifierType.trim().toUpperCase();
        this.identifierValue = identifierValue.trim();
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getProductId() {
        return productId;
    }

    public String getIdentifierType() {
        return identifierType;
    }

    public String getIdentifierValue() {
        return identifierValue;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
