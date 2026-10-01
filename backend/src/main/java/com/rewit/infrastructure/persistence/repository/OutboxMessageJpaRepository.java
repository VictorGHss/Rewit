package com.rewit.infrastructure.persistence.repository;

import com.rewit.infrastructure.persistence.entity.OutboxMessageJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Repositório Spring Data JPA para o Transactional Outbox (Step 27.1).
 *
 * <p>O claim é executado em duas instruções nativas dentro da MESMA transação:
 * o SELECT ... FOR UPDATE SKIP LOCKED trava as linhas elegíveis e o UPDATE
 * subsequente as move para PROCESSING antes do commit — sem janela entre
 * selecionar e atualizar.
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

    long countByStatus(String status);
}
