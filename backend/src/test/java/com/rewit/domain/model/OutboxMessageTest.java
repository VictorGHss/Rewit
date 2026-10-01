package com.rewit.domain.model;

import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.OutboxStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Testes de Unidade: Entidade de Domínio OutboxMessage (Step 27.1)")
class OutboxMessageTest {

    private static final String PAYLOAD = "{\"notificationId\":\"d0e1f2a3-0000-4000-8000-000000000001\"}";

    @Test
    @DisplayName("Criar OutboxMessage válida para enfileiramento: PENDING, attempts 0, id e timestamps gerados")
    void shouldCreateValidOutboxMessageForEnqueue() {
        Instant before = Instant.now();

        OutboxMessage message = new OutboxMessage("PUSH_DELIVERY", PAYLOAD);

        assertNotNull(message.getId());
        assertEquals("PUSH_DELIVERY", message.getMessageType());
        assertEquals(PAYLOAD, message.getPayload());
        assertEquals(OutboxStatus.PENDING, message.getStatus());
        assertEquals(0, message.getAttempts());
        assertNull(message.getLockedAt());
        assertNull(message.getLockedBy());
        assertNull(message.getLastError());
        assertNotNull(message.getCreatedAt());
        assertEquals(message.getCreatedAt(), message.getNextAttemptAt());
        assertEquals(message.getCreatedAt(), message.getUpdatedAt());
        assertFalse(message.getCreatedAt().isBefore(before));
    }

    @Test
    @DisplayName("Rejeitar messageType nulo ou vazio com MISSING_MESSAGE_TYPE")
    void shouldRejectEmptyMessageType() {
        BusinessException exNull = assertThrows(BusinessException.class, () ->
                new OutboxMessage(null, PAYLOAD));
        assertEquals("MISSING_MESSAGE_TYPE", exNull.getErrorCode());

        BusinessException exBlank = assertThrows(BusinessException.class, () ->
                new OutboxMessage("   ", PAYLOAD));
        assertEquals("MISSING_MESSAGE_TYPE", exBlank.getErrorCode());
    }

    @Test
    @DisplayName("Rejeitar payload nulo com MISSING_PAYLOAD")
    void shouldRejectNullPayload() {
        BusinessException ex = assertThrows(BusinessException.class, () ->
                new OutboxMessage("PUSH_DELIVERY", null));
        assertEquals("MISSING_PAYLOAD", ex.getErrorCode());
    }

    @Test
    @DisplayName("Rejeitar attempts negativos na reidratação com INVALID_ATTEMPTS")
    void shouldRejectNegativeAttempts() {
        BusinessException ex = assertThrows(BusinessException.class, () ->
                rehydratedMessage(OutboxStatus.PENDING, -1, null, null));
        assertEquals("INVALID_ATTEMPTS", ex.getErrorCode());
    }

    @Test
    @DisplayName("Claim: PENDING -> PROCESSING registra lock do worker e incrementa attempts")
    void shouldTransitionPendingToProcessingOnClaim() {
        OutboxMessage message = new OutboxMessage("PUSH_DELIVERY", PAYLOAD);
        Instant claimTime = Instant.parse("2026-03-01T12:05:00Z");

        message.claim("worker-alpha", claimTime);

        assertEquals(OutboxStatus.PROCESSING, message.getStatus());
        assertEquals("worker-alpha", message.getLockedBy());
        assertEquals(claimTime, message.getLockedAt());
        assertEquals(1, message.getAttempts());
        assertEquals(claimTime, message.getUpdatedAt());
    }

    @Test
    @DisplayName("markCompleted: PROCESSING -> COMPLETED encerra com sucesso e libera o lease")
    void shouldTransitionProcessingToCompleted() {
        OutboxMessage message = rehydratedMessage(OutboxStatus.PROCESSING, 1,
                Instant.parse("2026-03-01T12:05:00Z"), "worker-alpha");
        Instant completionTime = Instant.parse("2026-03-01T12:06:00Z");

        message.markCompleted(completionTime);

        assertEquals(OutboxStatus.COMPLETED, message.getStatus());
        assertNull(message.getLockedAt(), "Estado terminal não retém lock de worker");
        assertNull(message.getLockedBy(), "Estado terminal não retém lock de worker");
        assertEquals(completionTime, message.getUpdatedAt());
    }

