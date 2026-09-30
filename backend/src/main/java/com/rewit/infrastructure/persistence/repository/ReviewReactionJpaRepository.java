package com.rewit.infrastructure.persistence.repository;

import com.rewit.infrastructure.persistence.entity.ReviewReactionJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Repositório Spring Data JPA para reações em avaliações (review_reactions).
 */
@Repository
public interface ReviewReactionJpaRepository extends JpaRepository<ReviewReactionJpaEntity, UUID> {

    boolean existsByReviewIdAndUserIdAndReactionType(UUID reviewId, UUID userId, String reactionType);

    void deleteByReviewIdAndUserIdAndReactionType(UUID reviewId, UUID userId, String reactionType);

    long countByReviewIdAndReactionType(UUID reviewId, String reactionType);

    @Modifying
    @Query(value = """
        INSERT INTO review_reactions (id, review_id, user_id, reaction_type, created_at)
        VALUES (:id, :reviewId, :userId, 'HELPFUL', :createdAt)
        ON CONFLICT (review_id, user_id, reaction_type) DO NOTHING
    """, nativeQuery = true)
    int insertHelpfulIfNotExists(
            @Param("id") UUID id,
            @Param("reviewId") UUID reviewId,
            @Param("userId") UUID userId,
            @Param("createdAt") Instant createdAt
    );

    @Query("""
        SELECT r.reviewId, COUNT(r)
        FROM ReviewReactionJpaEntity r
        WHERE r.reactionType = 'HELPFUL'
          AND r.reviewId IN (:reviewIds)
        GROUP BY r.reviewId
    """)
    List<Object[]> countHelpfulByReviewIds(@Param("reviewIds") Collection<UUID> reviewIds);

    @Query("""
        SELECT r.reviewId
        FROM ReviewReactionJpaEntity r
        WHERE r.reactionType = 'HELPFUL'
          AND r.userId = :userId
          AND r.reviewId IN (:reviewIds)
    """)
    List<UUID> findHelpfulReviewIdsByUser(
            @Param("reviewIds") Collection<UUID> reviewIds,
            @Param("userId") UUID userId
    );

    @Query("""
        SELECT COUNT(rr)
        FROM ReviewReactionJpaEntity rr
        JOIN ReviewJpaEntity r ON r.id = rr.reviewId
        WHERE r.userId = :userId
          AND r.status = 'ACTIVE'
          AND rr.reactionType = 'HELPFUL'
    """)
    long countHelpfulVotesReceivedByUserId(@Param("userId") UUID userId);
}
