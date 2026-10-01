package com.rewit.infrastructure.persistence.adapter;

import com.rewit.application.port.OutboxRepository;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.OutboxStatus;
import com.rewit.domain.model.OutboxMessage;
import com.rewit.infrastructure.persistence.entity.OutboxMessageJpaEntity;
import com.rewit.infrastructure.persistence.repository.OutboxMessageJpaRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Adaptador de persistência para o Transactional Outbox (Step 27.1).
 *
 * <p>{@code save} usa PROPAGATION_REQUIRED e por isso participa da transação
 * do produtor: a mensagem só se torna visível se a transação de negócio
 * confirmar. {@code claimBatch} roda em transação própria e curta: o SELECT
 * FOR UPDATE SKIP LOCKED mantém os locks de linha até o commit da mesma
 * transação que executa o UPDATE de claim.
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
}
