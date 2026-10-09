package com.rewit.infrastructure.persistence.entity;

import com.rewit.domain.enums.VerificationStatus;
import com.rewit.domain.model.BusinessAccount;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Entidade JPA da tabela business_accounts (V1).
 */
@Entity
@Table(name = "business_accounts")
public class BusinessAccountJpaEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "corporate_name", nullable = false, length = 255)
    private String corporateName;

    @Column(name = "tax_id", nullable = false, length = 32)
    private String taxId;

    @Column(name = "verification_status", nullable = false, length = 32)
    private String verificationStatus;

    @Column(name = "plan_tier", nullable = false, length = 32)
    private String planTier;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected BusinessAccountJpaEntity() {
    }

    public static BusinessAccountJpaEntity fromDomain(BusinessAccount account) {
        BusinessAccountJpaEntity entity = new BusinessAccountJpaEntity();
        entity.id = account.getId();
        entity.userId = account.getUserId();
        entity.updateFromDomain(account);
        entity.createdAt = account.getCreatedAt();
        return entity;
    }

    /** Campos mutáveis; id, usuário e criação não mudam. */
    public void updateFromDomain(BusinessAccount account) {
        this.corporateName = account.getCorporateName();
        this.taxId = account.getTaxId();
        this.verificationStatus = account.getVerificationStatus().name();
        this.planTier = account.getPlanTier();
        this.updatedAt = account.getUpdatedAt();
    }

    public BusinessAccount toDomain() {
        return new BusinessAccount(id, userId, corporateName, taxId, VerificationStatus.valueOf(verificationStatus),
                planTier, createdAt, updatedAt);
    }

    public UUID getId() {
        return id;
    }
}
