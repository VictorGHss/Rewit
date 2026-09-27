package com.rewit.domain.model;

import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.TargetType;

import java.time.Instant;
import java.util.UUID;

/**
 * Raiz polimórfica relacional (ADR-009) para qualquer entidade que pode receber avaliações.
 */
public class RateableTarget {

    private final UUID id;
    private final TargetType targetType;
    private final Instant createdAt;

    public RateableTarget(UUID id, TargetType targetType) {
        if (targetType == null) {
            throw new BusinessException("O tipo do alvo avaliável não pode ser nulo", "INVALID_TARGET_TYPE");
        }
        this.id = id != null ? id : UUID.randomUUID();
        this.targetType = targetType;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public TargetType getTargetType() {
        return targetType;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
