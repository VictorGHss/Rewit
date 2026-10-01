package com.rewit.infrastructure.outbox;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

import org.springframework.stereotype.Component;

import com.rewit.application.port.OutboxRepository;
import com.rewit.application.usecase.ProcessOutboxBatchUseCase.ProcessOutboxBatchResult;
import com.rewit.domain.enums.OutboxStatus;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Métricas Micrometer do Outbox (Step 27.4, Partes B/C/N), única camada de
 * métricas do mecanismo — nenhuma outra é criada em paralelo.
 *
 * <p>Gauges (Partes B/D/E): consultam o PostgreSQL a cada scrape — o banco é a
 * fonte de verdade e nada de memória assume esse papel. Os gauges são
 * vinculados a beans singleton do Spring (o repositório e este componente),
 * garantindo que as referências fracas do Micrometer permaneçam alcançáveis.
 *
 * <p>Counters (Parte C): derivados exclusivamente do resultado estruturado do
 * ciclo do dispatcher ({@link ProcessOutboxBatchResult}), gravados pelo poller
 * — sem duplicação de contagem e sem tags (a cardinalidade de IDs, tipos,
 * mensagens de erro ou payloads é proibida pela Parte C).
 */
@Component
public class OutboxMetrics {

    public static final String GAUGE_PENDING = "rewit.outbox.pending";
    public static final String GAUGE_FAILED = "rewit.outbox.failed";
    public static final String GAUGE_OLDEST_PENDING_AGE = "rewit.outbox.oldest_pending_age";

    public static final String COUNTER_LEASES_RECLAIMED = "rewit.outbox.leases.reclaimed";
    public static final String COUNTER_MESSAGES_CLAIMED = "rewit.outbox.messages.claimed";
    public static final String COUNTER_MESSAGES_COMPLETED = "rewit.outbox.messages.completed";
    public static final String COUNTER_MESSAGES_RETRIED = "rewit.outbox.messages.retried";
    public static final String COUNTER_MESSAGES_FAILED = "rewit.outbox.messages.failed";
    public static final String COUNTER_MESSAGES_LOST_OWNERSHIP = "rewit.outbox.messages.lost_ownership";
    public static final String COUNTER_FINALIZE_ERRORS = "rewit.outbox.finalize.errors";
    public static final String COUNTER_PURGE_DELETED = "rewit.outbox.purge.deleted";
    public static final String TIMER_PURGE_DURATION = "rewit.outbox.purge.duration";

    private final MeterRegistry meterRegistry;

    public OutboxMetrics(MeterRegistry meterRegistry, OutboxRepository outboxRepository) {
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "MeterRegistry must not be null");
        Objects.requireNonNull(outboxRepository, "OutboxRepository must not be null");

        Gauge.builder(GAUGE_PENDING, outboxRepository, repo -> repo.countByStatus(OutboxStatus.PENDING))
                .description("Mensagens do Outbox aguardando processamento (fonte: PostgreSQL)")
                .register(meterRegistry);
        Gauge.builder(GAUGE_FAILED, outboxRepository, repo -> repo.countByStatus(OutboxStatus.FAILED))
                .description("Mensagens do Outbox em falha terminal (fonte: PostgreSQL)")
                .register(meterRegistry);
        Gauge.builder(GAUGE_OLDEST_PENDING_AGE, outboxRepository, OutboxMetrics::oldestPendingAgeSeconds)
                .description("Idade em segundos da mensagem PENDING mais antiga; 0 quando não há PENDING")
                .register(meterRegistry);
    }

    /**
     * Registra os contadores de um ciclo do dispatcher (Parte C). Nenhum dado
     * individual da mensagem é usado como tag ou dimensão.
     */
    public void recordCycle(ProcessOutboxBatchResult result) {
        Objects.requireNonNull(result, "result must not be null");
        counter(COUNTER_LEASES_RECLAIMED).increment(result.reclaimedCount());
        counter(COUNTER_MESSAGES_CLAIMED).increment(result.claimedCount());
        counter(COUNTER_MESSAGES_COMPLETED).increment(result.completedCount());
        counter(COUNTER_MESSAGES_RETRIED).increment(result.retriedCount());
        counter(COUNTER_MESSAGES_FAILED).increment(result.failedCount());
        counter(COUNTER_MESSAGES_LOST_OWNERSHIP).increment(result.lostOwnershipCount());
        counter(COUNTER_FINALIZE_ERRORS).increment(result.finalizeErrorCount());
    }

    /**
     * Registra o resultado de uma execução do purge (Parte N): quantidade
     * removida e duração da execução — sem métricas por ID.
     */
    public void recordPurge(int deletedCount, Duration executionDuration) {
        Objects.requireNonNull(executionDuration, "executionDuration must not be null");
        counter(COUNTER_PURGE_DELETED).increment(deletedCount);
        if (!executionDuration.isNegative()) {
            Timer.builder(TIMER_PURGE_DURATION)
                    .description("Duração das execuções do purge de mensagens COMPLETED antigas")
                    .register(meterRegistry)
                    .record(executionDuration);
        }
    }

    private Counter counter(String name) {
        return meterRegistry.counter(name);
    }

    private static double oldestPendingAgeSeconds(OutboxRepository repository) {
        Instant oldest = repository.oldestPendingCreatedAt().orElse(null);
        if (oldest == null) {
            return 0d;
        }
        long ageSeconds = Duration.between(oldest, Instant.now()).getSeconds();
        return Math.max(0d, ageSeconds);
    }
}
