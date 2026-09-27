package com.rewit.domain.model;

import com.rewit.common.exception.BusinessException;

import java.time.Instant;
import java.util.UUID;

/**
 * Entidade de Domínio representando uma tag de taxonomia contextual (ex: ATENDIMENTO, LIMPEZA, QUALIDADE).
 */
public class Tag {

    private final UUID id;
    private final String code;
    private final String displayName;
    private final String category;
    private final Instant createdAt;

    public Tag(UUID id, String code, String displayName, String category) {
        if (code == null || code.isBlank()) {
            throw new BusinessException("O código da tag é obrigatório", "MISSING_TAG_CODE");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new BusinessException("O nome de exibição da tag é obrigatório", "MISSING_TAG_NAME");
        }

        this.id = id != null ? id : UUID.randomUUID();
        this.code = code.trim().toUpperCase();
        this.displayName = displayName.trim();
        this.category = category != null ? category.trim() : "EXPERIENCIA";
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getCategory() {
        return category;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
