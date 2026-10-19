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
}

