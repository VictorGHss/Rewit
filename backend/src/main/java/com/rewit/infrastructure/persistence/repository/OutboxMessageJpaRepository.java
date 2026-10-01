package com.rewit.infrastructure.persistence.repository;

import com.rewit.infrastructure.persistence.entity.OutboxMessageJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Repositório Spring Data JPA para o Transactional Outbox (Step 27.1 / 27.2).
 *
 * <p>O claim é executado em duas instruções nativas dentro da MESMA transação:
 * o SELECT ... FOR UPDATE SKIP LOCKED trava as linhas elegíveis e o UPDATE
 * subsequente as move para PROCESSING antes do commit — sem janela entre
 * selecionar e atualizar.
 *
 * <p>O reclaim de leases expiradas (Step 27.2) é um único UPDATE atômico: o
 * PostgreSQL serializa via locks de linha, sem janela entre recuperar e
 * liberar. As finalizações (COMPLETED/FAILED/retry) são owner-checked: só
 * aplicam quando {@code locked_by} ainda é o worker informado — uma mensagem
 * cuja lease foi herdada por outro worker nunca é ressuscitada.
 */
public interface OutboxMessageJpaRepository extends JpaRepository<OutboxMessageJpaEntity, UUID> {

    @Query(value = """
        SELECT id
        FROM outbox_messages
        WHERE status = 'PENDING'
          AND next_attempt_at <= :now
        ORDER BY next_attempt_at, id
        LIMIT :limit
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<UUID> lockClaimableIds(@Param("now") Instant now, @Param("limit") int limit);

    @Modifying(clearAutomatically = true)
    @Query(value = """
        UPDATE outbox_messages
        SET status = 'PROCESSING',
            attempts = attempts + 1,
            locked_at = :now,
            locked_by = :workerId,
            updated_at = :now
        WHERE id IN (:ids)
        """, nativeQuery = true)
    int markClaimed(
            @Param("ids") Collection<UUID> ids,
            @Param("now") Instant now,
            @Param("workerId") String workerId
    );

    @Modifying(clearAutomatically = true)
    @Query(value = """
        UPDATE outbox_messages
        SET status = 'PENDING',
            locked_at = NULL,
            locked_by = NULL,
            next_attempt_at = :now,
            updated_at = :now
        WHERE status = 'PROCESSING'
          AND locked_at < :cutoff
        """, nativeQuery = true)
    int reclaimExpiredLeases(@Param("now") Instant now, @Param("cutoff") Instant cutoff);

    @Modifying(clearAutomatically = true)
    @Query(value = """
        UPDATE outbox_messages
        SET status = 'COMPLETED',
            locked_at = NULL,
            locked_by = NULL,
            last_error = NULL,
            updated_at = :now
        WHERE id = :id
          AND status = 'PROCESSING'
          AND locked_by = :workerId
        """, nativeQuery = true)
    int markCompletedById(
            @Param("id") UUID id,
            @Param("workerId") String workerId,
            @Param("now") Instant now
    );

    @Modifying(clearAutomatically = true)
    @Query(value = """
        UPDATE outbox_messages
        SET status = 'FAILED',
            locked_at = NULL,
            locked_by = NULL,
            last_error = :lastError,
            updated_at = :now
        WHERE id = :id
          AND status = 'PROCESSING'
          AND locked_by = :workerId
        """, nativeQuery = true)
    int markFailedById(
            @Param("id") UUID id,
            @Param("workerId") String workerId,
            @Param("lastError") String lastError,
            @Param("now") Instant now
    );

    @Modifying(clearAutomatically = true)
    @Query(value = """
        UPDATE outbox_messages
        SET status = 'PENDING',
            locked_at = NULL,
            locked_by = NULL,
            last_error = :lastError,
            next_attempt_at = :nextAttemptAt,
            updated_at = :now
        WHERE id = :id
          AND status = 'PROCESSING'
          AND locked_by = :workerId
        """, nativeQuery = true)
    int scheduleRetryById(
            @Param("id") UUID id,
            @Param("workerId") String workerId,
            @Param("lastError") String lastError,
            @Param("nextAttemptAt") Instant nextAttemptAt,
            @Param("now") Instant now
    );

    long countByStatus(String status);

    /**
     * Step 27.4 (Parte E): MIN(created_at) das PENDING, apoiado no índice parcial
     * idx_outbox_pending (WHERE status = 'PENDING'). Sem índice novo: a tabela
     * permanece limitada pelo purge e as PENDING são poucas em regime permanente.
     */
    @Query(value = """
        SELECT MIN(created_at)
        FROM outbox_messages
        WHERE status = 'PENDING'
        """, nativeQuery = true)
    Timestamp oldestPendingCreatedAt();

    /**
     * Step 27.4 (Partes J/R): purge em lote de COMPLETED antigas por updated_at.
     * Determinístico e limitado: subconsulta SELECT ... ORDER BY updated_at LIMIT
     * remove primeiro as mais antigas; PENDING/PROCESSING/FAILED nunca são
     * atingidos. Atômico — seguro com execuções concorrentes.
     */
    @Modifying(clearAutomatically = true)
    @Query(value = """
        DELETE FROM outbox_messages
        WHERE id IN (
            SELECT id
            FROM outbox_messages
            WHERE status = 'COMPLETED'
              AND updated_at < :cutoff
            ORDER BY updated_at
            LIMIT :limit
        )
        """, nativeQuery = true)
    int purgeCompletedBefore(@Param("cutoff") Instant cutoff, @Param("limit") int limit);
}
