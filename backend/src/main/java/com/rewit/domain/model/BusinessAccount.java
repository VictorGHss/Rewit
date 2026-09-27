package com.rewit.domain.model;

import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.VerificationStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Entidade de Domínio representando contas empresariais vinculadas a estabelecimentos físicos.
 * Sem dependência de módulos de pagamento ou assinatura prematura (Seção 29).
 */
public class BusinessAccount {

    private final UUID id;
    private final UUID userId;
    private final String corporateName;
    private final String taxId;
    private VerificationStatus verificationStatus;
    private String planTier;
    private final Instant createdAt;
    private Instant updatedAt;

    public BusinessAccount(UUID id, UUID userId, String corporateName, String taxId,
                           VerificationStatus verificationStatus, String planTier) {
        if (userId == null) {
            throw new BusinessException("O usuário administrador da conta comercial é obrigatório", "MISSING_USER_ID");
        }
        if (corporateName == null || corporateName.isBlank()) {
            throw new BusinessException("A razão social é obrigatória", "MISSING_CORPORATE_NAME");
        }
        if (taxId == null || taxId.isBlank()) {
            throw new BusinessException("O documento fiscal (CNPJ/Tax ID) é obrigatório", "MISSING_TAX_ID");
        }

        this.id = id != null ? id : UUID.randomUUID();
        this.userId = userId;
        this.corporateName = corporateName.trim();
        this.taxId = taxId.trim();
        this.verificationStatus = verificationStatus != null ? verificationStatus : VerificationStatus.PENDING;
        this.planTier = planTier != null ? planTier : "FREE";
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getCorporateName() {
        return corporateName;
    }

    public String getTaxId() {
        return taxId;
    }

    public VerificationStatus getVerificationStatus() {
        return verificationStatus;
    }

    public String getPlanTier() {
        return planTier;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
