package com.rewit.application.port;

import com.rewit.domain.enums.OutboxStatus;
import com.rewit.domain.model.OutboxMessage;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Porta de persistência para mensagens do Transactional Outbox (Step 27.1).
 *
 * <p>O {@code save} participa da transação do produtor (PROPAGATION_REQUIRED):
 * a mensagem só se torna visível se a transação de negócio confirmar.
 */
public interface OutboxRepository {

    OutboxMessage save(OutboxMessage message);

    /**
     * Reivindica atomicamente até {@code batchSize} mensagens elegíveis
     * (status PENDING e next_attempt_at <= agora) para o worker informado,
     * usando FOR UPDATE SKIP LOCKED para não travar workers concorrentes.
     * Executa em uma transação curta e própria.
     */
    List<OutboxMessage> claimBatch(int batchSize, String workerId);

    Optional<OutboxMessage> findById(UUID id);

    long countByStatus(OutboxStatus status);
}
