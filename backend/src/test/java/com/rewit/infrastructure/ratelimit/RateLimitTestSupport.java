package com.rewit.infrastructure.ratelimit;

import com.rewit.application.port.RateLimiter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Montagem do rate limiter para testes unitários, sem Redis: o fallback em memória ocupa também o papel do
 * armazenamento distribuído, com a mesma semântica de janela deslizante.
 */
public final class RateLimitTestSupport {

    public static final String TEST_KEY_SECRET = "rate-limit-test-secret-0123456789-abcdefghijklmnop";

    private RateLimitTestSupport() {
    }

    /** Propriedades com os defaults de produção e um segredo de teste. */
    public static RateLimitProperties properties() {
        RateLimitProperties properties = new RateLimitProperties();
        properties.setKeySecret(TEST_KEY_SECRET);
        return properties;
    }

    /** Rate limiter em memória com os limites padrão e o relógio do sistema. */
    public static RateLimiter inMemory() {
        return inMemory(properties(), Clock.systemUTC(), new SimpleMeterRegistry());
    }

    public static RateLimiter inMemory(RateLimitProperties properties, Clock clock, MeterRegistry meterRegistry) {
        LocalRateLimitStore store = new LocalRateLimitStore(properties.getBackend().getLocalFallbackMaxKeys(), clock);
        return new ResilientRateLimiter(properties, new RateLimitKeyDeriver(properties.getKeySecret()), store, store,
                new RateLimitMetrics(meterRegistry), clock);
    }

    /** Relógio controlado pelo teste. */
    public static final class MutableClock extends Clock {

        private volatile Instant now;

        public MutableClock(Instant start) {
            this.now = start;
        }

        public void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
