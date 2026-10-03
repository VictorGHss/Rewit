package com.rewit.infrastructure.persistence.repository;

import com.rewit.infrastructure.persistence.entity.StorageObjectQuarantineJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Interface Spring Data JPA para a quarentena de storage (Step 28.3).
 *
 * <p>Os upserts usam ON CONFLICT sobre uq_storage_object_quarantine_object_key: execuções repetidas
 * ou concorrentes da mesma chave convergem para uma única linha, preservando first_observed_at.
 */
public interface StorageObjectQuarantineJpaRepository extends JpaRepository<StorageObjectQuarantineJpaEntity, UUID> {

    Optional<StorageObjectQuarantineJpaEntity> findByObjectKey(String objectKey);

    @Modifying(clearAutomatically = true)
    @Query(value = """
        INSERT INTO storage_object_quarantine (object_key, status, first_observed_at, last_observed_at, last_modified_at)
        VALUES (:objectKey, 'OBSERVED', :observedAt, :observedAt, :lastModifiedAt)
        ON CONFLICT (object_key) DO UPDATE
        SET last_observed_at = GREATEST(storage_object_quarantine.last_observed_at, EXCLUDED.last_observed_at),
            last_modified_at = EXCLUDED.last_modified_at
        """, nativeQuery = true)
    int upsertObservation(
            @Param("objectKey") String objectKey,
            @Param("observedAt") Instant observedAt,
            @Param("lastModifiedAt") Instant lastModifiedAt
    );

    // Storage sem lastModified: mantém o valor já conhecido
    @Modifying(clearAutomatically = true)
    @Query(value = """
        INSERT INTO storage_object_quarantine (object_key, status, first_observed_at, last_observed_at)
        VALUES (:objectKey, 'OBSERVED', :observedAt, :observedAt)
        ON CONFLICT (object_key) DO UPDATE
        SET last_observed_at = GREATEST(storage_object_quarantine.last_observed_at, EXCLUDED.last_observed_at)
        """, nativeQuery = true)
    int upsertObservationWithoutLastModified(
            @Param("objectKey") String objectKey,
            @Param("observedAt") Instant observedAt
    );

    @Query(value = """
        SELECT id
        FROM storage_object_quarantine
        WHERE object_key = :objectKey
          AND first_observed_at = :firstObservedAt
        FOR UPDATE
        """, nativeQuery = true)
    Optional<UUID> lockEntry(
            @Param("objectKey") String objectKey,
            @Param("firstObservedAt") Instant firstObservedAt
    );

    @Modifying(clearAutomatically = true)
    @Query(value = """
        UPDATE storage_object_quarantine
        SET status = 'CONFIRMED_ORPHAN',
            confirmed_at = COALESCE(confirmed_at, :now)
        WHERE id = :id
        """, nativeQuery = true)
    int markConfirmedOrphan(@Param("id") UUID id, @Param("now") Instant now);

    @Modifying(clearAutomatically = true)
    @Query(value = "DELETE FROM storage_object_quarantine WHERE id = :id", nativeQuery = true)
    int releaseEntry(@Param("id") UUID id);
}
