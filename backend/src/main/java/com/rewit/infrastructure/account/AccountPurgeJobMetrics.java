package com.rewit.infrastructure.account;

import com.rewit.application.usecase.PurgeEligibleAccountsUseCase.AccountPurgeRunResult;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.util.Objects;

/**
 * Métricas do job de purge de contas (C2.3), sem tags de identidade (ADR-012): contagens por passada, passadas
 * bloqueadas por falta do segredo, falhas de passada e duração.
 */
public class AccountPurgeJobMetrics {

    public static final String COUNTER_PURGED = "rewit.account_purge.purged";
    public static final String COUNTER_ACCOUNT_FAILURES = "rewit.account_purge.account_failures";
    public static final String COUNTER_BLOCKED_RUNS = "rewit.account_purge.blocked_runs";
    public static final String COUNTER_RUN_FAILURES = "rewit.account_purge.run_failures";
    public static final String TIMER_DURATION = "rewit.account_purge.duration";

    private final MeterRegistry meterRegistry;

    public AccountPurgeJobMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "MeterRegistry must not be null");
    }

    public void recordRun(AccountPurgeRunResult result, Duration duration) {
        Objects.requireNonNull(result, "result must not be null");
        meterRegistry.counter(COUNTER_PURGED).increment(result.purged());
        meterRegistry.counter(COUNTER_ACCOUNT_FAILURES).increment(result.failed());
        if (result.secretMissing()) {
            meterRegistry.counter(COUNTER_BLOCKED_RUNS).increment();
        }
        recordDuration(duration);
    }

    public void recordFailure(Duration duration) {
        meterRegistry.counter(COUNTER_RUN_FAILURES).increment();
        recordDuration(duration);
    }

    private void recordDuration(Duration duration) {
        Objects.requireNonNull(duration, "duration must not be null");
        if (!duration.isNegative()) {
            Timer.builder(TIMER_DURATION)
                    .description("Duração das passadas do purge de contas excluídas")
                    .register(meterRegistry)
                    .record(duration);
        }
    }
}
