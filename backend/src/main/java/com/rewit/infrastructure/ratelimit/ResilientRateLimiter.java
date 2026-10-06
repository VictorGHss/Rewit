package com.rewit.infrastructure.ratelimit;

import com.rewit.application.port.RateLimiter;
import com.rewit.application.ratelimit.RateLimitPermit;
import com.rewit.application.ratelimit.RateLimitSubject;
import com.rewit.application.ratelimit.RateLimitedAction;
import com.rewit.common.exception.BusinessException;
import com.rewit.infrastructure.ratelimit.RateLimitMetrics.Operation;
import com.rewit.infrastructure.ratelimit.RateLimitMetrics.Source;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Implementação da porta {@link RateLimiter}: janela deslizante no armazenamento distribuído (Redis), com chaves
 * derivadas por HMAC e modo degradado explícito quando o Redis não responde.
 *
 * <p>Após uma falha, o Redis deixa de ser consultado por {@code backend.retry-interval}: as requisições seguintes
 * decidem pelo modo degradado sem esperar o timeout do cliente Redis. Vencido o intervalo, uma única requisição
 * volta a testar o Redis. A transição para o modo degradado e a recuperação geram um log cada; toda falha
 * incrementa {@code rewit.rate_limit.backend_errors} e o gauge {@code rewit.rate_limit.backend_available} fica em 0.
 * Logs trazem só a classe do erro, nunca a chave ou o sujeito.
 */
