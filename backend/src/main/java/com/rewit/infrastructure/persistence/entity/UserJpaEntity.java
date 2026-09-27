package com.rewit.infrastructure.persistence.entity;

import com.rewit.domain.enums.AuthProvider;
import com.rewit.domain.model.User;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Entidade JPA mapeando a tabela users no PostgreSQL.
 * Valida com o schema gerado pelas migrations Flyway V1 -> V4.
 * Mantida estritamente isolada na camada de infraestrutura.
 */
@Entity
@Table(name = "users")
public class UserJpaEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "email", nullable = false, length = 255)
    private String email;

    @Column(name = "password_hash", length = 255)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "auth_provider", nullable = false, length = 32)
    private AuthProvider authProvider = AuthProvider.LOCAL;

    @Column(name = "provider_user_id", length = 128)
    private String providerUserId;

    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;

    @Column(name = "is_verified", nullable = false)
    private boolean isVerified = false;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public UserJpaEntity() {
    }

    @PrePersist
    protected void onCreate() {
        if (this.id == null) {
            this.id = UUID.randomUUID();
        }
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
        if (this.updatedAt == null) {
            this.updatedAt = Instant.now();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }

    /**
     * Converte esta entidade JPA para o modelo de domínio User.
     */
    public User toDomain() {
        return User.rehydrate(
                this.id,
                this.email,
                this.passwordHash,
                this.authProvider,
                this.providerUserId,
                this.isActive,
                this.isVerified,
                this.deletedAt,
                this.createdAt,
                this.updatedAt
        );
    }

    /**
     * Cria uma nova entidade JPA a partir do modelo de domínio User.
     */
    public static UserJpaEntity fromDomain(User user) {
        if (user == null) {
            return null;
        }
        UserJpaEntity entity = new UserJpaEntity();
        entity.setId(user.getId());
        entity.setEmail(user.getEmail());
        entity.setPasswordHash(user.getPasswordHash());
        entity.setAuthProvider(user.getAuthProvider());
        entity.setProviderUserId(user.getProviderUserId());
        entity.setActive(user.isActive());
        entity.setVerified(user.isVerified());
        entity.setDeletedAt(user.getDeletedAt());
        entity.setCreatedAt(user.getCreatedAt());
        entity.setUpdatedAt(user.getUpdatedAt());
        return entity;
    }

    /**
     * Atualiza os campos mutáveis desta entidade a partir de uma instância do domínio.
     */
    public void updateFromDomain(User user) {
        this.email = user.getEmail();
        this.passwordHash = user.getPasswordHash();
        this.authProvider = user.getAuthProvider();
        this.providerUserId = user.getProviderUserId();
        this.isActive = user.isActive();
        this.isVerified = user.isVerified();
        this.deletedAt = user.getDeletedAt();
        this.updatedAt = user.getUpdatedAt() != null ? user.getUpdatedAt() : Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public AuthProvider getAuthProvider() {
        return authProvider;
    }

    public void setAuthProvider(AuthProvider authProvider) {
        this.authProvider = authProvider;
    }

    public String getProviderUserId() {
        return providerUserId;
    }

    public void setProviderUserId(String providerUserId) {
        this.providerUserId = providerUserId;
    }

    public boolean isActive() {
        return isActive;
    }

    public void setActive(boolean active) {
        isActive = active;
    }

    public boolean isVerified() {
        return isVerified;
    }

    public void setVerified(boolean verified) {
        isVerified = verified;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(Instant deletedAt) {
        this.deletedAt = deletedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
