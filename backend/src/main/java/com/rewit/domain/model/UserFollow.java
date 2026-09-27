package com.rewit.domain.model;

import com.rewit.common.exception.BusinessException;

import java.time.Instant;
import java.util.UUID;

/**
 * Entidade de Domínio representando conexões sociais e relações de seguidor.
 */
public class UserFollow {

    private final UUID id;
    private final UUID followerUserId;
    private final UUID followedUserId;
    private final Instant createdAt;

    public UserFollow(UUID id, UUID followerUserId, UUID followedUserId) {
        if (followerUserId == null || followedUserId == null) {
            throw new BusinessException("Os identificadores de seguidor e seguido são obrigatórios", "MISSING_FOLLOW_IDS");
        }
        if (followerUserId.equals(followedUserId)) {
            throw new BusinessException("Um usuário não pode seguir a si mesmo", "SELF_FOLLOW_FORBIDDEN");
        }

        this.id = id != null ? id : UUID.randomUUID();
        this.followerUserId = followerUserId;
        this.followedUserId = followedUserId;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getFollowerUserId() {
        return followerUserId;
    }

    public UUID getFollowedUserId() {
        return followedUserId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
