package com.rewit.domain.model;

import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.ActivityType;
import com.rewit.domain.enums.TargetType;

import java.time.Instant;
import java.util.UUID;

/**
 * Entidade de Domínio representando eventos de atividade e telemetria analítica para o sistema de recomendação futuro.
 * Não armazena localização contínua por design (Seção 25).
 */
public class UserActivity {

    private final UUID id;
    private final UUID userId;
    private final ActivityType activityType;
    private final TargetType targetType;
    private final UUID targetId;
    private final String metadataJson;
    private final Instant createdAt;

    public UserActivity(UUID id, UUID userId, ActivityType activityType,
                        TargetType targetType, UUID targetId, String metadataJson) {
        if (userId == null) {
            throw new BusinessException("O usuário é obrigatório", "MISSING_USER_ID");
        }
        if (activityType == null) {
            throw new BusinessException("O tipo de atividade é obrigatório", "MISSING_ACTIVITY_TYPE");
        }

        this.id = id != null ? id : UUID.randomUUID();
        this.userId = userId;
        this.activityType = activityType;
        this.targetType = targetType;
        this.targetId = targetId;
        this.metadataJson = metadataJson;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public ActivityType getActivityType() {
        return activityType;
    }

    public TargetType getTargetType() {
        return targetType;
    }

    public UUID getTargetId() {
        return targetId;
    }

    public String getMetadataJson() {
        return metadataJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
