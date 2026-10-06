package com.rewit.infrastructure.ratelimit;

import com.rewit.application.ratelimit.RateLimitPermit;
import com.rewit.application.ratelimit.RateLimitSubject;
import com.rewit.application.ratelimit.RateLimitedAction;
import com.rewit.common.exception.BusinessException;
import com.rewit.infrastructure.ratelimit.RateLimitTestSupport.MutableClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(OutputCaptureExtension.class)
@DisplayName("Rate limiting: orquestração, modo degradado e métricas")
class ResilientRateLimiterTest {

    private static final String EMAIL = "vitima@rewit.com";
    private static final RateLimitSubject IDENTITY = RateLimitSubject.ofIdentity(EMAIL);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-06T12:00:00Z"));
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ToggleableStore backend = new ToggleableStore(clock);

    @Test
    @DisplayName("Com o Redis disponível, decide pelo Redis e devolve a tentativa no Redis")
    void usesBackendWhenAvailable() {
        RateLimitProperties properties = loginLimit(2);
        ResilientRateLimiter limiter = limiter(properties);

        Optional<RateLimitPermit> first = limiter.tryAcquire(RateLimitedAction.LOGIN, IDENTITY);
        limiter.tryAcquire(RateLimitedAction.LOGIN, IDENTITY);
        assertTrue(limiter.tryAcquire(RateLimitedAction.LOGIN, IDENTITY).isEmpty());

        limiter.release(first.orElseThrow());
        assertTrue(limiter.tryAcquire(RateLimitedAction.LOGIN, IDENTITY).isPresent());

        assertEquals(3, decisions("login", "allowed", "redis"));
        assertEquals(1, decisions("login", "denied", "redis"));
        assertEquals(1.0, availability());
    }

