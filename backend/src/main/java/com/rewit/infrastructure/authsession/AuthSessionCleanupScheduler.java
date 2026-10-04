package com.rewit.infrastructure.authsession;

import com.rewit.application.usecase.CleanupAuthSessionsUseCase;
import com.rewit.application.usecase.CleanupAuthSessionsUseCase.AuthSessionCleanupResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Job @Scheduled fino do cleanup de {@code auth_sessions} (Step 29.3): apenas dispara
 * {@link CleanupAuthSessionsUseCase} e registra métricas e um log agregado. Sem SQL, sem regra de
 * elegibilidade e sem lock próprio: lotes concorrentes entre instâncias são disjuntos por
 * {@code FOR UPDATE SKIP LOCKED} no repositório.
 *
 * <p>Falhas são registradas (classe do erro, sem dados de sessão) e o próximo tick tenta novamente.
 */
@Component
@ConditionalOnProperty(prefix = "rewit.auth-session-cleanup", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AuthSessionCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(AuthSessionCleanupScheduler.class);

    private final CleanupAuthSessionsUseCase cleanupAuthSessionsUseCase;
    private final AuthSessionCleanupMetrics metrics;

    public AuthSessionCleanupScheduler(CleanupAuthSessionsUseCase cleanupAuthSessionsUseCase,
                                       AuthSessionCleanupMetrics metrics) {
        this.cleanupAuthSessionsUseCase =
                Objects.requireNonNull(cleanupAuthSessionsUseCase, "CleanupAuthSessionsUseCase must not be null");
        this.metrics = Objects.requireNonNull(metrics, "AuthSessionCleanupMetrics must not be null");
    }

    @Scheduled(
            fixedDelayString = "${rewit.auth-session-cleanup.interval-ms:3600000}",
            initialDelayString = "${rewit.auth-session-cleanup.initial-delay-ms:60000}"
    )
    public void cleanupAuthSessions() {
        Instant start = Instant.now();
        log.debug("Auth session cleanup: passada iniciada");
        try {
            AuthSessionCleanupResult result = cleanupAuthSessionsUseCase.cleanup(start);
            Duration duration = Duration.between(start, Instant.now());
            metrics.recordCycle(result, duration);
            log.info("Auth session cleanup: metadados limpos={}, sessões removidas={}, limiteAtingido(metadados={}, purge={}), duracaoMs={}",
                    result.metadataCleared(), result.sessionsPurged(), result.metadataLimitReached(),
                    result.purgeLimitReached(), duration.toMillis());
        } catch (RuntimeException e) {
            metrics.recordFailure(Duration.between(start, Instant.now()));
            log.error("Auth session cleanup: falha na passada; o lote em curso sofreu rollback e o próximo ciclo tenta novamente (erro={})",
                    e.getClass().getSimpleName());
        }
    }
}
