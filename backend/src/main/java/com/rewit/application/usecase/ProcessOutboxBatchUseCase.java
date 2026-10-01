package com.rewit.application.usecase;

import com.rewit.application.outbox.OutboxErrorSanitizer;
import com.rewit.application.outbox.OutboxFailureClassifier;
import com.rewit.application.outbox.OutboxFailureType;
import com.rewit.application.outbox.OutboxRetryPolicy;
import com.rewit.application.port.OutboxHandler;
import com.rewit.application.port.OutboxRepository;
import com.rewit.domain.model.OutboxMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Ciclo completo do dispatcher do outbox para um tick (Step 27.2).
 *
 * <p>Fluxo por execução:
 * <ol>
 *   <li>Reclaim atômico de mensagens em PROCESSING com lease expirada
 *       (transação curta própria do repositório).</li>
 *   <li>Claim de um lote de mensagens PENDING elegíveis via SKIP LOCKED
 *       (transação curta que commita ANTES do processamento).</li>
 *   <li>Processamento de cada mensagem FORA de qualquer transação: nenhuma
 *       transação PostgreSQL permanece aberta durante {@link OutboxHandler#handle}.</li>
 *   <li>Finalização owner-checked em transação curta individual: COMPLETED,
 *       PENDING com retry agendado (backoff) ou FAILED. Se o worker perdeu a
 *       posse (lease herdada por outro), a finalização é descartada — a
 *       mensagem nunca é ressuscitada.</li>
 * </ol>
 *
 * <p>Classificação de falhas (regra explícita):
 * <ul>
 *   <li>{@link com.rewit.application.outbox.OutboxPermanentException} → PERMANENTE:
 *       FAILED imediato, sem retry.</li>
 *   <li>{@link com.rewit.common.exception.BusinessException} → PERMANENTE:
 *       rejeição de negócio determinística não se resolve com retry.</li>
 *   <li>Qualquer outra exceção (incluindo
 *       {@link com.rewit.application.outbox.OutboxTransientException} e falhas
 *       não classificadas) → TRANSIENTE: retry com backoff enquanto restarem
 *       tentativas; após esgotar maxAttempts, FAILED.</li>
 *   <li>Mensagem sem handler registrado → PERMANENTE: falha de roteamento
 *       determinística (só se resolve com deploy de código, não com retry).</li>
 * </ul>
 *
 * <p>Classe deliberadamente livre de Spring (sem @Service, sem @Transactional,
 * sem @Scheduled): é montada pela configuração de infraestrutura e pode ser
 * executada manualmente ou por qualquer agendador. O @Scheduled fino que a
 * invoca vive em {@code OutboxDispatcherPoller}; cada claim incrementa
 * {@code attempts} (semântica do Step 27.1) e a finalização NÃO incrementa
 * novamente — o valor reivindicado é o número da tentativa que acabou de falhar.
 */
public class ProcessOutboxBatchUseCase {

    private static final Logger log = LoggerFactory.getLogger(ProcessOutboxBatchUseCase.class);

    private final OutboxRepository outboxRepository;
    private final List<OutboxHandler> handlers;
    private final OutboxRetryPolicy retryPolicy;
    private final OutboxErrorSanitizer errorSanitizer;
    private final String workerId;
    private final int batchSize;
    private final Duration leaseDuration;

    public ProcessOutboxBatchUseCase(
            OutboxRepository outboxRepository,
            List<OutboxHandler> handlers,
            OutboxRetryPolicy retryPolicy,
            OutboxErrorSanitizer errorSanitizer,
            String workerId,
            int batchSize,
            Duration leaseDuration
    ) {
        this.outboxRepository = Objects.requireNonNull(outboxRepository, "outboxRepository must not be null");
        this.handlers = handlers == null ? List.of() : List.copyOf(handlers);
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy must not be null");
        this.errorSanitizer = Objects.requireNonNull(errorSanitizer, "errorSanitizer must not be null");
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("workerId must not be blank");
        }
        this.workerId = workerId.trim();
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        this.batchSize = batchSize;
        if (leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
        this.leaseDuration = leaseDuration;
    }

    /**
     * Executa um ciclo completo: reclaim + claim + processamento + finalizações.
     *
     * @return contadores do ciclo para observabilidade
     */
    public ProcessOutboxBatchResult processPendingBatch() {
        Instant now = Instant.now();

        int reclaimedCount = outboxRepository.reclaimExpiredLeases(now, leaseDuration);
        if (reclaimedCount > 0) {
            log.warn("Outbox dispatcher: {} mensagens em PROCESSING com lease expirada foram recuperadas para PENDING", reclaimedCount);
        }

        List<OutboxMessage> batch = outboxRepository.claimBatch(batchSize, workerId);
        if (batch.isEmpty()) {
            return new ProcessOutboxBatchResult(reclaimedCount, 0, 0, 0, 0, 0, 0);
        }
        log.debug("Outbox dispatcher: worker [{}] reivindicou {} mensagens", workerId, batch.size());

        BatchAccumulator accumulator = new BatchAccumulator(reclaimedCount, batch.size());
        for (OutboxMessage message : batch) {
            processOne(message, accumulator);
        }
        return accumulator.toResult();
    }

    private void processOne(OutboxMessage message, BatchAccumulator accumulator) {
        UUID messageId = message.getId();
        try {
            OutboxHandler handler = resolveHandler(message);
            if (handler == null) {
                String sanitizedError = errorSanitizer.sanitize(
                        "Nenhum handler registrado para o tipo de mensagem: " + message.getMessageType());
                log.warn("Outbox dispatcher: mensagem {} sem handler (tipo {}); marcada como FAILED",
                        messageId, message.getMessageType());
                fail(message, sanitizedError, accumulator);
                return;
            }

            try {
                handler.handle(message);
                complete(message, accumulator);
            } catch (RuntimeException failure) {
                handleProcessingFailure(message, failure, accumulator);
            }
        } catch (RuntimeException finalizeFailure) {
            accumulator.countFinalizeError();
            log.error("Outbox dispatcher: falha ao finalizar mensagem {} pelo worker [{}]",
                    messageId, workerId, finalizeFailure);
        }
    }

    private void handleProcessingFailure(OutboxMessage message, RuntimeException failure, BatchAccumulator accumulator) {
        OutboxFailureType failureType = OutboxFailureClassifier.classify(failure);
        String sanitizedError = errorSanitizer.sanitize(failure);

        if (failureType == OutboxFailureType.PERMANENT) {
            log.warn("Outbox dispatcher: falha PERMANENTE na mensagem {} (tipo {}): {}",
                    message.getId(), message.getMessageType(), sanitizedError, failure);
            fail(message, sanitizedError, accumulator);
            return;
        }

        if (retryPolicy.shouldRetry(message.getAttempts())) {
            Instant nextAttemptAt = retryPolicy.nextAttemptAt(message.getAttempts(), Instant.now());
            log.warn("Outbox dispatcher: falha TRANSIENTE na mensagem {} (tipo {}, tentativa {}): {} | retry em {}",
                    message.getId(), message.getMessageType(), message.getAttempts(), sanitizedError, nextAttemptAt, failure);
            retry(message, sanitizedError, nextAttemptAt, accumulator);
            return;
        }

        log.warn("Outbox dispatcher: tentativas esgotadas para a mensagem {} (tipo {}, {} tentativas): FAILED | {}",
                message.getId(), message.getMessageType(), message.getAttempts(), sanitizedError, failure);
        fail(message, sanitizedError, accumulator);
    }

    private OutboxHandler resolveHandler(OutboxMessage message) {
        for (OutboxHandler handler : handlers) {
            if (handler.supports(message.getMessageType())) {
                return handler;
            }
        }
        return null;
    }

    private void complete(OutboxMessage message, BatchAccumulator accumulator) {
        boolean applied = outboxRepository.markCompleted(message.getId(), workerId, Instant.now());
        if (applied) {
            accumulator.countCompleted();
            log.debug("Outbox dispatcher: mensagem {} finalizada com sucesso por [{}]", message.getId(), workerId);
        } else {
            noteOwnershipLost(message, accumulator);
        }
    }

    private void fail(OutboxMessage message, String sanitizedError, BatchAccumulator accumulator) {
        boolean applied = outboxRepository.markFailed(message.getId(), workerId, sanitizedError, Instant.now());
        if (applied) {
            accumulator.countFailed();
        } else {
            noteOwnershipLost(message, accumulator);
        }
    }

    private void retry(OutboxMessage message, String sanitizedError, Instant nextAttemptAt, BatchAccumulator accumulator) {
        boolean applied = outboxRepository.scheduleRetry(
                message.getId(), workerId, sanitizedError, nextAttemptAt, Instant.now());
        if (applied) {
            accumulator.countRetried();
        } else {
            noteOwnershipLost(message, accumulator);
        }
    }

    private void noteOwnershipLost(OutboxMessage message, BatchAccumulator accumulator) {
        accumulator.countLostOwnership();
        log.warn("Outbox dispatcher: a lease da mensagem {} passou para outro worker; finalização ignorada por [{}]",
                message.getId(), workerId);
    }

    /**
     * Contadores de um ciclo de processamento.
     *
     * @param reclaimedCount     mensagens recuperadas de lease expirada para PENDING
     * @param claimedCount       mensagens reivindicadas neste ciclo
     * @param completedCount     finalizadas com sucesso como COMPLETED
     * @param retriedCount       devolvidas a PENDING com nova next_attempt_at
     * @param failedCount        marcadas como FAILED (permanente ou tentativas esgotadas)
     * @param lostOwnershipCount finalizações descartadas porque a lease migrou de worker
     * @param finalizeErrorCount erros ao persistir a finalização (falha original NÃO é mascarada)
     */
    public record ProcessOutboxBatchResult(
            int reclaimedCount,
            int claimedCount,
            int completedCount,
            int retriedCount,
            int failedCount,
            int lostOwnershipCount,
            int finalizeErrorCount
    ) {
    }

    private static final class BatchAccumulator {
        private final int reclaimedCount;
        private final int claimedCount;
        private int completedCount;
        private int retriedCount;
        private int failedCount;
        private int lostOwnershipCount;
        private int finalizeErrorCount;

        private BatchAccumulator(int reclaimedCount, int claimedCount) {
            this.reclaimedCount = reclaimedCount;
            this.claimedCount = claimedCount;
        }

        private void countCompleted() {
            completedCount++;
        }

        private void countRetried() {
            retriedCount++;
        }

        private void countFailed() {
            failedCount++;
        }

        private void countLostOwnership() {
            lostOwnershipCount++;
        }

        private void countFinalizeError() {
            finalizeErrorCount++;
        }

        private ProcessOutboxBatchResult toResult() {
            return new ProcessOutboxBatchResult(
                    reclaimedCount, claimedCount, completedCount, retriedCount,
                    failedCount, lostOwnershipCount, finalizeErrorCount);
        }
    }
}
