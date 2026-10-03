package com.rewit.infrastructure.storagegc;

import com.rewit.application.dto.storage.StorageGcDtos.StorageGcCycleReport;
import com.rewit.application.dto.storage.StorageGcDtos.StorageGcRunStatus;
import com.rewit.application.port.StorageQuarantineRepository;
import com.rewit.domain.enums.StorageQuarantineStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("Testes Unitários: StorageGcMetrics (Step 28.5)")
class StorageGcMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final StorageQuarantineRepository quarantine = mock(StorageQuarantineRepository.class);
    private final StorageGcMetrics metrics = new StorageGcMetrics(registry, quarantine);

    @Test
    @DisplayName("Ciclo destrutivo incrementa os contadores a partir do relatório")
    void destructiveCycleCounters() {
        metrics.recordCycle(report(StorageGcRunStatus.COMPLETED, false));

        assertEquals(1, count(StorageGcMetrics.COUNTER_RUNS_STARTED));
        assertEquals(1, count(StorageGcMetrics.COUNTER_RUNS_COMPLETED));
        assertEquals(0, count(StorageGcMetrics.COUNTER_RUNS_DRY_RUN));
        assertEquals(10, count(StorageGcMetrics.COUNTER_OBJECTS_LISTED));
        assertEquals(2, count(StorageGcMetrics.COUNTER_OBJECTS_IGNORED));
        assertEquals(3, count(StorageGcMetrics.COUNTER_CANDIDATES_OBSERVED));
        assertEquals(4, count(StorageGcMetrics.COUNTER_RECHECKS));
        assertEquals(1, count(StorageGcMetrics.COUNTER_CANDIDATES_GRACE_PENDING));
        assertEquals(1, count(StorageGcMetrics.COUNTER_CANDIDATES_PROTECTED));
        assertEquals(2, count(StorageGcMetrics.COUNTER_CANDIDATES_ELIGIBLE));
        assertEquals(3, count(StorageGcMetrics.COUNTER_DELETES_ATTEMPTED));
        assertEquals(1, count(StorageGcMetrics.COUNTER_DELETES_SUCCEEDED));
        assertEquals(1, count(StorageGcMetrics.COUNTER_DELETES_ALREADY_ABSENT));
        assertEquals(1, count(StorageGcMetrics.COUNTER_DELETES_RETRYABLE_FAILURE));
        assertEquals(0, count(StorageGcMetrics.COUNTER_DELETES_PERMANENT_FAILURE));
        assertEquals(0, count(StorageGcMetrics.COUNTER_DELETES_WOULD_DELETE));
        assertEquals(1, registry.get(StorageGcMetrics.TIMER_RUN_DURATION).tag("mode", "destructive").timer().count());
    }

    @Test
    @DisplayName("Dry-run é distinguido: conta o que seria removido, nunca exclusões reais")
    void dryRunIsDistinguished() {
        metrics.recordCycle(report(StorageGcRunStatus.PARTIAL, true));

        assertEquals(1, count(StorageGcMetrics.COUNTER_RUNS_DRY_RUN));
        assertEquals(1, count(StorageGcMetrics.COUNTER_RUNS_PARTIAL));
        assertEquals(5, count(StorageGcMetrics.COUNTER_DELETES_WOULD_DELETE));
        assertEquals(0, count(StorageGcMetrics.COUNTER_DELETES_ATTEMPTED));
        assertEquals(0, count(StorageGcMetrics.COUNTER_DELETES_SUCCEEDED));
        assertEquals(1, registry.get(StorageGcMetrics.TIMER_RUN_DURATION).tag("mode", "dry_run").timer().count());
    }

    @Test
    @DisplayName("Ciclo ignorado pelo lock só incrementa runs.skipped")
    void skippedCycleOnlyCountsSkip() {
        metrics.recordCycle(report(StorageGcRunStatus.SKIPPED_LOCKED, false));

        assertEquals(1, count(StorageGcMetrics.COUNTER_RUNS_SKIPPED));
        assertEquals(0, count(StorageGcMetrics.COUNTER_RUNS_STARTED));
        assertEquals(0, count(StorageGcMetrics.COUNTER_OBJECTS_LISTED));
    }

    @Test
    @DisplayName("Falha e aborto têm contadores próprios")
    void failureCounters() {
        metrics.recordCycle(report(StorageGcRunStatus.FAILED, false));
        metrics.recordCycle(report(StorageGcRunStatus.ABORTED, false));
        metrics.recordCycle(report(StorageGcRunStatus.TIMED_OUT, false));

        assertEquals(1, count(StorageGcMetrics.COUNTER_RUNS_FAILED));
        assertEquals(1, count(StorageGcMetrics.COUNTER_RUNS_ABORTED));
        assertEquals(1, count(StorageGcMetrics.COUNTER_RUNS_TIMED_OUT));
        assertEquals(3, count(StorageGcMetrics.COUNTER_RUNS_STARTED));
    }

    @Test
    @DisplayName("Gauges de backlog leem a quarentena no PostgreSQL")
    void backlogGaugesReadQuarantine() {
        when(quarantine.countByStatus(StorageQuarantineStatus.OBSERVED)).thenReturn(7L);
        when(quarantine.countByStatus(StorageQuarantineStatus.CONFIRMED_ORPHAN)).thenReturn(2L);

        assertEquals(7, registry.get(StorageGcMetrics.GAUGE_QUARANTINE_OBSERVED).gauge().value());
        assertEquals(2, registry.get(StorageGcMetrics.GAUGE_QUARANTINE_CONFIRMED).gauge().value());
    }

    private double count(String name) {
        var counter = registry.find(name).counter();
        return counter == null ? 0 : counter.count();
    }

    private static StorageGcCycleReport report(StorageGcRunStatus status, boolean dryRun) {
        return new StorageGcCycleReport(status, dryRun, Instant.parse("2026-10-03T03:00:00Z"), Duration.ofSeconds(2),
                1, 10, 2, 3, true, 4, 1, 1, 2, 5,
                dryRun ? 5 : 0,
                dryRun ? 0 : 3, dryRun ? 0 : 1, dryRun ? 0 : 1, dryRun ? 0 : 1, 0, 0);
    }
}