public class ResilientRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(ResilientRateLimiter.class);

    private static final RateLimitPermit UNTRACKED = new RateLimitPermit() {
        @Override
        public String toString() {
            return "RateLimitPermit[untracked]";
        }
    };

    private final RateLimitProperties properties;
    private final RateLimitKeyDeriver keyDeriver;
    private final RateLimitStore backendStore;
    private final RateLimitStore fallbackStore;
    private final RateLimitMetrics metrics;
    private final Clock clock;

    /** 0 = Redis em uso; caso contrário, instante (ms) a partir do qual uma requisição volta a testar o Redis. */
    private final AtomicLong backendRetryAtMillis = new AtomicLong();

    ResilientRateLimiter(RateLimitProperties properties,
                         RateLimitKeyDeriver keyDeriver,
                         RateLimitStore backendStore,
                         RateLimitStore fallbackStore,
                         RateLimitMetrics metrics,
                         Clock clock) {
        this.properties = Objects.requireNonNull(properties, "RateLimitProperties must not be null");
        this.keyDeriver = Objects.requireNonNull(keyDeriver, "RateLimitKeyDeriver must not be null");
        this.backendStore = Objects.requireNonNull(backendStore, "backendStore must not be null");
        this.fallbackStore = Objects.requireNonNull(fallbackStore, "fallbackStore must not be null");
        this.metrics = Objects.requireNonNull(metrics, "RateLimitMetrics must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        metrics.bindBackendAvailability(this::isBackendAvailable);
    }

    @Override
    public Optional<RateLimitPermit> tryAcquire(RateLimitedAction action, RateLimitSubject subject) {
        Acquisition acquisition = attemptAcquire(action, subject);
        return Optional.ofNullable(acquisition.permit());
    }

    @Override
    public RateLimitPermit acquireOrThrow(RateLimitedAction action, RateLimitSubject subject) {
        Acquisition acquisition = attemptAcquire(action, subject);
        if (acquisition.isGranted()) {
            return acquisition.permit();
        }
        throw new BusinessException(action.exceededMessage(), HttpStatus.TOO_MANY_REQUESTS, ERROR_CODE, acquisition.retryAfter());
    }

    @Override
    public void release(RateLimitPermit permit) {
        if (!(permit instanceof StorePermit storePermit)) {
            return;
        }
        if (storePermit.fallback()) {
            fallbackStore.release(storePermit.key(), storePermit.member());
            return;
        }
        try {
            backendStore.release(storePermit.key(), storePermit.member());
        } catch (RateLimitBackendException e) {
            // A tentativa continua contada até sair da janela; o chamador não é afetado
            markBackendUnavailable(Operation.RELEASE, e);
        }
    }

    boolean isBackendAvailable() {
        return backendRetryAtMillis.get() == 0;
    }

    private Acquisition attemptAcquire(RateLimitedAction action, RateLimitSubject subject) {
        Objects.requireNonNull(action, "action must not be null");
        Objects.requireNonNull(subject, "subject must not be null");
        RateLimitProperties.Policy policy = properties.policyFor(action);
        if (!properties.isEnabled() || !policy.isEnabled()) {
            return Acquisition.granted(UNTRACKED);
        }

        String key = keyDeriver.derive(action, subject);
        if (shouldUseBackend()) {
            try {
                RateLimitStoreResult result = backendStore.acquire(key, policy.getLimit(), policy.getWindow());
                markBackendAvailable();
                if (result.isGranted()) {
                    metrics.recordDecision(action, true, Source.REDIS);
                    return Acquisition.granted(new StorePermit(key, result.member(), false));
                } else {
                    metrics.recordDecision(action, false, Source.REDIS);
                    return Acquisition.rejected(result.retryAfter());
                }
            } catch (RateLimitBackendException e) {
                markBackendUnavailable(Operation.ACQUIRE, e);
            }
        }
        return decideDegraded(action, key, policy);
    }

    private Acquisition decideDegraded(RateLimitedAction action, String key,
                                       RateLimitProperties.Policy policy) {
        return switch (properties.getBackend().getFailureMode()) {
            case LOCAL_FALLBACK -> {
                RateLimitStoreResult result = fallbackStore.acquire(key, policy.getLimit(), policy.getWindow());
                if (result.isGranted()) {
                    metrics.recordDecision(action, true, Source.LOCAL_FALLBACK);
                    yield Acquisition.granted(new StorePermit(key, result.member(), true));
                } else {
                    metrics.recordDecision(action, false, Source.LOCAL_FALLBACK);
                    yield Acquisition.rejected(result.retryAfter());
                }
            }
            case DENY -> {
                metrics.recordDecision(action, false, Source.FAILURE_POLICY);
                long fallbackSeconds = Math.max(1, policy.getWindow().toSeconds());
                yield Acquisition.rejected(Duration.ofSeconds(fallbackSeconds));
            }
            case ALLOW -> {
                metrics.recordDecision(action, true, Source.FAILURE_POLICY);
                yield Acquisition.granted(UNTRACKED);
            }
        };
    }

    private boolean shouldUseBackend() {
        long retryAt = backendRetryAtMillis.get();
        if (retryAt == 0) {
            return true;
        }
        long now = clock.millis();
        // Uma única requisição por intervalo reivindica o teste do Redis; as demais seguem em modo degradado
        return now >= retryAt && backendRetryAtMillis.compareAndSet(retryAt, now + retryIntervalMillis());
    }

    private void markBackendAvailable() {
        if (backendRetryAtMillis.getAndSet(0) != 0) {
            log.info("Rate limiting: Redis disponível novamente; modo degradado encerrado");
        }
    }

    private void markBackendUnavailable(Operation operation, RateLimitBackendException e) {
        metrics.recordBackendError(operation);
        long previous = backendRetryAtMillis.getAndSet(clock.millis() + retryIntervalMillis());
        if (previous == 0) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.warn("Rate limiting: Redis indisponível; modo degradado={} por {}ms antes de nova tentativa (erro={})",
                    properties.getBackend().getFailureMode(), retryIntervalMillis(), cause.getClass().getSimpleName());
        }
    }

    private long retryIntervalMillis() {
        return properties.getBackend().getRetryInterval().toMillis();
    }

    /** Tentativa registrada em um armazenamento; a chave é o HMAC, nunca o sujeito. */
    private record StorePermit(String key, String member, boolean fallback) implements RateLimitPermit {

        @Override
        public String toString() {
            return "RateLimitPermit[tracked]";
        }
    }

    private record Acquisition(RateLimitPermit permit, Duration retryAfter) {
        boolean isGranted() {
            return permit != null;
        }

        static Acquisition granted(RateLimitPermit permit) {
            return new Acquisition(permit, null);
        }

        static Acquisition rejected(Duration retryAfter) {
            return new Acquisition(null, retryAfter);
        }
    }
}
