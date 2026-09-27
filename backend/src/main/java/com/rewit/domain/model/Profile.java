package com.rewit.domain.model;

import com.rewit.common.exception.BusinessException;

import java.time.Instant;
import java.util.UUID;

/**
 * Entidade de Domínio representando o perfil público e reputação do usuário.
 * Mantida estritamente desacoplada de JPA, Spring ou anotações de persistência.
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
        this.id = id != null ? id : UUID.randomUUID();
        this.userId = userId;
        this.handle = normalizeHandle(handle);
        this.displayName = validateDisplayName(displayName);
        this.bio = bio != null && !bio.isBlank() ? bio.trim() : null;
        this.avatarUrl = avatarUrl != null && !avatarUrl.isBlank() ? avatarUrl.trim() : null;
        this.reputationScore = 0;
        this.isAnonymousDefault = false;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    private Profile(UUID id, UUID userId, String handle, String displayName,
                    String bio, String avatarUrl, int reputationScore,
                    boolean isAnonymousDefault, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.userId = userId;
        this.handle = handle;
        this.displayName = displayName;
        this.bio = bio;
        this.avatarUrl = avatarUrl;
        this.reputationScore = reputationScore;
        this.isAnonymousDefault = isAnonymousDefault;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /**
     * Normaliza e valida o nome de usuário (@handle): trim, lowercase e remoção de arroba inicial.
     */
    public static String normalizeHandle(String handle) {
        if (handle == null || handle.isBlank()) {
            throw new BusinessException("O nome de usuário (@handle) é obrigatório", "INVALID_HANDLE");
        }
        String normalized = handle.trim().toLowerCase().replaceAll("^@", "");
        if (normalized.isBlank()) {
            throw new BusinessException("O nome de usuário (@handle) é inválido", "INVALID_HANDLE");
        }
        return normalized;
    }

    private static String validateDisplayName(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            throw new BusinessException("O nome de exibição é obrigatório", "INVALID_DISPLAY_NAME");
        }
        return displayName.trim();
    }

    /**
     * Reconstrói uma instância de Profile a partir da camada de persistência.
     */
    public static Profile rehydrate(UUID id, UUID userId, String handle, String displayName,
                                    String bio, String avatarUrl, int reputationScore,
                                    boolean isAnonymousDefault, Instant createdAt, Instant updatedAt) {
        if (id == null) {
            throw new BusinessException("O identificador do perfil é obrigatório", "MISSING_PROFILE_ID");
        }
        if (userId == null) {
            throw new BusinessException("O identificador do usuário é obrigatório", "MISSING_USER_ID");
        }
        if (reputationScore < 0) {
            throw new BusinessException("A pontuação de reputação não pode ser negativa", "INVALID_REPUTATION");
        }
        return new Profile(id, userId, normalizeHandle(handle), validateDisplayName(displayName),
                bio != null && !bio.isBlank() ? bio.trim() : null,
                avatarUrl != null && !avatarUrl.isBlank() ? avatarUrl.trim() : null,
                reputationScore, isAnonymousDefault,
                createdAt != null ? createdAt : Instant.now(),
                updatedAt != null ? updatedAt : Instant.now());
    }

    public void updateProfile(String displayName, String bio, String avatarUrl) {
        this.displayName = validateDisplayName(displayName);
        this.bio = bio != null && !bio.isBlank() ? bio.trim() : null;
        this.avatarUrl = avatarUrl != null && !avatarUrl.isBlank() ? avatarUrl.trim() : null;
        this.updatedAt = Instant.now();
    }

    public void adjustReputation(int delta) {
        int newScore = this.reputationScore + delta;
        if (newScore < 0) {
            throw new BusinessException("A pontuação de reputação não pode ser negativa", "INVALID_REPUTATION");
        }
        this.reputationScore = newScore;
        this.updatedAt = Instant.now();
    }

    public void setAnonymousDefault(boolean anonymousDefault) {
        this.isAnonymousDefault = anonymousDefault;
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