    @Test
    @DisplayName("requeue: PROCESSING -> PENDING devolve à fila limpando o lock e preservando attempts")
    void shouldTransitionProcessingToPendingOnRequeue() {
        OutboxMessage message = rehydratedMessage(OutboxStatus.PROCESSING, 3,
                Instant.parse("2026-03-01T12:05:00Z"), "worker-alpha");
        Instant requeueTime = Instant.parse("2026-03-01T12:07:00Z");

        message.requeue(requeueTime);

        assertEquals(OutboxStatus.PENDING, message.getStatus());
        assertNull(message.getLockedAt());
        assertNull(message.getLockedBy());
        assertEquals(3, message.getAttempts());
        assertEquals(requeueTime, message.getUpdatedAt());
    }

    @Test
    @DisplayName("markFailed: PROCESSING -> FAILED registra last_error, libera o lease e preserva nextAttemptAt")
    void shouldTransitionProcessingToFailed() {
        OutboxMessage message = rehydratedMessage(OutboxStatus.PROCESSING, 2,
                Instant.parse("2026-03-01T12:05:00Z"), "worker-alpha");
        Instant failTime = Instant.parse("2026-03-01T12:08:00Z");

        message.markFailed("Conexão com provider recusada", failTime);

        assertEquals(OutboxStatus.FAILED, message.getStatus());
        assertEquals("Conexão com provider recusada", message.getLastError());
        assertEquals(2, message.getAttempts());
        assertNull(message.getLockedAt(), "FAILED é terminal e não retém lock ativo");
        assertNull(message.getLockedBy(), "FAILED é terminal e não retém lock ativo");
        assertEquals(message.getCreatedAt(), message.getNextAttemptAt());
        assertEquals(failTime, message.getUpdatedAt());
    }

    @Test
    @DisplayName("retry: PROCESSING -> PENDING avança nextAttemptAt preservando attempts e lock limpo")
    void shouldRetryProcessingToPendingWithRescheduledNextAttempt() {
        OutboxMessage message = rehydratedMessage(OutboxStatus.PROCESSING, 3,
                Instant.parse("2026-03-01T12:05:00Z"), "worker-alpha");
        Instant retryAt = Instant.parse("2026-03-01T12:09:00Z");
        Instant nextAttempt = Instant.parse("2026-03-01T12:10:00Z");

        message.retry("timeout do provider", nextAttempt, retryAt);

        assertEquals(OutboxStatus.PENDING, message.getStatus());
        assertEquals(nextAttempt, message.getNextAttemptAt(),
                "next_attempt_at deve avançar para a nova janela de elegibilidade");
        assertEquals(3, message.getAttempts(), "O claim já incrementou attempts; o retry não incrementa novamente");
        assertNull(message.getLockedAt());
        assertNull(message.getLockedBy());
        assertEquals("timeout do provider", message.getLastError());
        assertEquals(retryAt, message.getUpdatedAt());
        assertEquals(Instant.parse("2026-03-01T10:00:00Z"), message.getCreatedAt(),
                "createdAt deve ser preservado pelo retry");
    }

    @Test
    @DisplayName("retry: rejeita nextAttemptAt nulo com MISSING_NEXT_ATTEMPT_AT")
    void shouldRejectRetryWithoutNextAttemptAt() {
        OutboxMessage message = rehydratedMessage(OutboxStatus.PROCESSING, 1,
                Instant.parse("2026-03-01T12:05:00Z"), "worker-alpha");

        BusinessException ex = assertThrows(BusinessException.class, () ->
                message.retry("timeout", null, Instant.now()));
        assertEquals("MISSING_NEXT_ATTEMPT_AT", ex.getErrorCode());
    }

