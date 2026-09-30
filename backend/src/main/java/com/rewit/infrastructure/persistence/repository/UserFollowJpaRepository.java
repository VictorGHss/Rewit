package com.rewit.infrastructure.persistence.repository;

import com.rewit.infrastructure.persistence.entity.UserFollowJpaEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Repositório Spring Data JPA para conexões sociais entre usuários (user_follows).
 */
public interface UserFollowJpaRepository extends JpaRepository<UserFollowJpaEntity, UUID> {

    boolean existsByFollowerUserIdAndFollowedUserId(UUID followerUserId, UUID followedUserId);

    Optional<UserFollowJpaEntity> findByFollowerUserIdAndFollowedUserId(UUID followerUserId, UUID followedUserId);

    void deleteByFollowerUserIdAndFollowedUserId(UUID followerUserId, UUID followedUserId);

    long countByFollowedUserId(UUID followedUserId);

    long countByFollowerUserId(UUID followerUserId);

    Page<UserFollowJpaEntity> findByFollowerUserId(UUID followerUserId, Pageable pageable);

    Page<UserFollowJpaEntity> findByFollowedUserId(UUID followedUserId, Pageable pageable);

    @Modifying
    @Query(value = """
        INSERT INTO user_follows (id, follower_user_id, followed_user_id, created_at)
        VALUES (:id, :followerUserId, :followedUserId, :createdAt)
        ON CONFLICT (follower_user_id, followed_user_id) DO NOTHING
    """, nativeQuery = true)
    int insertFollowIfNotExists(
            @Param("id") UUID id,
            @Param("followerUserId") UUID followerUserId,
            @Param("followedUserId") UUID followedUserId,
            @Param("createdAt") Instant createdAt
    );
}
