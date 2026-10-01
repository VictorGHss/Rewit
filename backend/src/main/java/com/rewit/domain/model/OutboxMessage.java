package com.rewit.domain.model;

import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.OutboxStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Mensagem do Outbox transacional (Step 27.1).
 *
 * <p>Modelo de domínio puro: sem Spring, sem JPA, sem conhecimento de PostgreSQL.
 * A mensagem é enfileirada na mesma transação PostgreSQL do produtor e consumida
 * de forma at-least-once por um dispatcher futuro.
 *
 * <p>Transições válidas:
 * <pre>
 *   PENDING    -&gt; PROCESSING  (claim)
 *   PROCESSING -&gt; COMPLETED   (sucesso)
 *   PROCESSING -&gt; PENDING     (devolução explícita; recovery por lease é futuro)
 *   PROCESSING -&gt; FAILED      (falha definitiva)
 * </pre>
 * COMPLETED e FAILED são terminais neste foundation.
 */
public class OutboxMessage {

    private final UUID id;
    private final String messageType;
    private final String payload;
    private OutboxStatus status;
    private int attempts;
    private Instant nextAttemptAt;
    private Instant lockedAt;
    private String lockedBy;
    private String lastError;
    private final Instant createdAt;
    private Instant updatedAt;

    /**
     * Cria uma nova mensagem para enfileiramento: PENDING, attempts = 0,
     * next_attempt_at = created_at = now.
     */
    public OutboxMessage(String messageType, String payload) {
        this(null, messageType, payload, OutboxStatus.PENDING, 0,
                null, null, null, null, null, null);
    }

    /**
     * Construtor completo (reidratação a partir da persistência ou cenários de teste).
     *
     * @param id             identificador; gerado quando nulo
     * @param messageType    tipo/roteamento da mensagem (não vazio)
     * @param payload        conteúdo JSON (não nulo)
     * @param status         estado atual (não nulo)
     * @param attempts       tentativas já realizadas (>= 0)
     * @param nextAttemptAt  elegibilidade para claim (default: createdAt)
     * @param lockedAt       instante do claim (default: null)
     * @param lockedBy       identificador do worker que reivindicou (default: null)
     * @param lastError      último erro registrado (opcional)
     * @param createdAt      criação (default: now)
     * @param updatedAt      última atualização (default: createdAt)
     */
    public OutboxMessage(UUID id, String messageType, String payload, OutboxStatus status, int attempts,
                         Instant nextAttemptAt, Instant lockedAt, String lockedBy, String lastError,
                         Instant createdAt, Instant updatedAt) {
        if (messageType == null || messageType.isBlank()) {
            throw new BusinessException("O tipo da mensagem do outbox é obrigatório", "MISSING_MESSAGE_TYPE");
        }
        if (payload == null) {
            throw new BusinessException("O payload da mensagem do outbox é obrigatório", "MISSING_PAYLOAD");
        }
        if (status == null) {
            throw new BusinessException("O status da mensagem do outbox é obrigatório", "MISSING_OUTBOX_STATUS");
        }
        if (attempts < 0) {
            throw new BusinessException("A contagem de tentativas não pode ser negativa", "INVALID_ATTEMPTS");
        }
        if (updatedAt != null && createdAt != null && updatedAt.isBefore(createdAt)) {
            throw new BusinessException("A data de atualização não pode ser anterior à data de criação",
                    "INVALID_TIMESTAMP_ORDER");
        }
        if ((lockedAt == null) != (lockedBy == null || lockedBy.isBlank())) {
            throw new BusinessException("locked_at e locked_by devem ser preenchidos conjuntamente",
                    "INVALID_LOCK_INFORMATION");
        }
        if (status == OutboxStatus.PROCESSING && lockedAt == null) {
            throw new BusinessException("Mensagem em processamento exige lock de worker (locked_at e locked_by)",
                    "INVALID_PROCESSING_STATE");
        }

        this.id = id != null ? id : UUID.randomUUID();
        this.messageType = messageType.trim();
        this.payload = payload;
        this.status = status;
        this.attempts = attempts;
        this.createdAt = createdAt != null ? createdAt : Instant.now();
        this.nextAttemptAt = nextAttemptAt != null ? nextAttemptAt : this.createdAt;
        this.lockedAt = lockedAt;
        this.lockedBy = lockedBy != null && !lockedBy.isBlank() ? lockedBy.trim() : null;
        this.lastError = lastError;
        this.updatedAt = updatedAt != null ? updatedAt : this.createdAt;
    }

    /**
     * Reivindica a mensagem (PENDING -&gt; PROCESSING): registra o lock do worker,
     * incrementa attempts e atualiza o timestamp.
     *
     * <p>Nota: o dispatcher futuro usará o caminho atômico em SQL (claimBatch),
     * que incrementa attempts de forma concorrente-segura; este método modela a
     * mesma regra no domínio para uso direto do objeto.
     */
    public void claim(String workerId, Instant now) {
        requireCurrentStatus(OutboxStatus.PENDING, OutboxStatus.PROCESSING);
        if (workerId == null || workerId.isBlank()) {
            throw new BusinessException("O identificador do worker é obrigatório para o claim", "MISSING_WORKER_ID");
        }
        Instant effectiveNow = now != null ? now : Instant.now();
        this.status = OutboxStatus.PROCESSING;
        this.attempts++;
        this.lockedAt = effectiveNow;
        this.lockedBy = workerId.trim();
        this.updatedAt = effectiveNow;
    }

    /**
     * Conclui a mensagem com sucesso (PROCESSING -&gt; COMPLETED).
     */
    public void markCompleted(Instant now) {
        requireCurrentStatus(OutboxStatus.PROCESSING, OutboxStatus.COMPLETED);
        Instant effectiveNow = now != null ? now : Instant.now();
        this.status = OutboxStatus.COMPLETED;
        this.updatedAt = effectiveNow;
    }

    /**
     * Registra falha definitiva (PROCESSING -&gt; FAILED). Sem backoff neste
     * foundation: nextAttemptAt permanece inalterado; FAILED é terminal.
     */
    public void markFailed(String lastError, Instant now) {
        requireCurrentStatus(OutboxStatus.PROCESSING, OutboxStatus.FAILED);
        Instant effectiveNow = now != null ? now : Instant.now();
        this.status = OutboxStatus.FAILED;
        this.lastError = lastError;
        this.updatedAt = effectiveNow;
    }

    /**
     * Devolve a mensagem à fila (PROCESSING -&gt; PENDING), limpando o lock.
     * Não reagenda nextAttemptAt: lease recovery/backoff são futuro (Step 27.2).
     */
    public void requeue(Instant now) {
        requireCurrentStatus(OutboxStatus.PROCESSING, OutboxStatus.PENDING);
        Instant effectiveNow = now != null ? now : Instant.now();
        this.status = OutboxStatus.PENDING;
        this.lockedAt = null;
        this.lockedBy = null;
        this.updatedAt = effectiveNow;
    }

    private void requireCurrentStatus(OutboxStatus expected, OutboxStatus target) {
        if (this.status != expected) {
            throw new BusinessException(
                    "Transição inválida do outbox: " + this.status + " -> " + target
                            + " (estado esperado: " + expected + ")",
                    "INVALID_OUTBOX_TRANSITION");
        }
    }

    public UUID getId() {
        return id;
    }

    public String getMessageType() {
        return messageType;
    }

    public String getPayload() {
        return payload;
    }

    public OutboxStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public Instant getLockedAt() {
        return lockedAt;
    }

    public String getLockedBy() {
        return lockedBy;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
