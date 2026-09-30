package com.rewit.infrastructure.persistence.repository;

import com.rewit.infrastructure.persistence.entity.PlaceExternalReferenceJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA Repository para a tabela place_external_references.
 */
public interface PlaceExternalReferenceJpaRepository extends JpaRepository<PlaceExternalReferenceJpaEntity, UUID> {

    Optional<PlaceExternalReferenceJpaEntity> findByProviderAndExternalId(String provider, String externalId);

    List<PlaceExternalReferenceJpaEntity> findByPlaceId(UUID placeId);

    boolean existsByProviderAndExternalId(String provider, String externalId);
}
