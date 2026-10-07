package com.rewit.infrastructure.persistence.adapter;

import com.rewit.application.dto.common.PageResult;
import com.rewit.application.port.UserFollowRepository;
import com.rewit.domain.enums.AccountStatus;
import com.rewit.domain.model.UserFollow;
import com.rewit.infrastructure.persistence.entity.UserFollowJpaEntity;
import com.rewit.infrastructure.persistence.repository.UserFollowJpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Adaptador de persistência para conexões de seguidores (UserFollowRepository).
 */
@Component
public class UserFollowRepositoryAdapter implements UserFollowRepository {

    private final UserFollowJpaRepository userFollowJpaRepository;

    public UserFollowRepositoryAdapter(UserFollowJpaRepository userFollowJpaRepository) {
        this.userFollowJpaRepository = userFollowJpaRepository;
    }

    @Override
    @Transactional
    public boolean follow(UUID followerUserId, UUID followedUserId) {
        if (followerUserId == null || followedUserId == null || followerUserId.equals(followedUserId)) {
            return false;
        }

        int rows = userFollowJpaRepository.insertFollowIfNotExists(
                UUID.randomUUID(),
                followerUserId,
                followedUserId,
                Instant.now()
        );
        return rows > 0;
    }

    @Override
    @Transactional
    public boolean unfollow(UUID followerUserId, UUID followedUserId) {
        if (followerUserId == null || followedUserId == null) {
            return false;
        }

        return userFollowJpaRepository.findByFollowerUserIdAndFollowedUserId(followerUserId, followedUserId)
                .map(entity -> {
                    userFollowJpaRepository.delete(entity);
                    userFollowJpaRepository.flush();
                    return true;
                })
                .orElse(false);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isFollowing(UUID followerUserId, UUID followedUserId) {
        if (followerUserId == null || followedUserId == null) {
            return false;
        }
        return userFollowJpaRepository.existsByFollowerUserIdAndFollowedUserId(followerUserId, followedUserId);
    }

    @Override
    @Transactional(readOnly = true)
    public long countFollowers(UUID userId) {
        if (userId == null) {
            return 0;
        }
        return userFollowJpaRepository.countVisibleFollowers(userId, AccountStatus.DELETED);
    }

    @Override
    @Transactional(readOnly = true)
    public long countFollowing(UUID userId) {
        if (userId == null) {
            return 0;
        }
        return userFollowJpaRepository.countVisibleFollowing(userId, AccountStatus.DELETED);
    }

    @Override
    @SuppressWarnings({"deprecation", "nullness"})
    @Transactional(readOnly = true)
    public PageResult<UserFollow> findFollowing(UUID followerUserId, int page, int size) {
        if (followerUserId == null || page < 0 || size <= 0) {
            return PageResult.of(List.of(), Math.max(page, 0), Math.max(size, 1), 0);
        }

        PageRequest pageRequest = PageRequest.of(
                page,
                size,
                Sort.sort(UserFollowJpaEntity.class)
                        .by((UserFollowJpaEntity entity) -> entity.getCreatedAt()).descending()
                        .and(Sort.sort(UserFollowJpaEntity.class).by((UserFollowJpaEntity entity) -> entity.getId()).ascending())
        );
        Page<UserFollowJpaEntity> entityPage = userFollowJpaRepository.findVisibleFollowing(followerUserId, AccountStatus.DELETED, pageRequest);

        List<UserFollow> content = entityPage.getContent().stream()
                .map(entity -> entity.toDomain())
                .toList();

        return new PageResult<>(
                content,
                entityPage.getNumber(),
                entityPage.getSize(),
                entityPage.getTotalElements(),
                entityPage.getTotalPages(),
                entityPage.isLast()
        );
    }

    @Override
    @SuppressWarnings({"deprecation", "nullness"})
    @Transactional(readOnly = true)
    public PageResult<UserFollow> findFollowers(UUID followedUserId, int page, int size) {
        if (followedUserId == null || page < 0 || size <= 0) {
            return PageResult.of(List.of(), Math.max(page, 0), Math.max(size, 1), 0);
        }

        PageRequest pageRequest = PageRequest.of(
                page,
                size,
                Sort.sort(UserFollowJpaEntity.class)
                        .by((UserFollowJpaEntity entity) -> entity.getCreatedAt()).descending()
                        .and(Sort.sort(UserFollowJpaEntity.class).by((UserFollowJpaEntity entity) -> entity.getId()).ascending())
        );
        Page<UserFollowJpaEntity> entityPage = userFollowJpaRepository.findVisibleFollowers(followedUserId, AccountStatus.DELETED, pageRequest);

        List<UserFollow> content = entityPage.getContent().stream()
                .map(entity -> entity.toDomain())
                .toList();

        return new PageResult<>(
                content,
                entityPage.getNumber(),
                entityPage.getSize(),
                entityPage.getTotalElements(),
                entityPage.getTotalPages(),
                entityPage.isLast()
        );
    }
}
