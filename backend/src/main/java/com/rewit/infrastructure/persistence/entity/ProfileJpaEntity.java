package com.rewit.infrastructure.persistence.entity;

import com.rewit.domain.model.Profile;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Entidade JPA mapeando a tabela profiles no PostgreSQL.
 * Valida com o schema gerado pelas migrations Flyway V1 -> V4.
 * Mantida estritamente isolada na camada de infraestrutura.
 */
@Entity
@Table(name = "profiles")
public class ProfileJpaEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private UserJpaEntity user;

    @Column(name = "handle", nullable = false, length = 64)
    private String handle;

    @Column(name = "display_name", nullable = false, length = 128)
    private String displayName;

    @Column(name = "bio", columnDefinition = "TEXT")
    private String bio;

    @Column(name = "avatar_url", columnDefinition = "TEXT")
    private String avatarUrl;

    @Column(name = "reputation_score", nullable = false)
    private int reputationScore = 0;

    @Column(name = "is_anonymous_default", nullable = false)
    private boolean isAnonymousDefault = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public ProfileJpaEntity() {
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
     * Converte esta entidade JPA para o modelo de domínio Profile.
     */
    public Profile toDomain() {
        UUID linkedUserId = this.user != null ? this.user.getId() : null;
        return Profile.rehydrate(
                this.id,
                linkedUserId,
                this.handle,
                this.displayName,
                this.bio,
                this.avatarUrl,
                this.reputationScore,
                this.isAnonymousDefault,
                this.createdAt,
                this.updatedAt
        );
    }

    /**
     * Cria uma nova entidade JPA a partir do modelo de domínio Profile e do vínculo com UserJpaEntity.
     */
    public static ProfileJpaEntity fromDomain(Profile profile, UserJpaEntity userEntity) {
        if (profile == null) {
            return null;
        }
        ProfileJpaEntity entity = new ProfileJpaEntity();
        entity.setId(profile.getId());
        entity.setUser(userEntity);
        entity.setHandle(profile.getHandle());
        entity.setDisplayName(profile.getDisplayName());
        entity.setBio(profile.getBio());
        entity.setAvatarUrl(profile.getAvatarUrl());
        entity.setReputationScore(profile.getReputationScore());
        entity.setAnonymousDefault(profile.isAnonymousDefault());
        entity.setCreatedAt(profile.getCreatedAt());
        entity.setUpdatedAt(profile.getUpdatedAt());
        return entity;
    }

    /**
     * Atualiza os campos mutáveis desta entidade a partir de uma instância do domínio.
     */
    public void updateFromDomain(Profile profile) {
        this.handle = profile.getHandle();
        this.displayName = profile.getDisplayName();
        this.bio = profile.getBio();
        this.avatarUrl = profile.getAvatarUrl();
        this.reputationScore = profile.getReputationScore();
        this.isAnonymousDefault = profile.isAnonymousDefault();
        this.updatedAt = profile.getUpdatedAt() != null ? profile.getUpdatedAt() : Instant.now();
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

    public String getHandle() {
        return handle;
    }

    public void setHandle(String handle) {
        this.handle = handle;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getBio() {
        return bio;
    }

    public void setBio(String bio) {
        this.bio = bio;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public void setAvatarUrl(String avatarUrl) {
        this.avatarUrl = avatarUrl;
    }

    public int getReputationScore() {
        return reputationScore;
    }

    public void setReputationScore(int reputationScore) {
        this.reputationScore = reputationScore;
    }

    public boolean isAnonymousDefault() {
        return isAnonymousDefault;
    }

    public void setAnonymousDefault(boolean anonymousDefault) {
        isAnonymousDefault = anonymousDefault;
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
