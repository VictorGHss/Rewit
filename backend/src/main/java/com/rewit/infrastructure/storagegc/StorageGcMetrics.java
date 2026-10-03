package com.rewit.infrastructure.storagegc;

import com.rewit.application.dto.storage.StorageGcDtos.StorageGcCycleReport;
import com.rewit.application.port.StorageGcCycleObserver;
import com.rewit.application.port.StorageQuarantineRepository;
import com.rewit.domain.enums.StorageQuarantineStatus;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.Objects;

/**
 * Métricas Micrometer do GC de storage (Step 28.5), separadas das do Outbox.
 *
 * <p>Counters derivados exclusivamente do {@link StorageGcCycleReport} de cada ciclo, sem tags e sem
 * object keys. O timer de duração tem apenas a tag {@code mode} (dry_run/destructive). Gauges de backlog
 * consultam o PostgreSQL, fonte de verdade da quarentena, a cada scrape.
 */
public class StorageGcMetrics implements StorageGcCycleObserver {

    public static final String COUNTER_RUNS_STARTED = "rewit.storage_gc.runs.started";
    public static final String COUNTER_RUNS_COMPLETED = "rewit.storage_gc.runs.completed";
    public static final String COUNTER_RUNS_PARTIAL = "rewit.storage_gc.runs.partial";
    public static final String COUNTER_RUNS_TIMED_OUT = "rewit.storage_gc.runs.timed_out";
    public static final String COUNTER_RUNS_ABORTED = "rewit.storage_gc.runs.aborted";
    public static final String COUNTER_RUNS_FAILED = "rewit.storage_gc.runs.failed";
    public static final String COUNTER_RUNS_SKIPPED = "rewit.storage_gc.runs.skipped";
    public static final String COUNTER_RUNS_DRY_RUN = "rewit.storage_gc.runs.dry_run";
    public static final String COUNTER_OBJECTS_LISTED = "rewit.storage_gc.objects.listed";
    public static final String COUNTER_OBJECTS_IGNORED = "rewit.storage_gc.objects.ignored";
    public static final String COUNTER_CANDIDATES_OBSERVED = "rewit.storage_gc.candidates.observed";
    public static final String COUNTER_RECHECKS = "rewit.storage_gc.rechecks";
    public static final String COUNTER_CANDIDATES_GRACE_PENDING = "rewit.storage_gc.candidates.grace_pending";
    public static final String COUNTER_CANDIDATES_ELIGIBLE = "rewit.storage_gc.candidates.eligible";
    public static final String COUNTER_CANDIDATES_PROTECTED = "rewit.storage_gc.candidates.protected";
    public static final String COUNTER_CANDIDATE_ERRORS = "rewit.storage_gc.candidates.errors";
    public static final String COUNTER_DELETES_WOULD_DELETE = "rewit.storage_gc.deletes.would_delete";
    public static final String COUNTER_DELETES_ATTEMPTED = "rewit.storage_gc.deletes.attempted";
    public static final String COUNTER_DELETES_SUCCEEDED = "rewit.storage_gc.deletes.succeeded";
    public static final String COUNTER_DELETES_ALREADY_ABSENT = "rewit.storage_gc.deletes.already_absent";
    public static final String COUNTER_DELETES_RETRYABLE_FAILURE = "rewit.storage_gc.deletes.retryable_failure";
    public static final String COUNTER_DELETES_PERMANENT_FAILURE = "rewit.storage_gc.deletes.permanent_failure";
    public static final String TIMER_RUN_DURATION = "rewit.storage_gc.run.duration";
    public static final String GAUGE_QUARANTINE_OBSERVED = "rewit.storage_gc.quarantine.observed";
    public static final String GAUGE_QUARANTINE_CONFIRMED = "rewit.storage_gc.quarantine.confirmed";

    private final MeterRegistry meterRegistry;

    public StorageGcMetrics(MeterRegistry meterRegistry, StorageQuarantineRepository quarantineRepository) {
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "MeterRegistry must not be null");
        Objects.requireNonNull(quarantineRepository, "StorageQuarantineRepository must not be null");

        Gauge.builder(GAUGE_QUARANTINE_OBSERVED, quarantineRepository,
                        repo -> repo.countByStatus(StorageQuarantineStatus.OBSERVED))
                .description("Objetos de storage em quarentena aguardando grace period/rechecagem (fonte: PostgreSQL)")
                .register(meterRegistry);
        Gauge.builder(GAUGE_QUARANTINE_CONFIRMED, quarantineRepository,
                        repo -> repo.countByStatus(StorageQuarantineStatus.CONFIRMED_ORPHAN))
                .description("Órfãos confirmados aguardando exclusão física (fonte: PostgreSQL)")
                .register(meterRegistry);
    }

    @Override
    public void recordCycle(StorageGcCycleReport report) {
        Objects.requireNonNull(report, "report must not be null");
        switch (report.status()) {
            case SKIPPED_LOCKED -> {
                increment(COUNTER_RUNS_SKIPPED, 1);
                return;
            }
            case COMPLETED -> increment(COUNTER_RUNS_COMPLETED, 1);
            case PARTIAL -> increment(COUNTER_RUNS_PARTIAL, 1);
            case TIMED_OUT -> increment(COUNTER_RUNS_TIMED_OUT, 1);
            case ABORTED -> increment(COUNTER_RUNS_ABORTED, 1);
            case FAILED -> increment(COUNTER_RUNS_FAILED, 1);
        }
        increment(COUNTER_RUNS_STARTED, 1);
        if (report.dryRun()) {
            increment(COUNTER_RUNS_DRY_RUN, 1);
        }
        increment(COUNTER_OBJECTS_LISTED, report.objectsListed());
        increment(COUNTER_OBJECTS_IGNORED, report.objectsIgnored());
        increment(COUNTER_CANDIDATES_OBSERVED, report.candidatesObserved());
        increment(COUNTER_RECHECKS, report.rechecks());
        increment(COUNTER_CANDIDATES_GRACE_PENDING, report.gracePending());
        increment(COUNTER_CANDIDATES_ELIGIBLE, report.confirmedOrphans());
        increment(COUNTER_CANDIDATES_PROTECTED, report.protectedByReference());
        increment(COUNTER_CANDIDATE_ERRORS, report.candidateErrors());
        increment(COUNTER_DELETES_WOULD_DELETE, report.wouldDelete());
        increment(COUNTER_DELETES_ATTEMPTED, report.deletesAttempted());
        increment(COUNTER_DELETES_SUCCEEDED, report.deleted());
        increment(COUNTER_DELETES_ALREADY_ABSENT, report.alreadyAbsent());
        increment(COUNTER_DELETES_RETRYABLE_FAILURE, report.retryableFailures());
        increment(COUNTER_DELETES_PERMANENT_FAILURE, report.permanentFailures());
        Timer.builder(TIMER_RUN_DURATION)
                .description("Duração dos ciclos do storage GC")
                .tag("mode", report.dryRun() ? "dry_run" : "destructive")
                .register(meterRegistry)
                .record(report.duration());
    }

    private void increment(String name, double amount) {
        meterRegistry.counter(name).increment(amount);
    }
}
