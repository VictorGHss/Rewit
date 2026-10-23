package com.rewit.infrastructure.persistence.repository;

import com.rewit.infrastructure.persistence.entity.PlaceMediaJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Repositório Spring Data JPA para a tabela place_media.
 */
public interface PlaceMediaJpaRepository extends JpaRepository<PlaceMediaJpaEntity, UUID> {

    List<PlaceMediaJpaEntity> findByPlaceIdAndStatusOrderByCreatedAtAscIdAsc(UUID placeId, String status);

    long countByPlaceIdAndStatus(UUID placeId, String status);

    List<PlaceMediaJpaEntity> findByObjectKeyIn(List<String> objectKeys);

    java.util.Optional<PlaceMediaJpaEntity> findByPlaceIdAndIsPrimaryTrueAndStatus(UUID placeId, String status);

    java.util.Optional<PlaceMediaJpaEntity> findByUserIdAndPlaceIdAndIdempotencyKey(UUID userId, UUID placeId, String idempotencyKey);

    java.util.Optional<PlaceMediaJpaEntity> findFirstByPlaceIdAndStatusOrderByCreatedAtAscIdAsc(UUID placeId, String status);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("SELECT m FROM PlaceMediaJpaEntity m WHERE m.id = :id")
    java.util.Optional<PlaceMediaJpaEntity> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") UUID id);

    @org.springframework.data.jpa.repository.Query("SELECT m FROM PlaceMediaJpaEntity m WHERE m.status = 'REMOVED' AND m.removedAt <= :cutoff AND m.purgedAt IS NULL ORDER BY m.removedAt ASC, m.id ASC")
    List<PlaceMediaJpaEntity> findRemovalsOlderThan(@org.springframework.data.repository.query.Param("cutoff") java.time.Instant cutoff, org.springframework.data.domain.Pageable pageable);

    org.springframework.data.domain.Page<PlaceMediaJpaEntity> findByStatusOrderByCreatedAtAscIdAsc(String status, org.springframework.data.domain.Pageable pageable);
}

