package com.rewit.infrastructure.outbox;

import com.rewit.application.usecase.PurgeCompletedOutboxUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Job @Scheduled de retenção do Outbox (Step 27.4, Partes J/N/T): remove em
 * lote mensagens COMPLETED mais antigas que a retenção configurada. O job é
 * desligável via {@code rewit.outbox.purge-enabled} e desligado por padrão nos
 * testes — nenhum DELETE por relógio disputa com cenários de teste.
 *
 * <p>fixedDelay garante que uma passada só inicia após a anterior terminar na
 * MESMA JVM. A remoção em si é atômica no PostgreSQL (DELETE com subconsulta
 * limitada), tornando execuções concorrentes entre instâncias seguras.
 *
 * <p>Cada execução registra métricas de quantidade removida e duração (Parte N,
 * via {@link OutboxMetrics#recordPurge}) — sem métricas por ID.
 */
@Component
@ConditionalOnProperty(prefix = "rewit.outbox", name = "purge-enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPurgeScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxPurgeScheduler.class);

    private final PurgeCompletedOutboxUseCase purgeCompletedOutboxUseCase;
    private final OutboxMetrics outboxMetrics;

    public OutboxPurgeScheduler(
            PurgeCompletedOutboxUseCase purgeCompletedOutboxUseCase,
            OutboxMetrics outboxMetrics
    ) {
        this.purgeCompletedOutboxUseCase =
                Objects.requireNonNull(purgeCompletedOutboxUseCase, "PurgeCompletedOutboxUseCase must not be null");
        this.outboxMetrics = Objects.requireNonNull(outboxMetrics, "OutboxMetrics must not be null");
    }

    @Scheduled(
            fixedDelayString = "${rewit.outbox.purge-interval-ms:3600000}",
            initialDelayString = "${rewit.outbox.purge-initial-delay-ms:60000}"
    )
    public void purgeExpiredCompletedMessages() {
        Instant start = Instant.now();
        try {
            int deleted = purgeCompletedOutboxUseCase.purgeExpired(start);
            outboxMetrics.recordPurge(deleted, Duration.between(start, Instant.now()));
            if (deleted > 0) {
                log.info("Outbox purge: {} mensagens COMPLETED antigas removidas nesta passada", deleted);
            }
        } catch (RuntimeException e) {
            log.error("Outbox purge: falha na passada de retenção; o próximo ciclo continua normalmente", e);
        }
    }
}
