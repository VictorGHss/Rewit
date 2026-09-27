package com.rewit.domain.model;

import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.AuthProvider;

import java.time.Instant;
import java.util.UUID;

/**
 * Entidade de Domínio representando a conta e identidade interna do usuário na plataforma.
 */
public class User {

    private final UUID id;
    private final String email;
    private final String passwordHash;
    private final AuthProvider authProvider;
    private final String providerUserId;
    private boolean isActive;
    private boolean isVerified;
    private Instant deletedAt;
    private final Instant createdAt;
    private Instant updatedAt;

    public User(UUID id, String email, String passwordHash, AuthProvider authProvider, String providerUserId) {
        if (email == null || email.isBlank() || !email.contains("@")) {
            throw new BusinessException("E-mail inválido ou ausente", "INVALID_EMAIL");
        }
        this.id = id != null ? id : UUID.randomUUID();
        this.email = email.trim().toLowerCase();
        this.passwordHash = passwordHash;
        this.authProvider = authProvider != null ? authProvider : AuthProvider.LOCAL;
        this.providerUserId = providerUserId;
        this.isActive = true;
        this.isVerified = false;
        this.deletedAt = null;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
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
}
