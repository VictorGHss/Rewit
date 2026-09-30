package com.rewit.infrastructure.persistence.repository;

import com.rewit.infrastructure.persistence.entity.ReviewMediaJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Interface Spring Data JPA para a entidade ReviewMediaJpaEntity (Step 21.0).
 */
@Repository
public interface ReviewMediaJpaRepository extends JpaRepository<ReviewMediaJpaEntity, UUID> {

    List<ReviewMediaJpaEntity> findByReviewIdAndStatusOrderByCreatedAtAscIdAsc(UUID reviewId, String status);

    long countByReviewIdAndStatus(UUID reviewId, String status);
}
