package com.rewit.domain.model;

import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.VerificationStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Entidade de Domínio representando a presença física de um produto global em um estabelecimento específico.
 */
public class ProductPresence {

    private final UUID id;
    private final UUID productId;
    private final UUID placeId;
    private final Instant firstDiscoveredAt;
    private Instant lastConfirmedAt;
    private final UUID reportedByUserId;
    private VerificationStatus verificationStatus;
    private String status;
    private final Instant createdAt;

    public ProductPresence(UUID id, UUID productId, UUID placeId, UUID reportedByUserId,
                           VerificationStatus verificationStatus, String status) {
        if (productId == null) {
            throw new BusinessException("O produto é obrigatório", "MISSING_PRODUCT_ID");
        }
        if (placeId == null) {
            throw new BusinessException("O local é obrigatório", "MISSING_PLACE_ID");
        }

        this.id = id != null ? id : UUID.randomUUID();
        this.productId = productId;
        this.placeId = placeId;
        this.reportedByUserId = reportedByUserId;
        this.firstDiscoveredAt = Instant.now();
        this.lastConfirmedAt = Instant.now();
        this.verificationStatus = verificationStatus != null ? verificationStatus : VerificationStatus.UNCONFIRMED;
        this.status = status != null ? status : "AVAILABLE";
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getProductId() {
        return productId;
    }

    public UUID getPlaceId() {
        return placeId;
    }

    public Instant getFirstDiscoveredAt() {
        return firstDiscoveredAt;
    }

    public Instant getLastConfirmedAt() {
        return lastConfirmedAt;
    }

    public UUID getReportedByUserId() {
        return reportedByUserId;
    }

    public VerificationStatus getVerificationStatus() {
        return verificationStatus;
    }

    public String getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
