package com.rewit.infrastructure.outbox;

import com.rewit.application.usecase.ProcessOutboxBatchUseCase;
import com.rewit.application.usecase.ProcessOutboxBatchUseCase.ProcessOutboxBatchResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Testes de Unidade: OutboxDispatcherPoller ↔ Use Case (Step 27.2, Partes H/W/R)")
class OutboxDispatcherPollerTest {

    @Test
    @DisplayName("W.1: ciclo do poller delega integralmente ao use case (invocação direta do método, sem relógio real)")
    void shouldDelegateToUseCaseOnDirectInvocation() {
        ProcessOutboxBatchUseCase useCase = mock(ProcessOutboxBatchUseCase.class);
        when(useCase.processPendingBatch()).thenReturn(new ProcessOutboxBatchResult(0, 2, 1, 1, 0, 0, 0));
        OutboxDispatcherPoller poller = new OutboxDispatcherPoller(useCase, mock(OutboxMetrics.class));

        poller.dispatchPendingMessages();

        verify(useCase, times(1)).processPendingBatch();
    }

    @Test
    @DisplayName("W.2 + Parte R: exceção do ciclo é absorvida (não derruba o scheduler) e o próximo tick continua delegando")
    void shouldAbsorbCycleFailureAndContinueOnNextTick() {
        ProcessOutboxBatchUseCase useCase = mock(ProcessOutboxBatchUseCase.class);
        when(useCase.processPendingBatch()).thenThrow(new RuntimeException("falha simulada do ciclo"));
        OutboxDispatcherPoller poller = new OutboxDispatcherPoller(useCase, mock(OutboxMetrics.class));

        assertDoesNotThrow(() -> poller.dispatchPendingMessages(),
                "Falha do ciclo não deve propagar ao scheduler");

        doReturn(new ProcessOutboxBatchResult(0, 0, 0, 0, 0, 0, 0)).when(useCase).processPendingBatch();
        poller.dispatchPendingMessages();

        verify(useCase, times(2)).processPendingBatch();
    }
}
