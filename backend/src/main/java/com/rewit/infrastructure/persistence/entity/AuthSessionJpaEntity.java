package com.rewit.infrastructure.persistence.entity;

import com.rewit.domain.model.AuthSession;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Entidade JPA mapeando a tabela auth_sessions no PostgreSQL.
 * Valida com o schema gerado pela migration Flyway V5.
 */
@Entity
@Table(name = "auth_sessions")
public class AuthSessionJpaEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserJpaEntity user;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "replaced_by_session_id")
    private UUID replacedBySessionId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "user_agent", columnDefinition = "TEXT")
    private String userAgent;

    @JdbcTypeCode(SqlTypes.INET)
    @Column(name = "ip_address", columnDefinition = "INET")
    private String ipAddress;

    public AuthSessionJpaEntity() {
    }

    @PrePersist
    protected void onCreate() {
        if (this.id == null) {
            this.id = UUID.randomUUID();
        }
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
        if (this.issuedAt == null) {
            this.issuedAt = Instant.now();
        }
    }

    public AuthSession toDomain() {
        UUID linkedUserId = this.user != null ? this.user.getId() : null;
        return AuthSession.rehydrate(
                this.id,
                linkedUserId,
                this.tokenHash,
                this.issuedAt,
                this.expiresAt,
                this.revokedAt,
                this.replacedBySessionId,
                this.createdAt,
                this.lastUsedAt,
                this.userAgent,
                this.ipAddress
        );
    }

    public static AuthSessionJpaEntity fromDomain(AuthSession session, UserJpaEntity userEntity) {
        if (session == null) {
            return null;
        }
        AuthSessionJpaEntity entity = new AuthSessionJpaEntity();
        entity.setId(session.getId());
        entity.setUser(userEntity);
        entity.setTokenHash(session.getTokenHash());
        entity.setIssuedAt(session.getIssuedAt());
        entity.setExpiresAt(session.getExpiresAt());
        entity.setRevokedAt(session.getRevokedAt());
        entity.setReplacedBySessionId(session.getReplacedBySessionId());
        entity.setCreatedAt(session.getCreatedAt());
        entity.setLastUsedAt(session.getLastUsedAt());
        entity.setUserAgent(session.getUserAgent());
        entity.setIpAddress(session.getIpAddress());
        return entity;
    }

    public void updateFromDomain(AuthSession session) {
        this.revokedAt = session.getRevokedAt();
        this.replacedBySessionId = session.getReplacedBySessionId();
        this.lastUsedAt = session.getLastUsedAt();
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UserJpaEntity getUser() {
        return user;
    }

    public void setUser(UserJpaEntity user) {
        this.user = user;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public void setTokenHash(String tokenHash) {
        this.tokenHash = tokenHash;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    public void setIssuedAt(Instant issuedAt) {
        this.issuedAt = issuedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public void setRevokedAt(Instant revokedAt) {
        this.revokedAt = revokedAt;
    }

    public UUID getReplacedBySessionId() {
        return replacedBySessionId;
    }

    public void setReplacedBySessionId(UUID replacedBySessionId) {
        this.replacedBySessionId = replacedBySessionId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public void setLastUsedAt(Instant lastUsedAt) {
        this.lastUsedAt = lastUsedAt;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }
}
