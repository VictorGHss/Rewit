package com.rewit.infrastructure.outbox;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.rewit.application.port.OutboxRepository;
import com.rewit.application.usecase.ProcessOutboxBatchUseCase.ProcessOutboxBatchResult;
import com.rewit.domain.enums.OutboxStatus;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

@DisplayName("Testes de Unidade: métricas do Outbox (Step 27.4)")
class OutboxMetricsTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final OutboxRepository outboxRepository = mock(OutboxRepository.class);

    @Test
    @DisplayName("gauges consultam PENDING e FAILED no repositório")
    void shouldReadPendingAndFailedFromRepository() {
        when(outboxRepository.countByStatus(OutboxStatus.PENDING)).thenReturn(3L);
        when(outboxRepository.countByStatus(OutboxStatus.FAILED)).thenReturn(2L);
        when(outboxRepository.oldestPendingCreatedAt()).thenReturn(Optional.empty());

        OutboxMetrics metrics = new OutboxMetrics(meterRegistry, outboxRepository);
        assertNotNull(metrics);

        assertEquals(3d, meterRegistry.get(OutboxMetrics.GAUGE_PENDING).gauge().value());
        assertEquals(2d, meterRegistry.get(OutboxMetrics.GAUGE_FAILED).gauge().value());
    }

    @Test
    @DisplayName("gauges retornam zero quando não há mensagens PENDING ou FAILED")
    void shouldReportZeroForEmptyStatuses() {
        when(outboxRepository.countByStatus(OutboxStatus.PENDING)).thenReturn(0L);
        when(outboxRepository.countByStatus(OutboxStatus.FAILED)).thenReturn(0L);
        when(outboxRepository.oldestPendingCreatedAt()).thenReturn(Optional.empty());

        OutboxMetrics metrics = new OutboxMetrics(meterRegistry, outboxRepository);
        assertNotNull(metrics);

        assertEquals(0d, meterRegistry.get(OutboxMetrics.GAUGE_PENDING).gauge().value());
        assertEquals(0d, meterRegistry.get(OutboxMetrics.GAUGE_FAILED).gauge().value());
        assertEquals(0d, meterRegistry.get(OutboxMetrics.GAUGE_OLDEST_PENDING_AGE).gauge().value());
    }

    @Test
    @DisplayName("oldest pending age é derivada do timestamp mais antigo sem sleep")
    void shouldReportOldestPendingAge() {
        when(outboxRepository.countByStatus(OutboxStatus.PENDING)).thenReturn(2L);
        when(outboxRepository.countByStatus(OutboxStatus.FAILED)).thenReturn(0L);
        when(outboxRepository.oldestPendingCreatedAt()).thenReturn(Optional.of(Instant.now().minusSeconds(60)));

        OutboxMetrics metrics = new OutboxMetrics(meterRegistry, outboxRepository);
        assertNotNull(metrics);

        double age = meterRegistry.get(OutboxMetrics.GAUGE_OLDEST_PENDING_AGE).gauge().value();
        assertEquals(true, age >= 60d);
        assertEquals(true, age < 65d);
    }

    @Test
    @DisplayName("counters são derivados do resultado estruturado do ciclo")
    void shouldRecordCycleCounters() {
        when(outboxRepository.oldestPendingCreatedAt()).thenReturn(Optional.empty());
        OutboxMetrics metrics = new OutboxMetrics(meterRegistry, outboxRepository);

        metrics.recordCycle(new ProcessOutboxBatchResult(2, 3, 4, 5, 6, 7, 8));

        assertEquals(2d, meterRegistry.get(OutboxMetrics.COUNTER_LEASES_RECLAIMED).counter().count());
        assertEquals(3d, meterRegistry.get(OutboxMetrics.COUNTER_MESSAGES_CLAIMED).counter().count());
        assertEquals(4d, meterRegistry.get(OutboxMetrics.COUNTER_MESSAGES_COMPLETED).counter().count());
        assertEquals(5d, meterRegistry.get(OutboxMetrics.COUNTER_MESSAGES_RETRIED).counter().count());
        assertEquals(6d, meterRegistry.get(OutboxMetrics.COUNTER_MESSAGES_FAILED).counter().count());
        assertEquals(7d, meterRegistry.get(OutboxMetrics.COUNTER_MESSAGES_LOST_OWNERSHIP).counter().count());
        assertEquals(8d, meterRegistry.get(OutboxMetrics.COUNTER_FINALIZE_ERRORS).counter().count());
    }
}