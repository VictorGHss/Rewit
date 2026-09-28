package com.rewit.infrastructure.persistence.entity;

import com.rewit.domain.model.UserFollow;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Entidade JPA mapeando a tabela user_follows no PostgreSQL (Seção 27 / Step 15.0).
 */
@Entity
@Table(name = "user_follows")
public class UserFollowJpaEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "follower_user_id", nullable = false)
    private UUID followerUserId;

    @Column(name = "followed_user_id", nullable = false)
    private UUID followedUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public UserFollowJpaEntity() {}

    public UserFollowJpaEntity(UUID id, UUID followerUserId, UUID followedUserId, Instant createdAt) {
        this.id = id != null ? id : UUID.randomUUID();
        this.followerUserId = followerUserId;
        this.followedUserId = followedUserId;
        this.createdAt = createdAt != null ? createdAt : Instant.now();
    }

    public static UserFollowJpaEntity fromDomain(UserFollow domain) {
        if (domain == null) {
            return null;
        }
        return new UserFollowJpaEntity(
                domain.getId(),
                domain.getFollowerUserId(),
                domain.getFollowedUserId(),
                domain.getCreatedAt()
        );
    }

    public UserFollow toDomain() {
        return new UserFollow(
                this.id,
                this.followerUserId,
                this.followedUserId,
                this.createdAt
        );
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getFollowerUserId() {
        return followerUserId;
    }

    public void setFollowerUserId(UUID followerUserId) {
        this.followerUserId = followerUserId;
    }

    public UUID getFollowedUserId() {
        return followedUserId;
    }

    public void setFollowedUserId(UUID followedUserId) {
        this.followedUserId = followedUserId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        UserFollowJpaEntity that = (UserFollowJpaEntity) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
