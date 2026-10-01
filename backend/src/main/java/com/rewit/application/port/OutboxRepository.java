package com.rewit.application.port;

import com.rewit.domain.enums.OutboxStatus;
import com.rewit.domain.model.OutboxMessage;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Porta de persistência para mensagens do Transactional Outbox (Steps 27.1/27.2).
 *
 * <p>O {@code save} participa da transação do produtor (PROPAGATION_REQUIRED):
 * a mensagem só se torna visível se a transação de negócio confirmar. As demais
 * operações do dispatcher ({@code claimBatch}, {@code reclaimExpiredLeases} e as
 * finalizações) rodam em transações curtas e próprias — nunca envolvem a execução
 * de um handler.
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

    /**
     * Recupera atomicamente mensagens presas em PROCESSING com lease expirado
     * (locked_at anterior a now - leaseDuration): volta a PENDING, limpa o
     * lock, reagenda next_attempt_at para agora e preserva attempts. Operação
     * atômica em SQL — segura com múltiplos workers.
     *
     * @return quantidade de mensagens recuperadas
     */
    int reclaimExpiredLeases(Instant now, Duration leaseDuration);

    /**
     * Finaliza com sucesso a mensagem ainda possuída pelo worker informado
     * (status PROCESSING e locked_by igual ao workerId). Se a lease foi
     * perdida (outro worker recuperou e reivindicou a mensagem), retorna
     * false sem alterar nenhum estado — a mensagem nunca é ressuscitada.
     */
    boolean markCompleted(UUID messageId, String workerId, Instant now);

    /**
     * Finaliza com falha definitiva (FAILED) a mensagem ainda possuída pelo
     * worker informado, registrando o lastError sanitizado e liberando o lock.
     * Retorna false quando o worker não é mais o dono, sem alterar estado.
     */
    boolean markFailed(UUID messageId, String workerId, String lastError, Instant now);

    /**
     * Reagenda para retry (PENDING com novo next_attempt_at) a mensagem ainda
     * possuída pelo worker informado, registrando o lastError sanitizado,
     * preservando attempts e liberando o lock. Retorna false quando o worker
     * não é mais o dono, sem alterar estado.
     */
    boolean scheduleRetry(UUID messageId, String workerId, String lastError, Instant nextAttemptAt, Instant now);

    Optional<OutboxMessage> findById(UUID id);

    long countByStatus(OutboxStatus status);

    /**
     * Instante de criação da mensagem PENDING mais antiga (Step 27.4, Parte E).
     * Fonte de verdade é o PostgreSQL: a consulta é executada a cada chamada.
     *
     * @return instante da PENDING mais antiga; vazio quando não há mensagens PENDING
     */
    Optional<Instant> oldestPendingCreatedAt();

    /**
     * Remove em lote mensagens COMPLETED cujo updated_at é anterior ao corte
     * informado (Step 27.4, Partes I/J/R). Só atinge estado terminal de sucesso:
     * PENDING, PROCESSING e FAILED nunca são removidos, independentemente da idade.
     *
     * @param cutoff    limite de idade por updated_at (messages com updated_at >= cutoff permanecem)
     * @param batchSize limite máximo de linhas removidas nesta chamada
     * @return quantidade efetivamente removida
     */
    int purgeCompletedBefore(Instant cutoff, int batchSize);
}
