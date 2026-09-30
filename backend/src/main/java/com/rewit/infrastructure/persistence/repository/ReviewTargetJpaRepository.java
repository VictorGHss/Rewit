package com.rewit.infrastructure.persistence.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.rewit.infrastructure.persistence.entity.ReviewTargetJpaEntity;

/**
 * Repositório Spring Data JPA para persistência de alvos de avaliação (ReviewTargetJpaEntity).
 */
public interface ReviewTargetJpaRepository extends JpaRepository<ReviewTargetJpaEntity, UUID> {

    List<ReviewTargetJpaEntity> findByReviewId(UUID reviewId);

    List<ReviewTargetJpaEntity> findByReviewIdIn(java.util.Collection<UUID> reviewIds);

    boolean existsByReviewIdAndTargetId(UUID reviewId, UUID targetId);
}