    @Test
    @DisplayName("acquireOrThrow rejeita com 429 RATE_LIMIT_EXCEEDED e a mensagem da ação")
    void acquireOrThrowMapsToTooManyRequests() {
        ResilientRateLimiter limiter = limiter(loginLimit(1));
        limiter.acquireOrThrow(RateLimitedAction.LOGIN, IDENTITY);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> limiter.acquireOrThrow(RateLimitedAction.LOGIN, IDENTITY));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.getStatus());
        assertEquals("RATE_LIMIT_EXCEEDED", ex.getErrorCode());
        assertEquals(RateLimitedAction.LOGIN.exceededMessage(), ex.getMessage());
    }

    @Test
    @DisplayName("Redis indisponível com LOCAL_FALLBACK: os mesmos limites seguem aplicados em memória")
    void localFallbackKeepsEnforcingLimits() {
        ResilientRateLimiter limiter = limiter(loginLimit(2));
        backend.down.set(true);

        assertTrue(limiter.tryAcquire(RateLimitedAction.LOGIN, IDENTITY).isPresent());
        assertTrue(limiter.tryAcquire(RateLimitedAction.LOGIN, IDENTITY).isPresent());
        assertTrue(limiter.tryAcquire(RateLimitedAction.LOGIN, IDENTITY).isEmpty());

        assertEquals(2, decisions("login", "allowed", "local_fallback"));
        assertEquals(1, decisions("login", "denied", "local_fallback"));
        assertEquals(1, backendErrors("acquire"));
        assertEquals(0.0, availability());
    }

    @Test
    @DisplayName("Login bem-sucedido em modo degradado devolve a tentativa no fallback local")
    void releaseInFallbackGoesToLocalStore() {
        ResilientRateLimiter limiter = limiter(loginLimit(1));
        backend.down.set(true);

        RateLimitPermit permit = limiter.tryAcquire(RateLimitedAction.LOGIN, IDENTITY).orElseThrow();
        limiter.release(permit);

        assertTrue(limiter.tryAcquire(RateLimitedAction.LOGIN, IDENTITY).isPresent());
        assertEquals(0, backend.releases.get());
    }

    @Test
    @DisplayName("Redis indisponível com DENY: toda tentativa é negada pela política de falha")
    void denyPolicyRejects() {
        RateLimitProperties properties = loginLimit(5);
        properties.getBackend().setFailureMode(RateLimitBackendFailureMode.DENY);
        ResilientRateLimiter limiter = limiter(properties);
        backend.down.set(true);

        assertTrue(limiter.tryAcquire(RateLimitedAction.LOGIN, IDENTITY).isEmpty());
        assertEquals(1, decisions("login", "denied", "failure_policy"));
    }

    @Test
    @DisplayName("Redis indisponível com ALLOW: toda tentativa é concedida, mas fica registrada nas métricas")
    void allowPolicyGrantsVisibly() {
        RateLimitProperties properties = loginLimit(1);
        properties.getBackend().setFailureMode(RateLimitBackendFailureMode.ALLOW);
        ResilientRateLimiter limiter = limiter(properties);
        backend.down.set(true);

        for (int i = 0; i < 3; i++) {
            assertTrue(limiter.tryAcquire(RateLimitedAction.LOGIN, IDENTITY).isPresent());
        }
        assertEquals(3, decisions("login", "allowed", "failure_policy"));
        assertEquals(1, backendErrors("acquire"));
        assertEquals(0.0, availability());
    }

    @Test
    @DisplayName("Após uma falha, o Redis não é consultado até o intervalo; depois, uma requisição o testa e ele volta")
    void backendIsRetriedOnlyAfterInterval(CapturedOutput output) {
        ResilientRateLimiter limiter = limiter(loginLimit(100));
        backend.down.set(true);

        limiter.tryAcquire(RateLimitedAction.LOGIN, IDENTITY);
        assertEquals(1, backend.acquireCalls.get());

        // Dentro do intervalo: decide em memória, sem esperar o timeout do Redis
        clock.advance(Duration.ofSeconds(9));
        limiter.tryAcquire(RateLimitedAction.LOGIN, IDENTITY);
        assertEquals(1, backend.acquireCalls.get());

        // Vencido o intervalo, o Redis ainda fora: uma tentativa de teste e novo intervalo, sem novo WARN
        clock.advance(Duration.ofSeconds(1));
        limiter.tryAcquire(RateLimitedAction.LOGIN, IDENTITY);
        limiter.tryAcquire(RateLimitedAction.LOGIN, IDENTITY);
        assertEquals(2, backend.acquireCalls.get());
        assertEquals(2, backendErrors("acquire"));

        // Redis de volta: o próximo teste o encontra e o modo degradado termina
        backend.down.set(false);
        clock.advance(Duration.ofSeconds(10));
        assertTrue(limiter.tryAcquire(RateLimitedAction.LOGIN, IDENTITY).isPresent());
        assertEquals(1.0, availability());
        limiter.tryAcquire(RateLimitedAction.LOGIN, IDENTITY);
        assertEquals(4, backend.acquireCalls.get());

        assertEquals(1, occurrences(output.getOut(), "Redis indisponível"));
        assertEquals(1, occurrences(output.getOut(), "Redis disponível novamente"));
    }

    @Test
    @DisplayName("Logs do modo degradado não contêm sujeito, chave nem a mensagem da causa")
    void degradedLogsHaveNoIdentifiableData(CapturedOutput output) {
        ResilientRateLimiter limiter = limiter(loginLimit(5));
        backend.down.set(true);

        limiter.tryAcquire(RateLimitedAction.LOGIN, IDENTITY);

        String logs = output.getOut() + output.getErr();
        assertTrue(logs.contains("Rate limiting: Redis indisponível"));
        assertTrue(logs.contains("erro=QueryTimeoutException"));
        assertFalse(logs.contains(EMAIL));
        assertFalse(logs.contains("vitima"));
        assertFalse(logs.contains("rewit:rate-limit:"));
        assertFalse(logs.contains(ToggleableStore.CAUSE_DETAIL));
    }

    @Test
    @DisplayName("Falha ao devolver a tentativa não propaga erro: a tentativa segue contada e a falha é medida")
    void releaseFailureIsSwallowed() {
        ResilientRateLimiter limiter = limiter(loginLimit(5));
        RateLimitPermit permit = limiter.tryAcquire(RateLimitedAction.LOGIN, IDENTITY).orElseThrow();
        backend.down.set(true);

        limiter.release(permit);

        assertEquals(1, backendErrors("release"));
        assertEquals(0.0, availability());
    }

    @Test
    @DisplayName("Rate limiting desligado (global ou por ação) concede sem consultar o Redis nem gerar métricas")
    void disabledSkipsBackend() {
        RateLimitProperties globallyDisabled = loginLimit(1);
        globallyDisabled.setEnabled(false);
        ResilientRateLimiter disabled = limiter(globallyDisabled);
        RateLimitProperties actionDisabled = loginLimit(1);
        actionDisabled.getAuth().getLogin().setEnabled(false);
        ResilientRateLimiter loginDisabled = limiter(actionDisabled);

        for (int i = 0; i < 3; i++) {
            RateLimitPermit permit = disabled.tryAcquire(RateLimitedAction.LOGIN, IDENTITY).orElseThrow();
            disabled.release(permit);
            assertTrue(loginDisabled.tryAcquire(RateLimitedAction.LOGIN, IDENTITY).isPresent());
        }
        assertEquals(0, backend.acquireCalls.get());
        assertTrue(registry.find(RateLimitMetrics.COUNTER_DECISIONS).counters().isEmpty());
    }

    @Test
    @DisplayName("Métricas só têm tags de conjuntos fechados")
    void metricTagsAreLowCardinality() {
        ResilientRateLimiter limiter = limiter(loginLimit(5));
        limiter.tryAcquire(RateLimitedAction.LOGIN, IDENTITY);
        limiter.tryAcquire(RateLimitedAction.REFRESH, RateLimitSubject.ofUser(UUID.randomUUID()));

        registry.getMeters().forEach(meter -> meter.getId().getTags().forEach(tag -> {
            assertTrue(tag.getKey().matches("action|outcome|source|operation"), tag.getKey());
            assertTrue(tag.getValue().matches("[a-z_]+"), tag.getValue());
        }));
    }

    private ResilientRateLimiter limiter(RateLimitProperties properties) {
        return new ResilientRateLimiter(properties, new RateLimitKeyDeriver(RateLimitTestSupport.TEST_KEY_SECRET),
                backend, new LocalRateLimitStore(1000, clock), new RateLimitMetrics(registry), clock);
    }

    private static RateLimitProperties loginLimit(int limit) {
        RateLimitProperties properties = RateLimitTestSupport.properties();
        properties.getAuth().getLogin().setLimit(limit);
        return properties;
    }

    private double decisions(String action, String outcome, String source) {
        var counter = registry.find(RateLimitMetrics.COUNTER_DECISIONS)
                .tags("action", action, "outcome", outcome, "source", source).counter();
        return counter == null ? 0 : counter.count();
    }

    private double backendErrors(String operation) {
        var counter = registry.find(RateLimitMetrics.COUNTER_BACKEND_ERRORS).tag("operation", operation).counter();
        return counter == null ? 0 : counter.count();
    }

    private double availability() {
        return registry.get(RateLimitMetrics.GAUGE_BACKEND_AVAILABLE).gauge().value();
    }

    private static int occurrences(String text, String fragment) {
        return text.split(java.util.regex.Pattern.quote(fragment), -1).length - 1;
    }

    /** Armazenamento "distribuído" em memória que pode simular o Redis fora do ar. */
    private static final class ToggleableStore implements RateLimitStore {

        static final String CAUSE_DETAIL = "Command timed out after 3 second(s) on 10.0.0.7:6379";

        final AtomicBoolean down = new AtomicBoolean();
        final AtomicInteger acquireCalls = new AtomicInteger();
        final AtomicInteger releases = new AtomicInteger();
        private final LocalRateLimitStore delegate;

        ToggleableStore(MutableClock clock) {
            this.delegate = new LocalRateLimitStore(1000, clock);
        }

        @Override
        public Optional<String> tryAcquire(String key, int limit, Duration window) {
            acquireCalls.incrementAndGet();
            failIfDown();
            return delegate.tryAcquire(key, limit, window);
        }

        @Override
        public void release(String key, String member) {
            releases.incrementAndGet();
            failIfDown();
            delegate.release(key, member);
        }

        private void failIfDown() {
            if (down.get()) {
                throw new RateLimitBackendException(new QueryTimeoutException(CAUSE_DETAIL));
            }
        }
    }
}
