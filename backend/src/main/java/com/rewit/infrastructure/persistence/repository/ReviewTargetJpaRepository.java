package com.rewit.infrastructure.persistence.repository;

import com.rewit.infrastructure.persistence.entity.ReviewTargetJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Repositório Spring Data JPA para persistência de alvos de avaliação (ReviewTargetJpaEntity).
 */
@Repository
public interface ReviewTargetJpaRepository extends JpaRepository<ReviewTargetJpaEntity, UUID> {

    List<ReviewTargetJpaEntity> findByReviewId(UUID reviewId);

    boolean existsByReviewIdAndTargetId(UUID reviewId, UUID targetId);
}
