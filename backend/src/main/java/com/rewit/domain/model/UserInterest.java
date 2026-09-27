package com.rewit.domain.model;

import com.rewit.common.exception.BusinessException;

import java.time.Instant;
import java.util.UUID;

/**
 * Entidade de Domínio representando áreas de interesse cadastradas pelo usuário.
 */
public class UserInterest {

    private final UUID id;
    private final UUID userId;
    private final String interestName;
    private final String categoryCode;
    private final Instant createdAt;

    public UserInterest(UUID id, UUID userId, String interestName, String categoryCode) {
        if (userId == null) {
            throw new BusinessException("O usuário é obrigatório", "MISSING_USER_ID");
        }
        if (interestName == null || interestName.isBlank()) {
            throw new BusinessException("O nome do interesse é obrigatório", "MISSING_INTEREST_NAME");
        }
        if (categoryCode == null || categoryCode.isBlank()) {
            throw new BusinessException("O código da categoria é obrigatório", "MISSING_CATEGORY_CODE");
        }

        this.id = id != null ? id : UUID.randomUUID();
        this.userId = userId;
        this.interestName = interestName.trim();
        this.categoryCode = categoryCode.trim().toUpperCase();
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getInterestName() {
        return interestName;
    }

    public String getCategoryCode() {
        return categoryCode;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
