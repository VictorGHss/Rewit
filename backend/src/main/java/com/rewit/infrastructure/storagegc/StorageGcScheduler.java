package com.rewit.infrastructure.storagegc;

import com.rewit.application.usecase.RunStorageGcCycleUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Job @Scheduled fino do GC de storage (Step 28.5): apenas dispara {@link RunStorageGcCycleUseCase}.
 * Nenhuma regra de reconciliação ou exclusão, nenhum repositório, nenhum SDK de storage.
 *
 * <p>fixedDelay evita sobreposição na mesma JVM; entre instâncias, a exclusividade vem do advisory lock
 * do PostgreSQL adquirido pelo próprio ciclo. Separado do Outbox: não gera nem consome mensagens.
 */
@Component
@ConditionalOnProperty(prefix = "rewit.storage-gc", name = "enabled", havingValue = "true")
public class StorageGcScheduler {

    private static final Logger log = LoggerFactory.getLogger(StorageGcScheduler.class);

    private final RunStorageGcCycleUseCase runStorageGcCycleUseCase;

    public StorageGcScheduler(RunStorageGcCycleUseCase runStorageGcCycleUseCase) {
        this.runStorageGcCycleUseCase =
                Objects.requireNonNull(runStorageGcCycleUseCase, "RunStorageGcCycleUseCase must not be null");
    }

    @Scheduled(
            fixedDelayString = "${rewit.storage-gc.interval-ms}",
            initialDelayString = "${rewit.storage-gc.initial-delay-ms:60000}"
    )
    public void runScheduledCycle() {
        try {
            runStorageGcCycleUseCase.runCycle();
        } catch (RuntimeException e) {
            log.error("Storage GC: falha inesperada ao disparar o ciclo; o próximo tick continua (erro={})",
                    e.getClass().getSimpleName());
        }
    }
}
