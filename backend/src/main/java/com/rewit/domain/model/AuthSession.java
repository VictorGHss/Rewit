package com.rewit.domain.model;

import com.rewit.common.exception.BusinessException;

import java.time.Instant;
import java.util.UUID;

/**
 * Entidade de Domínio representando uma sessão de autenticação vinculada a um Refresh Token persistido.
 * Mantida estritamente desacoplada de frameworks ou anotações de persistência.
 */
public class AuthSession {

    private final UUID id;
    private final UUID userId;
    private final String tokenHash;
    private final Instant issuedAt;
    private final Instant expiresAt;
    private Instant revokedAt;
    private UUID replacedBySessionId;
    private final Instant createdAt;
    private Instant lastUsedAt;
    private final String userAgent;
    private final String ipAddress;

    public AuthSession(UUID id, UUID userId, String tokenHash, Instant expiresAt, String userAgent, String ipAddress) {
        if (userId == null) {
            throw new BusinessException("O identificador do usuário é obrigatório para a sessão", "MISSING_USER_ID");
        }
        if (tokenHash == null || tokenHash.isBlank()) {
            throw new BusinessException("O hash do token é obrigatório", "INVALID_TOKEN_HASH");
        }
        Instant now = Instant.now();
        if (expiresAt == null || expiresAt.isBefore(now)) {
            throw new BusinessException("A data de expiração da sessão deve ser futura", "INVALID_EXPIRATION");
        }

        this.id = id != null ? id : UUID.randomUUID();
        this.userId = userId;
        this.tokenHash = tokenHash.trim();
        this.issuedAt = now;
        this.expiresAt = expiresAt;
        this.revokedAt = null;
        this.replacedBySessionId = null;
        this.createdAt = now;
        this.lastUsedAt = now;
        this.userAgent = userAgent != null && !userAgent.isBlank() ? userAgent.trim() : null;
        this.ipAddress = ipAddress != null && !ipAddress.isBlank() ? ipAddress.trim() : null;
    }

    private AuthSession(UUID id, UUID userId, String tokenHash, Instant issuedAt, Instant expiresAt,
                        Instant revokedAt, UUID replacedBySessionId, Instant createdAt,
                        Instant lastUsedAt, String userAgent, String ipAddress) {
        this.id = id;
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
        this.revokedAt = revokedAt;
        this.replacedBySessionId = replacedBySessionId;
        this.createdAt = createdAt;
        this.lastUsedAt = lastUsedAt;
        this.userAgent = userAgent;
        this.ipAddress = ipAddress;
    }

    public static AuthSession rehydrate(UUID id, UUID userId, String tokenHash, Instant issuedAt,
                                        Instant expiresAt, Instant revokedAt, UUID replacedBySessionId,
                                        Instant createdAt, Instant lastUsedAt, String userAgent, String ipAddress) {
        if (id == null) {
            throw new BusinessException("O identificador da sessão é obrigatório", "MISSING_SESSION_ID");
        }
        if (userId == null) {
            throw new BusinessException("O identificador do usuário é obrigatório", "MISSING_USER_ID");
        }
        if (tokenHash == null || tokenHash.isBlank()) {
            throw new BusinessException("O hash do token é obrigatório", "INVALID_TOKEN_HASH");
        }
        return new AuthSession(id, userId, tokenHash.trim(),
                issuedAt != null ? issuedAt : Instant.now(),
                expiresAt, revokedAt, replacedBySessionId,
                createdAt != null ? createdAt : Instant.now(),
                lastUsedAt, userAgent, ipAddress);
    }

    public boolean isRevoked() {
        return this.revokedAt != null;
    }

    public boolean isExpired() {
        return this.expiresAt.isBefore(Instant.now());
    }

    public boolean isValid() {
        return !isRevoked() && !isExpired();
    }

    public void revoke() {
        this.revokedAt = Instant.now();
    }

    public void rotate(UUID newSessionId) {
        this.revokedAt = Instant.now();
        this.replacedBySessionId = newSessionId;
        this.lastUsedAt = Instant.now();
    }

    public void touch() {
        this.lastUsedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public UUID getReplacedBySessionId() {
        return replacedBySessionId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public String getIpAddress() {
        return ipAddress;
    }
}
