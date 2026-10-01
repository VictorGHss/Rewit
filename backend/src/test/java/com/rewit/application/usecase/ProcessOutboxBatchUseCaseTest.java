package com.rewit.application.usecase;

import com.rewit.application.outbox.OutboxErrorSanitizer;
import com.rewit.application.outbox.OutboxPermanentException;
import com.rewit.application.outbox.OutboxRetryPolicy;
import com.rewit.application.outbox.OutboxTransientException;
import com.rewit.application.port.OutboxHandler;
import com.rewit.application.port.OutboxRepository;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.OutboxStatus;
import com.rewit.domain.model.OutboxMessage;
import com.rewit.application.usecase.ProcessOutboxBatchUseCase.ProcessOutboxBatchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Testes de Unidade: ProcessOutboxBatchUseCase (Step 27.2)")
class ProcessOutboxBatchUseCaseTest {

    private static final String WORKER_ID = "worker-unit";
    private static final int MAX_ATTEMPTS = 3;
    private static final int BATCH_SIZE = 10;

    private OutboxRepository outboxRepository;
    private OutboxHandler handler;
    private ProcessOutboxBatchUseCase useCase;

    @BeforeEach
    void setUp() {
        outboxRepository = mock(OutboxRepository.class);
        handler = mock(OutboxHandler.class);
        when(handler.supports(anyString())).thenReturn(true);
        useCase = new ProcessOutboxBatchUseCase(
                outboxRepository,
                List.of(handler),
                new OutboxRetryPolicy(Duration.ofSeconds(30), 2.0, Duration.ofMinutes(10), MAX_ATTEMPTS),
                new OutboxErrorSanitizer(512),
                WORKER_ID,
                BATCH_SIZE,
                Duration.ofMinutes(2));
    }

