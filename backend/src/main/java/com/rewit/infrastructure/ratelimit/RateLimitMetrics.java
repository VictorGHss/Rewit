package com.rewit.infrastructure.ratelimit;

import com.rewit.application.ratelimit.RateLimitedAction;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.Locale;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Métricas Micrometer do rate limiting. Tags apenas de conjuntos fechados (ação, resultado, origem da decisão,
 * operação); nunca sujeito, chave, e-mail, usuário ou IP.
 */
public class RateLimitMetrics {

    public static final String COUNTER_DECISIONS = "rewit.rate_limit.decisions";
    public static final String COUNTER_BACKEND_ERRORS = "rewit.rate_limit.backend_errors";
    public static final String GAUGE_BACKEND_AVAILABLE = "rewit.rate_limit.backend_available";

    /** Quem decidiu: o Redis, o fallback em memória ou a política de falha ({@code DENY}/{@code ALLOW}). */
    public enum Source {
        REDIS, LOCAL_FALLBACK, FAILURE_POLICY;

        String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Operação no Redis que falhou. */
    public enum Operation {
        ACQUIRE, RELEASE;

        String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final MeterRegistry meterRegistry;

    public RateLimitMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "MeterRegistry must not be null");
    }

    /**
     * Registra o gauge de disponibilidade (1 = Redis em uso, 0 = modo degradado).
     */
    public void bindBackendAvailability(BooleanSupplier available) {
        Objects.requireNonNull(available, "available must not be null");
        Gauge.builder(GAUGE_BACKEND_AVAILABLE, available, supplier -> supplier.getAsBoolean() ? 1 : 0)
                .description("1 quando o rate limiting usa o Redis; 0 em modo degradado")
                .strongReference(true)
                .register(meterRegistry);
    }

    public void recordDecision(RateLimitedAction action, boolean allowed, Source source) {
        Counter.builder(COUNTER_DECISIONS)
                .description("Decisões do rate limiting")
                .tag("action", action.name().toLowerCase(Locale.ROOT))
                .tag("outcome", allowed ? "allowed" : "denied")
                .tag("source", source.tag())
                .register(meterRegistry)
                .increment();
    }

    public void recordBackendError(Operation operation) {
        Counter.builder(COUNTER_BACKEND_ERRORS)
                .description("Falhas de comunicação do rate limiting com o Redis")
                .tag("operation", operation.tag())
                .register(meterRegistry)
                .increment();
    }
}