    @Test
    @DisplayName("retry: rejeita transição a partir de PENDING com INVALID_OUTBOX_TRANSITION")
    void shouldRejectRetryFromPending() {
        OutboxMessage message = rehydratedMessage(OutboxStatus.PENDING, 0, null, null);

        BusinessException ex = assertThrows(BusinessException.class, () ->
                message.retry("timeout", Instant.now().plusSeconds(30), Instant.now()));
        assertEquals("INVALID_OUTBOX_TRANSITION", ex.getErrorCode());
    }

    @Test
    @DisplayName("Rejeitar transições a partir de COMPLETED com INVALID_OUTBOX_TRANSITION")
    void shouldRejectTransitionsFromCompleted() {
        OutboxMessage completed = rehydratedMessage(OutboxStatus.COMPLETED, 1, null, null);
        Instant now = Instant.parse("2026-03-01T12:10:00Z");

        BusinessException exClaim = assertThrows(BusinessException.class, () ->
                completed.claim("worker-beta", now));
        assertEquals("INVALID_OUTBOX_TRANSITION", exClaim.getErrorCode());

        BusinessException exComplete = assertThrows(BusinessException.class, () ->
                completed.markCompleted(now));
        assertEquals("INVALID_OUTBOX_TRANSITION", exComplete.getErrorCode());

        BusinessException exRequeue = assertThrows(BusinessException.class, () ->
                completed.requeue(now));
        assertEquals("INVALID_OUTBOX_TRANSITION", exRequeue.getErrorCode());

        BusinessException exFail = assertThrows(BusinessException.class, () ->
                completed.markFailed("erro", now));
        assertEquals("INVALID_OUTBOX_TRANSITION", exFail.getErrorCode());

        BusinessException exRetry = assertThrows(BusinessException.class, () ->
                completed.retry("erro", now.plusSeconds(30), now));
        assertEquals("INVALID_OUTBOX_TRANSITION", exRetry.getErrorCode());
    }

    @Test
    @DisplayName("Rejeitar transições a partir de FAILED com INVALID_OUTBOX_TRANSITION (terminal neste foundation)")
    void shouldRejectTransitionsFromFailed() {
        OutboxMessage failed = rehydratedMessage(OutboxStatus.FAILED, 2, null, null);
        Instant now = Instant.parse("2026-03-01T12:10:00Z");

        BusinessException exClaim = assertThrows(BusinessException.class, () ->
                failed.claim("worker-beta", now));
        assertEquals("INVALID_OUTBOX_TRANSITION", exClaim.getErrorCode());

        BusinessException exRequeue = assertThrows(BusinessException.class, () ->
                failed.requeue(now));
        assertEquals("INVALID_OUTBOX_TRANSITION", exRequeue.getErrorCode());

        BusinessException exRetry = assertThrows(BusinessException.class, () ->
                failed.retry("erro", now.plusSeconds(30), now));
        assertEquals("INVALID_OUTBOX_TRANSITION", exRetry.getErrorCode());
    }

    @Test
    @DisplayName("Reidratação preserva identificadores e timestamps exatos da persistência")
    void shouldPreserveIdentifiersAndTimestampsOnRehydration() {
        UUID id = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-03-01T10:00:00Z");
        Instant nextAttemptAt = Instant.parse("2026-03-01T10:30:00Z");
        Instant lockedAt = Instant.parse("2026-03-01T11:00:00Z");
        Instant updatedAt = Instant.parse("2026-03-01T11:00:00Z");

        OutboxMessage message = new OutboxMessage(
                id, "PUSH_DELIVERY", PAYLOAD,
                OutboxStatus.PROCESSING, 4, nextAttemptAt, lockedAt, "worker-alpha",
                "tentativa anterior falhou", createdAt, updatedAt);

        assertEquals(id, message.getId());
        assertEquals(createdAt, message.getCreatedAt());
        assertEquals(nextAttemptAt, message.getNextAttemptAt());
        assertEquals(lockedAt, message.getLockedAt());
        assertEquals("worker-alpha", message.getLockedBy());
        assertEquals("tentativa anterior falhou", message.getLastError());
        assertEquals(updatedAt, message.getUpdatedAt());
    }

