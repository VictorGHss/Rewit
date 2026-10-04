package com.rewit.infrastructure.authsession;

import com.rewit.application.usecase.CleanupAuthSessionsUseCase.AuthSessionCleanupResult;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.util.Objects;

/**
 * Métricas Micrometer do cleanup de {@code auth_sessions} (Step 29.3): contagens agregadas por passada,
 * sem tags e sem identificadores de sessão.
 */
public class AuthSessionCleanupMetrics {

    public static final String COUNTER_METADATA_CLEARED = "rewit.auth_session_cleanup.metadata_cleared";
    public static final String COUNTER_SESSIONS_PURGED = "rewit.auth_session_cleanup.sessions_purged";
    public static final String COUNTER_FAILURES = "rewit.auth_session_cleanup.failures";
    public static final String TIMER_DURATION = "rewit.auth_session_cleanup.duration";

    private final MeterRegistry meterRegistry;

    public AuthSessionCleanupMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "MeterRegistry must not be null");
    }

    public void recordCycle(AuthSessionCleanupResult result, Duration duration) {
        Objects.requireNonNull(result, "result must not be null");
        meterRegistry.counter(COUNTER_METADATA_CLEARED).increment(result.metadataCleared());
        meterRegistry.counter(COUNTER_SESSIONS_PURGED).increment(result.sessionsPurged());
        recordDuration(duration);
    }

    public void recordFailure(Duration duration) {
        meterRegistry.counter(COUNTER_FAILURES).increment();
        recordDuration(duration);
    }

    private void recordDuration(Duration duration) {
        Objects.requireNonNull(duration, "duration must not be null");
        if (!duration.isNegative()) {
            Timer.builder(TIMER_DURATION)
                    .description("Duração das passadas do cleanup de auth_sessions")
                    .register(meterRegistry)
                    .record(duration);
        }
    }
}
