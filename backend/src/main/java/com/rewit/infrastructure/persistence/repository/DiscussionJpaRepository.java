package com.rewit.infrastructure.persistence.repository;

import com.rewit.infrastructure.persistence.entity.DiscussionJpaEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * Interface Spring Data JPA para a entidade DiscussionJpaEntity (Step 20.0).
 */
public interface DiscussionJpaRepository extends JpaRepository<DiscussionJpaEntity, UUID> {

    Page<DiscussionJpaEntity> findByReviewIdAndStatus(UUID reviewId, String status, Pageable pageable);
}
