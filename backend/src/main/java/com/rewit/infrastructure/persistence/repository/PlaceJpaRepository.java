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

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PlaceJpaEntity p WHERE p.id = :id")
    Optional<PlaceJpaEntity> findByIdForUpdate(@Param("id") UUID id);

    Optional<PlaceJpaEntity> findBySlug(String slug);

    boolean existsBySlug(String slug);

    @Query(value = """
        SELECT EXISTS (
            SELECT 1 FROM places p
            WHERE p.status = 'ACTIVE'
              AND regexp_replace(btrim(rewit_search_normalize(p.name)), '\\s+', ' ', 'g')
                = regexp_replace(btrim(rewit_search_normalize(:name)), '\\s+', ' ', 'g')
              AND regexp_replace(btrim(rewit_search_normalize(p.address_text)), '\\s+', ' ', 'g')
                = regexp_replace(btrim(rewit_search_normalize(:addressText)), '\\s+', ' ', 'g')
        )
    """, nativeQuery = true)
    boolean existsActiveByNormalizedNameAndAddress(
            @Param("name") String name,
            @Param("addressText") String addressText
    );

    @Query(value = """
        SELECT p.* FROM places p
        WHERE p.coordinates IS NOT NULL
          AND ST_DWithin(p.coordinates, ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326)::geography, :radiusMeters)
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
          AND p.coordinates IS NOT NULL
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

    /**
     * Grava o perfil comercial (descrição e contatos) e nada mais: nome, endereço, coordenadas, status, titular e
     * verificação ficam fora do SET, então os triggers de troca de titular (V28/V29) não são acionados. Os CASTs
     * fixam o tipo dos parâmetros nulos (limpeza de campo), que o driver não teria como inferir.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE places SET description = CAST(:description AS TEXT), phone = CAST(:phone AS VARCHAR),
                website_url = CAST(:websiteUrl AS VARCHAR), instagram_url = CAST(:instagramUrl AS VARCHAR),
                facebook_url = CAST(:facebookUrl AS VARCHAR), updated_at = NOW()
            WHERE id = :placeId
            """, nativeQuery = true)
    int updateBusinessProfile(@Param("placeId") UUID placeId,
                              @Param("description") String description,
                              @Param("phone") String phone,
                              @Param("websiteUrl") String websiteUrl,
                              @Param("instagramUrl") String instagramUrl,
                              @Param("facebookUrl") String facebookUrl);
}
