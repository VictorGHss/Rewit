package com.rewit.infrastructure.persistence.repository;

import com.rewit.infrastructure.persistence.entity.StorageObjectQuarantineJpaEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
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

    List<StorageObjectQuarantineJpaEntity> findByStatusOrderByFirstObservedAtAscIdAsc(String status, Pageable pageable);

    long countByStatus(String status);

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

    @Query(value = """
        SELECT id
        FROM storage_object_quarantine
        WHERE object_key = :objectKey
          AND first_observed_at = :firstObservedAt
          AND status = 'CONFIRMED_ORPHAN'
        FOR UPDATE
        """, nativeQuery = true)
    Optional<UUID> lockConfirmedEntry(
            @Param("objectKey") String objectKey,
            @Param("firstObservedAt") Instant firstObservedAt
    );

    /**
     * Validação temporal autoritativa no PostgreSQL (SEC-F2-04-ARCH-01): executada dentro da
     * transação de expurgo, após a aquisição dos locks da entidade dona e da linha de quarentena,
     * imediatamente antes da exclusão física no storage. Avalia elegibilidade exclusivamente com
     * {@code clock_timestamp()} do banco de dados, sem depender do relógio da JVM e sem exigir
     * nova função {@code SECURITY DEFINER} (usa privilégio normal de {@code SELECT} em ambos os
     * namespaces, sob {@code rewit_app} ou {@code rewit_media}).
     */
    @Query(value = """
        SELECT (
            q.status = 'CONFIRMED_ORPHAN'
            AND q.first_observed_at <= t.v_now
            AND q.first_observed_at <= t.v_now - INTERVAL '24 hours'
            AND q.confirmed_at IS NOT NULL
            AND q.confirmed_at <= t.v_now
            AND q.confirmed_at >= q.first_observed_at + INTERVAL '24 hours'
        )
        FROM public.storage_object_quarantine q
        CROSS JOIN (SELECT clock_timestamp() AS v_now) t
        WHERE q.id = :id
        """, nativeQuery = true)
    Boolean isLockedConfirmedEntryEligibleForPurge(@Param("id") UUID id);

    @Modifying(clearAutomatically = true)
    @Query(value = """
        UPDATE storage_object_quarantine
        SET status = 'CONFIRMED_ORPHAN',
            confirmed_at = COALESCE(confirmed_at, :now)
        WHERE id = :id
        """, nativeQuery = true)
    int markConfirmedOrphan(@Param("id") UUID id, @Param("now") Instant now);

    /**
     * Confirmação autorizada via função SECURITY DEFINER (SEC-F2-04): valida session_user, adquire FOR UPDATE,
     * exige estado OBSERVED e o transcurso do grace period de 24h a partir de first_observed_at pelo relógio
     * do PostgreSQL. Transiciona atomicamente para CONFIRMED_ORPHAN com confirmed_at = clock_timestamp().
     */
    @Query(value = "SELECT public.rewit_confirm_storage_quarantine_entry(:id)", nativeQuery = true)
    Boolean confirmEntryAsRuntime(@Param("id") UUID id);

    @Modifying(clearAutomatically = true)
    @Query(value = "DELETE FROM storage_object_quarantine WHERE id = :id", nativeQuery = true)
    int releaseEntry(@Param("id") UUID id);

    /**
     * Liberação restrita usada sob {@code rewit_media} (expurgo de {@code places/}): {@code rewit_media} não possui
     * DELETE direto na tabela. A função SECURITY DEFINER remove somente a entrada {@code CONFIRMED_ORPHAN} do
     * namespace {@code places/}; retorna {@code false} se a entrada não existir mais e aborta para outros
     * namespaces ou estados.
     */
    @Query(value = "SELECT public.rewit_release_place_quarantine_entry(:id)", nativeQuery = true)
    Boolean releasePlaceEntryAsMediaRole(@Param("id") UUID id);
}
