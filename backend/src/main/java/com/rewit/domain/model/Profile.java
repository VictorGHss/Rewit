package com.rewit.domain.model;

import com.rewit.common.exception.BusinessException;

import java.time.Instant;
import java.util.UUID;

/**
 * Entidade de Domínio representando o perfil público e reputação do usuário.
 */
public class Profile {

    private final UUID id;
    private final UUID userId;
    private String handle;
    private String displayName;
    private String bio;
    private String avatarUrl;
    private int reputationScore;
    private boolean isAnonymousDefault;
    private final Instant createdAt;
    private Instant updatedAt;

    public Profile(UUID id, UUID userId, String handle, String displayName, String bio, String avatarUrl) {
        if (userId == null) {
            throw new BusinessException("O identificador do usuário é obrigatório", "MISSING_USER_ID");
        }
        if (handle == null || handle.isBlank()) {
            throw new BusinessException("O nome de usuário (@handle) é obrigatório", "INVALID_HANDLE");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new BusinessException("O nome de exibição é obrigatório", "INVALID_DISPLAY_NAME");
        }

        this.id = id != null ? id : UUID.randomUUID();
        this.userId = userId;
        this.handle = handle.trim().toLowerCase().replaceAll("^@", "");
        this.displayName = displayName.trim();
        this.bio = bio;
        this.avatarUrl = avatarUrl;
        this.reputationScore = 0;
        this.isAnonymousDefault = false;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getHandle() {
        return handle;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getBio() {
        return bio;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public int getReputationScore() {
        return reputationScore;
    }

    public boolean isAnonymousDefault() {
        return isAnonymousDefault;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
