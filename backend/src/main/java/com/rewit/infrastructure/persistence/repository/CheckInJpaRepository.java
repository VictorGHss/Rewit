package com.rewit.infrastructure.persistence.repository;

import com.rewit.infrastructure.persistence.entity.CheckInJpaEntity;
import jakarta.persistence.Tuple;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repositório Spring Data JPA para a entidade CheckInJpaEntity (Step 12.0).
 */
public interface CheckInJpaRepository extends JpaRepository<CheckInJpaEntity, UUID> {

    Optional<CheckInJpaEntity> findByReviewId(UUID reviewId);

    List<CheckInJpaEntity> findByUserId(UUID userId);

    List<CheckInJpaEntity> findByPlaceId(UUID placeId);

    boolean existsByReviewId(UUID reviewId);

    /**
     * Advisory lock transacional por chave textual, reduzida a 64 bits por {@code hashtextextended}. Uma colisão só
     * faz duas chaves esperarem uma pela outra; nunca libera uma verificação concorrente.
     */
    @Query(value = "SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(:lockKey, 0))", nativeQuery = true)
    Integer acquireTransactionLock(@Param("lockKey") String lockKey);

    boolean existsByUserIdAndPlaceIdAndStatusAndVerifiedAtAfter(UUID userId, UUID placeId, String status,
                                                                 Instant verifiedAfter);

    /** Check-ins verificados do usuário em avaliações ACTIVE e não anônimas; só dados do local, nunca coordenadas. */
    @Query(value = """
        SELECT c.id AS check_in_id,
               c.review_id AS review_id,
               c.place_id AS place_id,
               p.name AS place_name,
               p.category AS place_category,
               p.city AS place_city,
               p.state AS place_state,
               c.verified_at AS verified_at
        FROM check_ins c
        JOIN reviews r ON r.id = c.review_id
        JOIN places p ON p.id = c.place_id
        WHERE c.user_id = :userId
          AND c.status = 'VERIFIED'
          AND r.status = 'ACTIVE'
          AND r.is_anonymous = FALSE
        ORDER BY c.verified_at DESC, c.id DESC
        LIMIT :limit OFFSET :offset
    """, nativeQuery = true)
    List<Tuple> findMyVerifiedCheckIns(@Param("userId") UUID userId, @Param("limit") int limit,
                                       @Param("offset") long offset);

    @Query(value = """
        SELECT count(*)
        FROM check_ins c
        JOIN reviews r ON r.id = c.review_id
        WHERE c.user_id = :userId
          AND c.status = 'VERIFIED'
          AND r.status = 'ACTIVE'
          AND r.is_anonymous = FALSE
    """, nativeQuery = true)
    long countMyVerifiedCheckIns(@Param("userId") UUID userId);
}
