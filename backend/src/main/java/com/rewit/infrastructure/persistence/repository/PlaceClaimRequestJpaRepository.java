package com.rewit.infrastructure.persistence.repository;

import com.rewit.infrastructure.persistence.entity.PlaceClaimRequestJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

/**
 * Repositório Spring Data JPA das solicitações de reivindicação de locais (place_claim_requests).
 */
public interface PlaceClaimRequestJpaRepository extends JpaRepository<PlaceClaimRequestJpaEntity, UUID> {

    /** Atendida pelo índice parcial único uq_place_claim_pending_place. */
    @Query(value = "SELECT EXISTS (SELECT 1 FROM place_claim_requests WHERE place_id = :placeId AND status = 'PENDING')",
            nativeQuery = true)
    boolean existsPendingByPlaceId(@Param("placeId") UUID placeId);
}
