package com.rewit.infrastructure.persistence.repository;

import com.rewit.infrastructure.persistence.entity.ReviewJpaEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repositório Spring Data JPA para persistência e consultas básicas e paginadas de ReviewJpaEntity.
 */
public interface ReviewJpaRepository extends JpaRepository<ReviewJpaEntity, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM ReviewJpaEntity r WHERE r.id = :id")
    Optional<ReviewJpaEntity> findByIdForUpdate(@Param("id") UUID id);

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

    long countByUserIdAndStatus(UUID userId, String status);

    long countByUserIdAndStatusAndIsVerifiedOnSiteTrue(UUID userId, String status);

    // ------------------------------------------------------------------
    // Queries de Reputação V1 (Step 23.0)
    // Reviews anônimas excluídas conforme ADR-008, Seção 3.
    // ------------------------------------------------------------------

    /**
     * Conta reviews ACTIVE não-anônimas do usuário.
     * Sinal: activeReviews na reputação V1.
     */
    @Query("""
        SELECT COUNT(r)
        FROM ReviewJpaEntity r
        WHERE r.userId = :userId
          AND r.status = 'ACTIVE'
          AND r.isAnonymous = FALSE
    """)
    long countActiveNonAnonByUserId(@Param("userId") UUID userId);

    /**
     * Conta reviews ACTIVE, verificadas e não-anônimas do usuário.
     * Sinal: verifiedReviews na reputação V1.
     */
    @Query("""
        SELECT COUNT(r)
        FROM ReviewJpaEntity r
        WHERE r.userId = :userId
          AND r.status = 'ACTIVE'
          AND r.isAnonymous = FALSE
          AND r.isVerifiedOnSite = TRUE
    """)
    long countActiveNonAnonVerifiedByUserId(@Param("userId") UUID userId);

    /**
     * Conta targets DISTINTOS avaliados em reviews ACTIVE e não-anônimas do usuário.
     * Sinal: distinctTargetsReviewed na reputação V1.
     */
    @Query("""
        SELECT COUNT(DISTINCT rt.targetId)
        FROM ReviewTargetJpaEntity rt
        JOIN ReviewJpaEntity r ON r.id = rt.reviewId
        WHERE r.userId = :userId
          AND r.status = 'ACTIVE'
          AND r.isAnonymous = FALSE
    """)
    long countDistinctTargetsByUserIdActiveNonAnon(@Param("userId") UUID userId);
}
