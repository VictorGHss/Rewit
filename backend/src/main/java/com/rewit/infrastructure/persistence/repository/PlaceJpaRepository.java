package com.rewit.infrastructure.persistence.repository;

import com.rewit.infrastructure.persistence.entity.PlaceJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repositório Spring Data JPA para a entidade de locais físicos (places).
 */
@Repository
public interface PlaceJpaRepository extends JpaRepository<PlaceJpaEntity, UUID> {

    Optional<PlaceJpaEntity> findBySlug(String slug);

    boolean existsBySlug(String slug);

    @Query(value = """
        SELECT p.* FROM places p
        WHERE ST_DWithin(p.coordinates, ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326)::geography, :radiusMeters)
        ORDER BY ST_Distance(p.coordinates, ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326)::geography) ASC
    """, nativeQuery = true)
    List<PlaceJpaEntity> findNearby(
            @Param("latitude") double latitude,
            @Param("longitude") double longitude,
            @Param("radiusMeters") double radiusMeters
    );
}
