package com.rewit.infrastructure.persistence.repository;

import com.rewit.infrastructure.persistence.entity.CheckInJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
