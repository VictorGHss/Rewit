package com.rewit.domain.model;

import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.AuthProvider;

import java.time.Instant;
import java.util.UUID;

/**
 * Entidade de Domínio representando a conta e identidade interna do usuário na plataforma.
 * Mantida estritamente desacoplada de JPA, Spring ou anotações de persistência.
 */
public class User {

    private final UUID id;
    private final String email;
    private String passwordHash;
    private final AuthProvider authProvider;
    private final String providerUserId;
    private boolean isActive;
    private boolean isVerified;
    private Instant deletedAt;
    private final Instant createdAt;
    private Instant updatedAt;

    public User(UUID id, String email, String passwordHash, AuthProvider authProvider, String providerUserId) {
        this.id = id != null ? id : UUID.randomUUID();
        this.email = normalizeEmail(email);
        this.passwordHash = passwordHash;
        this.authProvider = authProvider != null ? authProvider : AuthProvider.LOCAL;
        this.providerUserId = providerUserId != null && !providerUserId.isBlank() ? providerUserId.trim() : null;
        this.isActive = true;
        this.isVerified = false;
        this.deletedAt = null;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    private User(UUID id, String email, String passwordHash, AuthProvider authProvider,
                 String providerUserId, boolean isActive, boolean isVerified,
                 Instant deletedAt, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.email = email;
        this.passwordHash = passwordHash;
        this.authProvider = authProvider;
        this.providerUserId = providerUserId;
        this.isActive = isActive;
        this.isVerified = isVerified;
        this.deletedAt = deletedAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /**
     * Normaliza e valida o formato básico do endereço de e-mail (lowercase e trim).
     */
    public static String normalizeEmail(String email) {
        if (email == null || email.isBlank() || !email.contains("@")) {
            throw new BusinessException("E-mail inválido ou ausente", "INVALID_EMAIL");
        }
        return email.trim().toLowerCase();
    }

    /**
     * Reconstrói uma instância de User a partir da camada de persistência.
     */
    public static User rehydrate(UUID id, String email, String passwordHash, AuthProvider authProvider,
                                 String providerUserId, boolean isActive, boolean isVerified,
                                 Instant deletedAt, Instant createdAt, Instant updatedAt) {
        if (id == null) {
            throw new BusinessException("O identificador do usuário é obrigatório", "MISSING_USER_ID");
        }
        return new User(id, normalizeEmail(email), passwordHash,
                authProvider != null ? authProvider : AuthProvider.LOCAL,
                providerUserId != null && !providerUserId.isBlank() ? providerUserId.trim() : null,
                isActive, isVerified, deletedAt,
                createdAt != null ? createdAt : Instant.now(),
                updatedAt != null ? updatedAt : Instant.now());
    }

    public void softDelete() {
        this.isActive = false;
        this.deletedAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public boolean isDeleted() {
        return this.deletedAt != null;
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public AuthProvider getAuthProvider() {
        return authProvider;
    }

    public String getProviderUserId() {
        return providerUserId;
    }

    public boolean isActive() {
        return isActive;
    }

    public boolean isVerified() {
        return isVerified;
    }

    public void setVerified(boolean verified) {
        this.isVerified = verified;
        this.updatedAt = Instant.now();
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void changePassword(String newPasswordHash) {
        if (this.authProvider != AuthProvider.LOCAL) {
            throw new BusinessException("Alteração de senha permitida apenas para contas locais", "LOCAL_AUTH_REQUIRED");
        }
        if (newPasswordHash == null || newPasswordHash.isBlank()) {
            throw new BusinessException("O hash da nova senha é obrigatório", "INVALID_PASSWORD_HASH");
        }
        this.passwordHash = newPasswordHash;
        this.updatedAt = Instant.now();
    }
}
