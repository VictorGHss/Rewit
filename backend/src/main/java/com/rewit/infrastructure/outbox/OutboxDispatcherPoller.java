package com.rewit.infrastructure.outbox;

import com.rewit.application.usecase.ProcessOutboxBatchUseCase;
import com.rewit.application.usecase.ProcessOutboxBatchUseCase.ProcessOutboxBatchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Poller @Scheduled fino do dispatcher do Outbox (Step 27.2, Partes H/J/R):
 * apenas invoca {@link ProcessOutboxBatchUseCase} — nenhuma regra de negócio,
 * nenhum acesso direto a repositório. O use case também roda sem ele (Parte H).
 *
 * <p>fixedDelay garante que um novo ciclo só inicia após o anterior terminar na
 * MESMA JVM (sem sobreposição, sem flag/singleton/synchronized). Em múltiplas
 * instâncias NÃO há coordenação por aplicação: a exclusividade vem exclusivamente
 * do PostgreSQL (SELECT FOR UPDATE SKIP LOCKED no claim e owner-checked updates
 * na finalização), tornando a execução concorrente entre instâncias segura.
 *
 * <p>Falhas do ciclo são logadas e absorvidas (Parte R): o próximo tick continua
 * normalmente — a exceção nunca é engolida silenciosamente nem derruba o scheduler.
 *
 * <p>Step 27.4 (Parte C): o resultado estruturado de cada ciclo é gravado como
 * counters Micrometer por {@link OutboxMetrics#recordCycle} — o poller é o único
 * ponto de gravação, sem dupla contagem.
 */
@Component
@ConditionalOnProperty(prefix = "rewit.outbox", name = "poller-enabled", havingValue = "true", matchIfMissing = true)
public class OutboxDispatcherPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcherPoller.class);

    private final ProcessOutboxBatchUseCase processOutboxBatchUseCase;
    private final OutboxMetrics outboxMetrics;

    public OutboxDispatcherPoller(
            ProcessOutboxBatchUseCase processOutboxBatchUseCase,
            OutboxMetrics outboxMetrics
    ) {
        this.processOutboxBatchUseCase =
                Objects.requireNonNull(processOutboxBatchUseCase, "ProcessOutboxBatchUseCase must not be null");
        this.outboxMetrics = Objects.requireNonNull(outboxMetrics, "OutboxMetrics must not be null");
    }

    @Scheduled(
            fixedDelayString = "${rewit.outbox.poll-interval-ms:5000}",
            initialDelayString = "${rewit.outbox.initial-delay-ms:15000}"
    )
    public void dispatchPendingMessages() {
        try {
            ProcessOutboxBatchResult result = processOutboxBatchUseCase.processPendingBatch();
            outboxMetrics.recordCycle(result);
            if (result.claimedCount() > 0) {
                log.info(
                        "Outbox poller: lote processado — {} concluídas, {} reagendadas, {} FAILED, "
                                + "{} finalizações descartadas (lease migrou), {} erros de finalização",
                        result.completedCount(),
                        result.retriedCount(),
                        result.failedCount(),
                        result.lostOwnershipCount(),
                        result.finalizeErrorCount());
            }
        } catch (RuntimeException e) {
            log.error("Outbox poller: falha no ciclo de processamento; o próximo tick continua normalmente", e);
        }
    }
}
