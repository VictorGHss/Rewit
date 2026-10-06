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
}
