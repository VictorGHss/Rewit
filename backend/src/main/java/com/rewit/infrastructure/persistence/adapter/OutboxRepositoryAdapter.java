package com.rewit.infrastructure.persistence.adapter;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.rewit.application.port.OutboxRepository;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.OutboxStatus;
import com.rewit.domain.model.OutboxMessage;
import com.rewit.infrastructure.persistence.entity.OutboxMessageJpaEntity;
import com.rewit.infrastructure.persistence.repository.OutboxMessageJpaRepository;

/**
 * Adaptador de persistência para o Transactional Outbox (Step 27.1).
 *
 * <p>{@code save} usa PROPAGATION_REQUIRED e por isso participa da transação
 * do produtor: a mensagem só se torna visível se a transação de negócio
 * confirmar. {@code claimBatch} roda em transação própria e curta: o SELECT
 * FOR UPDATE SKIP LOCKED mantém os locks de linha até o commit da mesma
 * transação que executa o UPDATE de claim. As operações do dispatcher
 * (reclaim de leases, finalizações owner-checked) também rodam cada uma em
 * transação própria curta — nunca ao redor da execução do handler.
 */
@Component
public class OutboxRepositoryAdapter implements OutboxRepository {

    private final OutboxMessageJpaRepository jpaRepository;

    public OutboxRepositoryAdapter(OutboxMessageJpaRepository jpaRepository) {
        this.jpaRepository = Objects.requireNonNull(jpaRepository, "OutboxMessageJpaRepository must not be null");
    }

    @Override
    @Transactional
    public OutboxMessage save(OutboxMessage message) {
        Objects.requireNonNull(message, "message must not be null");
        OutboxMessageJpaEntity saved = jpaRepository.save(OutboxMessageJpaEntity.fromDomain(message));
        return saved.toDomain();
    }

    @Override
    @Transactional
    public List<OutboxMessage> claimBatch(int batchSize, String workerId) {
        if (batchSize <= 0) {
            throw new BusinessException("O tamanho do lote do claim deve ser positivo", "INVALID_BATCH_SIZE");
        }
        if (workerId == null || workerId.isBlank()) {
            throw new BusinessException("O identificador do worker é obrigatório para o claim", "MISSING_WORKER_ID");
        }

        Instant now = Instant.now();
        List<UUID> ids = jpaRepository.lockClaimableIds(now, batchSize);
        if (ids.isEmpty()) {
            return List.of();
        }
        jpaRepository.markClaimed(ids, now, workerId.trim());
        return jpaRepository.findAllById(ids).stream()
                .map(entity -> entity.toDomain())
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<OutboxMessage> findById(UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        return jpaRepository.findById(id)
                .map(entity -> entity.toDomain());
    }

    @Override
    @Transactional(readOnly = true)
    public long countByStatus(OutboxStatus status) {
        if (status == null) {
            return 0;
        }
        return jpaRepository.countByStatus(status.name());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Instant> oldestPendingCreatedAt() {
        return Optional.ofNullable(jpaRepository.oldestPendingCreatedAt())
                .map(timestamp -> timestamp.toInstant());
    }

    @Override
    @Transactional
    public int purgeCompletedBefore(Instant cutoff, int batchSize) {
        if (cutoff == null) {
            throw new BusinessException("O corte de retenção é obrigatório para o purge", "MISSING_PURGE_CUTOFF");
        }
        if (batchSize <= 0) {
            throw new BusinessException("O tamanho do lote do purge deve ser positivo", "INVALID_PURGE_BATCH_SIZE");
        }
        return jpaRepository.purgeCompletedBefore(cutoff, batchSize);
    }

    @Override
    @Transactional
    public int reclaimExpiredLeases(Instant now, Duration leaseDuration) {
        if (now == null) {
            throw new BusinessException("O instante de referência é obrigatório para o reclaim", "MISSING_NOW");
        }
        if (leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new BusinessException("A duração da lease deve ser positiva", "INVALID_LEASE_DURATION");
        }
        Instant cutoff = now.minus(leaseDuration);
        return jpaRepository.reclaimExpiredLeases(now, cutoff);
    }

    @Override
    @Transactional
    public boolean markCompleted(UUID messageId, String workerId, Instant now) {
        validateFinalizationArguments(messageId, workerId, now);
        int updatedRows = jpaRepository.markCompletedById(messageId, workerId.trim(), now);
        return updatedRows > 0;
    }

    @Override
    @Transactional
    public boolean markFailed(UUID messageId, String workerId, String lastError, Instant now) {
        validateFinalizationArguments(messageId, workerId, now);
        int updatedRows = jpaRepository.markFailedById(messageId, workerId.trim(), lastError, now);
        return updatedRows > 0;
    }

    @Override
    @Transactional
    public boolean scheduleRetry(UUID messageId, String workerId, String lastError, Instant nextAttemptAt, Instant now) {
        validateFinalizationArguments(messageId, workerId, now);
        if (nextAttemptAt == null) {
            throw new BusinessException("A data da próxima tentativa é obrigatória para o retry", "MISSING_NEXT_ATTEMPT_AT");
        }
        int updatedRows = jpaRepository.scheduleRetryById(messageId, workerId.trim(), lastError, nextAttemptAt, now);
        return updatedRows > 0;
    }

    private void validateFinalizationArguments(UUID messageId, String workerId, Instant now) {
        if (messageId == null) {
            throw new BusinessException("O identificador da mensagem é obrigatório", "MISSING_MESSAGE_ID");
        }
        if (workerId == null || workerId.isBlank()) {
            throw new BusinessException("O identificador do worker é obrigatório", "MISSING_WORKER_ID");
        }
        if (now == null) {
            throw new BusinessException("O instante de referência é obrigatório", "MISSING_NOW");
        }
    }
}
