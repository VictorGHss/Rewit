package com.rewit.infrastructure.persistence.repository;

import com.rewit.infrastructure.persistence.entity.ReviewJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * Repositório Spring Data JPA para persistência e consultas básicas e paginadas de ReviewJpaEntity.
 */
@Repository
public interface ReviewJpaRepository extends JpaRepository<ReviewJpaEntity, UUID> {

    List<ReviewJpaEntity> findByUserId(UUID userId);

    Page<ReviewJpaEntity> findByUserId(UUID userId, Pageable pageable);

    List<ReviewJpaEntity> findByContextPlaceId(UUID contextPlaceId);

    List<ReviewJpaEntity> findByStatus(String status);

    @Query(value = """
        SELECT r, rt FROM ReviewJpaEntity r
        JOIN ReviewTargetJpaEntity rt ON rt.reviewId = r.id AND rt.targetId = :targetId
        WHERE r.status = 'ACTIVE'
          AND (
            r.visibility = 'PUBLIC'
            OR (:requesterUserId IS NOT NULL AND r.userId = :requesterUserId)
            OR (
              :requesterUserId IS NOT NULL
              AND r.visibility = 'FOLLOWERS'
              AND EXISTS (
                SELECT 1 FROM UserFollowJpaEntity uf
                WHERE uf.followerUserId = :requesterUserId
                  AND uf.followedUserId = r.userId
              )
            )
          )
          AND (:verifiedOnly = FALSE OR r.isVerifiedOnSite = TRUE)
        ORDER BY r.createdAt DESC, r.id ASC
    """,
    countQuery = """
        SELECT COUNT(DISTINCT r.id) FROM ReviewJpaEntity r
        JOIN ReviewTargetJpaEntity rt ON rt.reviewId = r.id AND rt.targetId = :targetId
        WHERE r.status = 'ACTIVE'
          AND (
            r.visibility = 'PUBLIC'
            OR (:requesterUserId IS NOT NULL AND r.userId = :requesterUserId)
            OR (
              :requesterUserId IS NOT NULL
              AND r.visibility = 'FOLLOWERS'
              AND EXISTS (
                SELECT 1 FROM UserFollowJpaEntity uf
                WHERE uf.followerUserId = :requesterUserId
                  AND uf.followedUserId = r.userId
              )
            )
          )
          AND (:verifiedOnly = FALSE OR r.isVerifiedOnSite = TRUE)
    """)
    Page<Object[]> findReviewsByTargetNewest(
            @Param("targetId") UUID targetId,
            @Param("requesterUserId") UUID requesterUserId,
            @Param("verifiedOnly") boolean verifiedOnly,
            Pageable pageable
    );

    @Query(value = """
        SELECT r, rt FROM ReviewJpaEntity r
        JOIN ReviewTargetJpaEntity rt ON rt.reviewId = r.id AND rt.targetId = :targetId
        WHERE r.status = 'ACTIVE'
          AND (
            r.visibility = 'PUBLIC'
            OR (:requesterUserId IS NOT NULL AND r.userId = :requesterUserId)
            OR (
              :requesterUserId IS NOT NULL
              AND r.visibility = 'FOLLOWERS'
              AND EXISTS (
                SELECT 1 FROM UserFollowJpaEntity uf
                WHERE uf.followerUserId = :requesterUserId
                  AND uf.followedUserId = r.userId
              )
            )
          )
          AND (:verifiedOnly = FALSE OR r.isVerifiedOnSite = TRUE)
        ORDER BY rt.rating DESC, r.createdAt DESC, r.id ASC
    """,
    countQuery = """
        SELECT COUNT(DISTINCT r.id) FROM ReviewJpaEntity r
        JOIN ReviewTargetJpaEntity rt ON rt.reviewId = r.id AND rt.targetId = :targetId
        WHERE r.status = 'ACTIVE'
          AND (
            r.visibility = 'PUBLIC'
            OR (:requesterUserId IS NOT NULL AND r.userId = :requesterUserId)
            OR (
              :requesterUserId IS NOT NULL
              AND r.visibility = 'FOLLOWERS'
              AND EXISTS (
                SELECT 1 FROM UserFollowJpaEntity uf
                WHERE uf.followerUserId = :requesterUserId
                  AND uf.followedUserId = r.userId
              )
            )
          )
          AND (:verifiedOnly = FALSE OR r.isVerifiedOnSite = TRUE)
    """)
    Page<Object[]> findReviewsByTargetRatingDesc(
            @Param("targetId") UUID targetId,
            @Param("requesterUserId") UUID requesterUserId,
            @Param("verifiedOnly") boolean verifiedOnly,
            Pageable pageable
    );

    @Query(value = """
        SELECT r, rt FROM ReviewJpaEntity r
        JOIN ReviewTargetJpaEntity rt ON rt.reviewId = r.id AND rt.targetId = :targetId
        WHERE r.status = 'ACTIVE'
          AND (
            r.visibility = 'PUBLIC'
            OR (:requesterUserId IS NOT NULL AND r.userId = :requesterUserId)
            OR (
              :requesterUserId IS NOT NULL
              AND r.visibility = 'FOLLOWERS'
              AND EXISTS (
                SELECT 1 FROM UserFollowJpaEntity uf
                WHERE uf.followerUserId = :requesterUserId
                  AND uf.followedUserId = r.userId
              )
            )
          )
          AND (:verifiedOnly = FALSE OR r.isVerifiedOnSite = TRUE)
        ORDER BY rt.rating ASC, r.createdAt DESC, r.id ASC
    """,
    countQuery = """
        SELECT COUNT(DISTINCT r.id) FROM ReviewJpaEntity r
        JOIN ReviewTargetJpaEntity rt ON rt.reviewId = r.id AND rt.targetId = :targetId
        WHERE r.status = 'ACTIVE'
          AND (
            r.visibility = 'PUBLIC'
            OR (:requesterUserId IS NOT NULL AND r.userId = :requesterUserId)
            OR (
              :requesterUserId IS NOT NULL
              AND r.visibility = 'FOLLOWERS'
              AND EXISTS (
                SELECT 1 FROM UserFollowJpaEntity uf
                WHERE uf.followerUserId = :requesterUserId
                  AND uf.followedUserId = r.userId
              )
            )
          )
          AND (:verifiedOnly = FALSE OR r.isVerifiedOnSite = TRUE)
    """)
    Page<Object[]> findReviewsByTargetRatingAsc(
            @Param("targetId") UUID targetId,
            @Param("requesterUserId") UUID requesterUserId,
            @Param("verifiedOnly") boolean verifiedOnly,
            Pageable pageable
    );

    @Query(value = """
        SELECT r FROM ReviewJpaEntity r
        JOIN UserFollowJpaEntity uf ON uf.followedUserId = r.userId
        WHERE uf.followerUserId = :requesterUserId
          AND r.status = 'ACTIVE'
          AND r.visibility IN ('PUBLIC', 'FOLLOWERS')
        ORDER BY r.createdAt DESC, r.id ASC
    """,
    countQuery = """
        SELECT COUNT(DISTINCT r.id) FROM ReviewJpaEntity r
        JOIN UserFollowJpaEntity uf ON uf.followedUserId = r.userId
        WHERE uf.followerUserId = :requesterUserId
          AND r.status = 'ACTIVE'
          AND r.visibility IN ('PUBLIC', 'FOLLOWERS')
    """)
    Page<ReviewJpaEntity> findFeedByFollowing(
            @Param("requesterUserId") UUID requesterUserId,
            Pageable pageable
    );
}
