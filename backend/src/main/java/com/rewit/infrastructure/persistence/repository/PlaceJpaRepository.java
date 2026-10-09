package com.rewit.infrastructure.persistence.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.rewit.infrastructure.persistence.entity.PlaceJpaEntity;

/**
 * Repositório Spring Data JPA para a entidade de locais físicos (places).
 */
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

    @Query(value = """
        SELECT
            p.validation_radius_meters AS validation_radius_meters,
            ST_Distance(p.coordinates, ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326)::geography) AS distance_meters,
            ST_DWithin(p.coordinates, ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326)::geography, p.validation_radius_meters) AS is_within_radius
        FROM places p
        WHERE p.id = :placeId
    """, nativeQuery = true)
    Optional<jakarta.persistence.Tuple> validateProximity(
            @Param("placeId") UUID placeId,
            @Param("latitude") double latitude,
            @Param("longitude") double longitude
    );

    /** Vincula o local à conta só se ainda livre: a condição no próprio UPDATE impede vínculo duplo. */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE places SET claimed_by_business_id = :businessAccountId, updated_at = NOW()
            WHERE id = :placeId AND claimed_by_business_id IS NULL
            """, nativeQuery = true)
    int assignClaimedBusiness(@Param("placeId") UUID placeId, @Param("businessAccountId") UUID businessAccountId);
}
