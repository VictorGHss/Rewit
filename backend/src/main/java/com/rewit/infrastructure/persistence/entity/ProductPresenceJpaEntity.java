package com.rewit.infrastructure.persistence.entity;

import com.rewit.domain.enums.VerificationStatus;
import com.rewit.domain.model.ProductPresence;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Entidade JPA mapeando a tabela product_presences no PostgreSQL.
 * Valida com o schema gerado pela migration Flyway V1.
 */
@Entity
@Table(name = "product_presences")
public class ProductPresenceJpaEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "place_id", nullable = false)
    private UUID placeId;

    @Column(name = "first_discovered_at", nullable = false)
    private Instant firstDiscoveredAt = Instant.now();

    @Column(name = "last_confirmed_at", nullable = false)
    private Instant lastConfirmedAt = Instant.now();

    @Column(name = "reported_by_user_id")
    private UUID reportedByUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_status", nullable = false, length = 32)
    private VerificationStatus verificationStatus = VerificationStatus.UNCONFIRMED;

    @Column(name = "status", nullable = false, length = 32)
    private String status = "AVAILABLE";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public ProductPresenceJpaEntity() {
    }

    public ProductPresence toDomain() {
        return new ProductPresence(
                id,
                productId,
                placeId,
                reportedByUserId,
                verificationStatus,
                status
        );
    }

    public static ProductPresenceJpaEntity fromDomain(ProductPresence domain) {
        Objects.requireNonNull(domain, "domain product presence must not be null");

        ProductPresenceJpaEntity entity = new ProductPresenceJpaEntity();
        entity.id = domain.getId();
        entity.productId = domain.getProductId();
        entity.placeId = domain.getPlaceId();
        entity.firstDiscoveredAt = domain.getFirstDiscoveredAt() != null ? domain.getFirstDiscoveredAt() : Instant.now();
        entity.lastConfirmedAt = domain.getLastConfirmedAt() != null ? domain.getLastConfirmedAt() : Instant.now();
        entity.reportedByUserId = domain.getReportedByUserId();
        entity.verificationStatus = domain.getVerificationStatus() != null ? domain.getVerificationStatus() : VerificationStatus.UNCONFIRMED;
        entity.status = domain.getStatus() != null ? domain.getStatus() : "AVAILABLE";
        entity.createdAt = domain.getCreatedAt() != null ? domain.getCreatedAt() : Instant.now();
        return entity;
    }

    public void updateFromDomain(ProductPresence domain) {
        Objects.requireNonNull(domain, "domain product presence must not be null");
        this.lastConfirmedAt = Instant.now();
        this.verificationStatus = domain.getVerificationStatus();
        this.status = domain.getStatus();
    }

    // Getters and Setters
    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getProductId() {
        return productId;
    }

    public void setProductId(UUID productId) {
        this.productId = productId;
    }

    public UUID getPlaceId() {
        return placeId;
    }

    public void setPlaceId(UUID placeId) {
        this.placeId = placeId;
    }

    public Instant getFirstDiscoveredAt() {
        return firstDiscoveredAt;
    }

    public void setFirstDiscoveredAt(Instant firstDiscoveredAt) {
        this.firstDiscoveredAt = firstDiscoveredAt;
    }

    public Instant getLastConfirmedAt() {
        return lastConfirmedAt;
    }

    public void setLastConfirmedAt(Instant lastConfirmedAt) {
        this.lastConfirmedAt = lastConfirmedAt;
    }

    public UUID getReportedByUserId() {
        return reportedByUserId;
    }

    public void setReportedByUserId(UUID reportedByUserId) {
        this.reportedByUserId = reportedByUserId;
    }

    public VerificationStatus getVerificationStatus() {
        return verificationStatus;
    }

    public void setVerificationStatus(VerificationStatus verificationStatus) {
        this.verificationStatus = verificationStatus;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