    @Test
    @DisplayName("U.1: lote vazio encerra o ciclo sem invocar handler nem finalização")
    void shouldHandleEmptyBatch() {
        when(outboxRepository.claimBatch(BATCH_SIZE, WORKER_ID)).thenReturn(List.of());

        ProcessOutboxBatchResult result = useCase.processPendingBatch();

        assertEquals(0, result.claimedCount());
        assertEquals(0, result.completedCount());
        verify(handler, never()).handle(any());
        verify(outboxRepository, never()).markCompleted(any(), anyString(), any());
        verify(outboxRepository, never()).markFailed(any(), anyString(), anyString(), any());
        verify(outboxRepository, never()).scheduleRetry(any(), anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("U.2: handler bem-sucedido finaliza a mensagem como COMPLETED")
    void shouldCompleteMessageAfterSuccessfulHandler() {
        OutboxMessage message = claimedMessage(1);
        when(outboxRepository.claimBatch(BATCH_SIZE, WORKER_ID)).thenReturn(List.of(message));
        when(outboxRepository.markCompleted(eq(message.getId()), eq(WORKER_ID), any())).thenReturn(true);

        ProcessOutboxBatchResult result = useCase.processPendingBatch();

        assertEquals(1, result.completedCount());
        verify(handler).handle(message);
        verify(outboxRepository, never()).markFailed(any(), anyString(), anyString(), any());
        verify(outboxRepository, never()).scheduleRetry(any(), anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("U.3: falha TRANSIENTE com crédito reagenda para o futuro (next_attempt_at futuro, last_error sanitizado)")
    void shouldScheduleRetryForTransientFailureWithRemainingAttempts() {
        Instant before = Instant.now();
        OutboxMessage message = claimedMessage(1);
        when(outboxRepository.claimBatch(BATCH_SIZE, WORKER_ID)).thenReturn(List.of(message));
        doThrow(new OutboxTransientException("provider indisponível")).when(handler).handle(message);
        when(outboxRepository.scheduleRetry(eq(message.getId()), eq(WORKER_ID), anyString(), any(), any()))
                .thenReturn(true);

        ProcessOutboxBatchResult result = useCase.processPendingBatch();

        assertEquals(1, result.retriedCount());
        ArgumentCaptor<Instant> nextAttemptCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(outboxRepository).scheduleRetry(
                eq(message.getId()), eq(WORKER_ID),
                contains("OutboxTransientException: provider indisponível"),
                nextAttemptCaptor.capture(), any());
        assertTrue(nextAttemptCaptor.getValue().isAfter(before),
                "Retry deve ser agendado no futuro (backoff de 30s)");
        verify(outboxRepository, never()).markFailed(any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("U.4: falha TRANSIENTE sem crédito (attempts >= maxAttempts) vai direto a FAILED")
    void shouldFailTransientFailureWithoutRemainingAttempts() {
        OutboxMessage message = claimedMessage(MAX_ATTEMPTS);
        when(outboxRepository.claimBatch(BATCH_SIZE, WORKER_ID)).thenReturn(List.of(message));
        doThrow(new OutboxTransientException("provider ainda indisponível")).when(handler).handle(message);
        when(outboxRepository.markFailed(eq(message.getId()), eq(WORKER_ID), anyString(), any())).thenReturn(true);

        ProcessOutboxBatchResult result = useCase.processPendingBatch();

        assertEquals(1, result.failedCount());
        verify(outboxRepository).markFailed(eq(message.getId()), eq(WORKER_ID),
                contains("OutboxTransientException: provider ainda indisponível"), any());
        verify(outboxRepository, never()).scheduleRetry(any(), anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("U.5: OutboxPermanentException é classificada como PERMANENTE e vai a FAILED imediatamente, mesmo com crédito")
    void shouldFailImmediatelyOnPermanentException() {
        OutboxMessage message = claimedMessage(1);
        when(outboxRepository.claimBatch(BATCH_SIZE, WORKER_ID)).thenReturn(List.of(message));
        doThrow(new OutboxPermanentException("payload incompatível com o handler")).when(handler).handle(message);
        when(outboxRepository.markFailed(eq(message.getId()), eq(WORKER_ID), anyString(), any())).thenReturn(true);

        ProcessOutboxBatchResult result = useCase.processPendingBatch();

        assertEquals(1, result.failedCount());
        verify(outboxRepository).markFailed(eq(message.getId()), eq(WORKER_ID),
                contains("payload incompatível"), any());
        verify(outboxRepository, never()).scheduleRetry(any(), anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("Classificação: BusinessException é rejeição determinística de negócio → PERMANENTE → FAILED")
    void shouldClassifyBusinessExceptionAsPermanent() {
        OutboxMessage message = claimedMessage(1);
        when(outboxRepository.claimBatch(BATCH_SIZE, WORKER_ID)).thenReturn(List.of(message));
        doThrow(new BusinessException("destinatário sem registro de dispositivo", "MISSING_DEVICE")).when(handler).handle(message);
        when(outboxRepository.markFailed(eq(message.getId()), eq(WORKER_ID), anyString(), any())).thenReturn(true);

        ProcessOutboxBatchResult result = useCase.processPendingBatch();

        assertEquals(1, result.failedCount());
        verify(outboxRepository, never()).scheduleRetry(any(), anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("Classificação: exceção desconhecida (RuntimeException arbitrária) é TRANSIENTE com teto de tentativas")
    void shouldClassifyUnknownExceptionAsTransient() {
        OutboxMessage message = claimedMessage(1);
        when(outboxRepository.claimBatch(BATCH_SIZE, WORKER_ID)).thenReturn(List.of(message));
        doThrow(new IllegalStateException("bug inesperado no handler")).when(handler).handle(message);
        when(outboxRepository.scheduleRetry(eq(message.getId()), eq(WORKER_ID), anyString(), any(), any()))
                .thenReturn(true);

        ProcessOutboxBatchResult result = useCase.processPendingBatch();

        assertEquals(1, result.retriedCount(), "Exceção não classificada não deve ser tratada como permanente");
        verify(outboxRepository, never()).markFailed(any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("U.6: mensagem sem handler registrado é FAILED sem invocar handle (falha de roteamento)")
    void shouldFailMessageWithoutRegisteredHandler() {
        OutboxMessage message = claimedMessageWithType("UNKNOWN_TYPE");
        when(handler.supports("UNKNOWN_TYPE")).thenReturn(false);
        when(outboxRepository.claimBatch(BATCH_SIZE, WORKER_ID)).thenReturn(List.of(message));
        when(outboxRepository.markFailed(eq(message.getId()), eq(WORKER_ID), anyString(), any())).thenReturn(true);

        ProcessOutboxBatchResult result = useCase.processPendingBatch();

        assertEquals(1, result.failedCount());
        verify(handler, never()).handle(any());
        verify(outboxRepository).markFailed(eq(message.getId()), eq(WORKER_ID),
                contains("UNKNOWN_TYPE"), any());
    }

    @Test
    @DisplayName("U.7: reclaim de leases expiradas executa antes do claim e propaga o contador")
    void shouldReclaimBeforeClaiming() {
        when(outboxRepository.reclaimExpiredLeases(any(), eq(Duration.ofMinutes(2)))).thenReturn(5);
        when(outboxRepository.claimBatch(BATCH_SIZE, WORKER_ID)).thenReturn(List.of());

        ProcessOutboxBatchResult result = useCase.processPendingBatch();

        assertEquals(5, result.reclaimedCount());
        InOrder inOrder = inOrder(outboxRepository);
        inOrder.verify(outboxRepository).reclaimExpiredLeases(any(), eq(Duration.ofMinutes(2)));
        inOrder.verify(outboxRepository).claimBatch(BATCH_SIZE, WORKER_ID);
    }

    @Test
    @DisplayName("U.8: apenas mensagens reivindicadas pelo claim são processadas pelo handler")
    void shouldProcessOnlyClaimedMessages() {
        OutboxMessage claimed = claimedMessage(1);
        when(outboxRepository.claimBatch(BATCH_SIZE, WORKER_ID)).thenReturn(List.of(claimed));
        when(outboxRepository.markCompleted(eq(claimed.getId()), eq(WORKER_ID), any())).thenReturn(true);

        ProcessOutboxBatchResult result = useCase.processPendingBatch();

        assertEquals(1, result.claimedCount());
        verify(handler, times(1)).handle(any());
        verify(handler).handle(claimed);
    }

    @Test
    @DisplayName("U.9: finalização é descartada quando o worker perdeu a posse (sem ressurreição, sem segunda tentativa)")
    void shouldDiscardFinalizationWhenOwnershipWasLost() {
        OutboxMessage message = claimedMessage(1);
        when(outboxRepository.claimBatch(BATCH_SIZE, WORKER_ID)).thenReturn(List.of(message));
        when(outboxRepository.markCompleted(eq(message.getId()), eq(WORKER_ID), any())).thenReturn(false);

        ProcessOutboxBatchResult result = useCase.processPendingBatch();

        assertEquals(1, result.lostOwnershipCount());
        assertEquals(0, result.completedCount());
        verify(outboxRepository, times(1)).markCompleted(any(), anyString(), any());
        verify(outboxRepository, never()).markFailed(any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("U.10: lote com múltiplas mensagens processa todas sequencialmente até COMPLETED")
    void shouldProcessAllMessagesInBatch() {
        OutboxMessage m1 = claimedMessage(1);
        OutboxMessage m2 = claimedMessage(1);
        OutboxMessage m3 = claimedMessage(1);
        when(outboxRepository.claimBatch(BATCH_SIZE, WORKER_ID)).thenReturn(List.of(m1, m2, m3));
        when(outboxRepository.markCompleted(any(), eq(WORKER_ID), any())).thenReturn(true);

        ProcessOutboxBatchResult result = useCase.processPendingBatch();

        assertEquals(3, result.claimedCount());
        assertEquals(3, result.completedCount());
        verify(handler).handle(m1);
        verify(handler).handle(m2);
        verify(handler).handle(m3);
    }

    @Test
    @DisplayName("U.12: erro na finalização é contado e absorvido sem mascarar a falha original nem parar o lote")
    void shouldHandleFinalizationErrorWithoutMaskingOriginalFailure() {
        OutboxMessage failing = claimedMessage(1);
        OutboxMessage healthy = claimedMessage(1);
        when(outboxRepository.claimBatch(BATCH_SIZE, WORKER_ID)).thenReturn(List.of(failing, healthy));
        doThrow(new OutboxTransientException("provider indisponível")).when(handler).handle(failing);
        when(outboxRepository.scheduleRetry(eq(failing.getId()), eq(WORKER_ID), anyString(), any(), any()))
                .thenThrow(new RuntimeException("falha simulada de banco na finalização"));
        when(outboxRepository.markCompleted(eq(healthy.getId()), eq(WORKER_ID), any())).thenReturn(true);

        ProcessOutboxBatchResult result = assertDoesNotThrow(() -> useCase.processPendingBatch(),
                "Erro de finalização não deve interromper o ciclo");

        assertEquals(1, result.finalizeErrorCount());
        assertEquals(0, result.retriedCount());
        assertEquals(1, result.completedCount(), "Mensagem seguinte do lote deve ser processada normalmente");
    }

    @Test
    @DisplayName("Construtor valida workerId, batchSize e leaseDuration obrigatórios")
    void shouldValidateConstructionArguments() {
        assertThrows(IllegalArgumentException.class, () -> new ProcessOutboxBatchUseCase(
                outboxRepository, List.of(handler), policy(), sanitizer(), "   ", BATCH_SIZE, Duration.ofMinutes(2)));
        assertThrows(IllegalArgumentException.class, () -> new ProcessOutboxBatchUseCase(
                outboxRepository, List.of(handler), policy(), sanitizer(), WORKER_ID, 0, Duration.ofMinutes(2)));
        assertThrows(IllegalArgumentException.class, () -> new ProcessOutboxBatchUseCase(
                outboxRepository, List.of(handler), policy(), sanitizer(), WORKER_ID, BATCH_SIZE, null));
    }

    private OutboxRetryPolicy policy() {
        return new OutboxRetryPolicy(Duration.ofSeconds(30), 2.0, Duration.ofMinutes(10), MAX_ATTEMPTS);
    }

    private OutboxErrorSanitizer sanitizer() {
        return new OutboxErrorSanitizer(512);
    }

    private OutboxMessage claimedMessage(int attempts) {
        return claimedMessageWithType("PUSH_DELIVERY", attempts);
    }

    private OutboxMessage claimedMessageWithType(String messageType) {
        return claimedMessageWithType(messageType, 1);
    }

    private OutboxMessage claimedMessageWithType(String messageType, int attempts) {
        Instant now = Instant.now();
        return new OutboxMessage(
                UUID.randomUUID(), messageType, "{\"notificationId\":\"" + UUID.randomUUID() + "\"}",
                OutboxStatus.PROCESSING, attempts, now, now, WORKER_ID, null, now, now);
    }
}
