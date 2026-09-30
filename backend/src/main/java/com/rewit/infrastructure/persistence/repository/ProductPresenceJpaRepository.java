package com.rewit.infrastructure.persistence.repository;

import com.rewit.infrastructure.persistence.entity.ProductPresenceJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repositório Spring Data JPA para a relação de presença de produtos em locais (product_presences).
 */
public interface ProductPresenceJpaRepository extends JpaRepository<ProductPresenceJpaEntity, UUID> {

    Optional<ProductPresenceJpaEntity> findByProductIdAndPlaceId(UUID productId, UUID placeId);

    List<ProductPresenceJpaEntity> findByPlaceId(UUID placeId);

    List<ProductPresenceJpaEntity> findByProductId(UUID productId);

    boolean existsByProductIdAndPlaceId(UUID productId, UUID placeId);
}
