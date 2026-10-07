package com.rewit.infrastructure.persistence.repository;

import com.rewit.domain.enums.DiscussionStatus;
import com.rewit.infrastructure.persistence.entity.DiscussionJpaEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Interface Spring Data JPA para a entidade DiscussionJpaEntity (Step 20.0 / Step 32.0).
 */
public interface DiscussionJpaRepository extends JpaRepository<DiscussionJpaEntity, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM DiscussionJpaEntity d WHERE d.id = :id")
    Optional<DiscussionJpaEntity> findByIdForUpdate(@Param("id") UUID id);

    Page<DiscussionJpaEntity> findByReviewIdAndStatus(UUID reviewId, DiscussionStatus status, Pageable pageable);

    // ---------------------------------------------------------------------------------------------------------
    // Thread por leitor (C3 D2). Resposta visível: ACTIVE, ou UNDER_REVIEW do próprio leitor. Raiz listada:
    // ACTIVE; UNDER_REVIEW do próprio leitor; ou REMOVED com pelo menos uma resposta visível (tombstone).
    // ---------------------------------------------------------------------------------------------------------

    @Query(value = """
        SELECT d FROM DiscussionJpaEntity d
        WHERE d.reviewId = :reviewId
          AND d.parentId IS NULL
          AND (d.status = :active
               OR (d.status = :underReview AND d.userId = :viewerId)
               OR (d.status = :removed AND EXISTS (
                      SELECT r.id FROM DiscussionJpaEntity r
                      WHERE r.parentId = d.id
                        AND (r.status = :active OR (r.status = :underReview AND r.userId = :viewerId)))))
        """,
        countQuery = """
        SELECT COUNT(d) FROM DiscussionJpaEntity d
        WHERE d.reviewId = :reviewId
          AND d.parentId IS NULL
          AND (d.status = :active
               OR (d.status = :underReview AND d.userId = :viewerId)
               OR (d.status = :removed AND EXISTS (
                      SELECT r.id FROM DiscussionJpaEntity r
                      WHERE r.parentId = d.id
                        AND (r.status = :active OR (r.status = :underReview AND r.userId = :viewerId)))))
        """)
    Page<DiscussionJpaEntity> findThreadRootsVisibleTo(@Param("reviewId") UUID reviewId,
                                                        @Param("viewerId") UUID viewerId,
                                                        @Param("active") DiscussionStatus active,
                                                        @Param("underReview") DiscussionStatus underReview,
                                                        @Param("removed") DiscussionStatus removed,
                                                        Pageable pageable);

    /** Primeiras {@code limit} respostas visíveis de cada raiz, em ordem cronológica, numa única consulta. */
    @Query(value = """
        SELECT x.id, x.review_id, x.user_id, x.parent_id, x.content, x.is_from_owner, x.status,
               x.created_at, x.updated_at
        FROM (
            SELECT d.*, ROW_NUMBER() OVER (PARTITION BY d.parent_id ORDER BY d.created_at, d.id) AS position
            FROM review_discussions d
            WHERE d.parent_id IN (:rootIds)
              AND (d.status = 'ACTIVE' OR (d.status = 'UNDER_REVIEW' AND d.user_id = :viewerId))
        ) x
        WHERE x.position <= :limit
        ORDER BY x.parent_id, x.created_at, x.id
        """, nativeQuery = true)
    List<DiscussionJpaEntity> findFirstRepliesVisibleTo(@Param("rootIds") Collection<UUID> rootIds,
                                                        @Param("viewerId") UUID viewerId,
                                                        @Param("limit") int limit);

    /** Total de respostas visíveis por raiz: linhas {@code [parentId, count]}. */
    @Query("""
        SELECT r.parentId, COUNT(r) FROM DiscussionJpaEntity r
        WHERE r.parentId IN :rootIds
          AND (r.status = :active OR (r.status = :underReview AND r.userId = :viewerId))
        GROUP BY r.parentId
        """)
    List<Object[]> countRepliesVisibleTo(@Param("rootIds") Collection<UUID> rootIds,
                                         @Param("viewerId") UUID viewerId,
                                         @Param("active") DiscussionStatus active,
                                         @Param("underReview") DiscussionStatus underReview);

    @Query(value = """
        SELECT r FROM DiscussionJpaEntity r
        WHERE r.parentId = :rootId
          AND (r.status = :active OR (r.status = :underReview AND r.userId = :viewerId))
        """,
        countQuery = """
        SELECT COUNT(r) FROM DiscussionJpaEntity r
        WHERE r.parentId = :rootId
          AND (r.status = :active OR (r.status = :underReview AND r.userId = :viewerId))
        """)
    Page<DiscussionJpaEntity> findRepliesVisibleTo(@Param("rootId") UUID rootId,
                                                   @Param("viewerId") UUID viewerId,
                                                   @Param("active") DiscussionStatus active,
                                                   @Param("underReview") DiscussionStatus underReview,
                                                   Pageable pageable);
}
