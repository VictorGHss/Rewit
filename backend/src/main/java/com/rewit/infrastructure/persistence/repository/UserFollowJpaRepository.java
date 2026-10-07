package com.rewit.infrastructure.persistence.repository;

import com.rewit.domain.enums.AccountStatus;
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

    // Leituras públicas (C2): vínculos com uma conta DELETED não aparecem nas listas nem nos contadores. O vínculo
    // continua no banco até o purge físico, que o remove em cascata (FKs ON DELETE CASCADE).

    @Query("""
        SELECT COUNT(f) FROM UserFollowJpaEntity f
        WHERE f.followedUserId = :followedUserId
          AND NOT EXISTS (SELECT 1 FROM UserJpaEntity u WHERE u.id = f.followerUserId AND u.accountStatus = :deleted)
        """)
    long countVisibleFollowers(@Param("followedUserId") UUID followedUserId, @Param("deleted") AccountStatus deleted);

    @Query("""
        SELECT COUNT(f) FROM UserFollowJpaEntity f
        WHERE f.followerUserId = :followerUserId
          AND NOT EXISTS (SELECT 1 FROM UserJpaEntity u WHERE u.id = f.followedUserId AND u.accountStatus = :deleted)
        """)
    long countVisibleFollowing(@Param("followerUserId") UUID followerUserId, @Param("deleted") AccountStatus deleted);

    @Query(value = """
        SELECT f FROM UserFollowJpaEntity f
        WHERE f.followerUserId = :followerUserId
          AND NOT EXISTS (SELECT 1 FROM UserJpaEntity u WHERE u.id = f.followedUserId AND u.accountStatus = :deleted)
        """, countQuery = """
        SELECT COUNT(f) FROM UserFollowJpaEntity f
        WHERE f.followerUserId = :followerUserId
          AND NOT EXISTS (SELECT 1 FROM UserJpaEntity u WHERE u.id = f.followedUserId AND u.accountStatus = :deleted)
        """)
    Page<UserFollowJpaEntity> findVisibleFollowing(@Param("followerUserId") UUID followerUserId,
                                                   @Param("deleted") AccountStatus deleted, Pageable pageable);

    @Query(value = """
        SELECT f FROM UserFollowJpaEntity f
        WHERE f.followedUserId = :followedUserId
          AND NOT EXISTS (SELECT 1 FROM UserJpaEntity u WHERE u.id = f.followerUserId AND u.accountStatus = :deleted)
        """, countQuery = """
        SELECT COUNT(f) FROM UserFollowJpaEntity f
        WHERE f.followedUserId = :followedUserId
          AND NOT EXISTS (SELECT 1 FROM UserJpaEntity u WHERE u.id = f.followerUserId AND u.accountStatus = :deleted)
        """)
    Page<UserFollowJpaEntity> findVisibleFollowers(@Param("followedUserId") UUID followedUserId,
                                                   @Param("deleted") AccountStatus deleted, Pageable pageable);

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
