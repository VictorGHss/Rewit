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

    @Query(value = """
        SELECT
            p.id AS id,
            p.name AS name,
            p.slug AS slug,
            p.category AS category,
            p.description AS description,
            p.address_text AS address_text,
            p.street_number AS street_number,
            p.neighborhood AS neighborhood,
            p.city AS city,
            p.state AS state,
            p.country AS country,
            ST_Y(p.coordinates::geometry) AS latitude,
            ST_X(p.coordinates::geometry) AS longitude,
            p.validation_radius_meters AS validation_radius_meters,
            p.origin AS origin,
            p.is_verified AS is_verified,
            p.claimed_by_business_id AS claimed_by_business_id,
            p.status AS status,
            ST_Distance(p.coordinates, ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326)::geography) AS distance_meters
        FROM places p
        WHERE p.status = 'ACTIVE'
          AND p.coordinates IS NOT NULL
          AND ST_DWithin(p.coordinates, ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326)::geography, :radiusMeters)
        ORDER BY ST_Distance(p.coordinates, ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326)::geography) ASC, p.id ASC
        LIMIT :limit
    """, nativeQuery = true)
    List<jakarta.persistence.Tuple> findNearbyWithDistance(
            @Param("latitude") double latitude,
            @Param("longitude") double longitude,
            @Param("radiusMeters") double radiusMeters,
            @Param("limit") int limit
    );
}