    @Test
    @DisplayName("Claim incrementa attempts a partir do valor reidratado e normaliza o workerId")
    void shouldIncrementAttemptsOnClaimFromRehydratedValue() {
        OutboxMessage message = rehydratedMessage(OutboxStatus.PENDING, 5, null, null);

        message.claim("  worker-gamma  ", Instant.parse("2026-03-01T12:05:00Z"));

        assertEquals(6, message.getAttempts());
        assertEquals("worker-gamma", message.getLockedBy());
    }

    @Test
    @DisplayName("Rejeitar claim sem identificador de worker com MISSING_WORKER_ID")
    void shouldRejectClaimWithoutWorkerId() {
        OutboxMessage message = new OutboxMessage("PUSH_DELIVERY", PAYLOAD);

        BusinessException exNull = assertThrows(BusinessException.class, () ->
                message.claim(null, Instant.now()));
        assertEquals("MISSING_WORKER_ID", exNull.getErrorCode());

        BusinessException exBlank = assertThrows(BusinessException.class, () ->
                message.claim("   ", Instant.now()));
        assertEquals("MISSING_WORKER_ID", exBlank.getErrorCode());
    }

    @Test
    @DisplayName("Rejeitar status nulo na reidratação com MISSING_OUTBOX_STATUS")
    void shouldRejectNullStatusOnRehydration() {
        BusinessException ex = assertThrows(BusinessException.class, () ->
                new OutboxMessage(UUID.randomUUID(), "PUSH_DELIVERY", PAYLOAD,
                        null, 0, null, null, null, null, null, null));
        assertEquals("MISSING_OUTBOX_STATUS", ex.getErrorCode());
    }

    @Test
    @DisplayName("Rejeitar updatedAt anterior a createdAt com INVALID_TIMESTAMP_ORDER")
    void shouldRejectUpdatedAtBeforeCreatedAt() {
        BusinessException ex = assertThrows(BusinessException.class, () ->
                new OutboxMessage(UUID.randomUUID(), "PUSH_DELIVERY", PAYLOAD,
                        OutboxStatus.PENDING, 0,
                        Instant.parse("2026-03-01T10:00:00Z"), null, null, null,
                        Instant.parse("2026-03-01T10:00:00Z"),
                        Instant.parse("2026-03-01T09:59:59Z")));
        assertEquals("INVALID_TIMESTAMP_ORDER", ex.getErrorCode());
    }

    @Test
    @DisplayName("Rejeitar lock parcial (lockedAt XOR lockedBy) com INVALID_LOCK_INFORMATION")
    void shouldRejectPartialLockInformation() {
        BusinessException exMissingBy = assertThrows(BusinessException.class, () ->
                new OutboxMessage(UUID.randomUUID(), "PUSH_DELIVERY", PAYLOAD,
                        OutboxStatus.PENDING, 0, null,
                        Instant.parse("2026-03-01T12:05:00Z"), null, null, null, null));
        assertEquals("INVALID_LOCK_INFORMATION", exMissingBy.getErrorCode());

        BusinessException exMissingAt = assertThrows(BusinessException.class, () ->
                new OutboxMessage(UUID.randomUUID(), "PUSH_DELIVERY", PAYLOAD,
                        OutboxStatus.PENDING, 0, null,
                        null, "worker-alpha", null, null, null));
        assertEquals("INVALID_LOCK_INFORMATION", exMissingAt.getErrorCode());
    }

    @Test
    @DisplayName("Rejeitar PROCESSING sem lock na reidratação com INVALID_PROCESSING_STATE")
    void shouldRejectProcessingWithoutLock() {
        BusinessException ex = assertThrows(BusinessException.class, () ->
                rehydratedMessage(OutboxStatus.PROCESSING, 1, null, null));
        assertEquals("INVALID_PROCESSING_STATE", ex.getErrorCode());
    }

    private OutboxMessage rehydratedMessage(OutboxStatus status, int attempts,
                                            Instant lockedAt, String lockedBy) {
        Instant createdAt = Instant.parse("2026-03-01T10:00:00Z");
        return new OutboxMessage(
                UUID.randomUUID(), "PUSH_DELIVERY", PAYLOAD,
                status, attempts, createdAt, lockedAt, lockedBy, null, createdAt, createdAt);
    }
}
