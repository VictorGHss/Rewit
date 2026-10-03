package com.rewit.infrastructure.storagegc;

import com.rewit.application.dto.storage.StorageGcDtos.StorageGcLimits;
import com.rewit.application.port.ObjectStorageDeletionPort;
import com.rewit.application.port.ObjectStorageListingPort;
import com.rewit.application.port.ReviewMediaRepository;
import com.rewit.application.port.StorageGcExecutionLock;
import com.rewit.application.port.StorageQuarantineRepository;
import com.rewit.application.storage.FixedStorageQuarantineGracePolicy;
import com.rewit.application.usecase.PurgeConfirmedOrphanStorageObjectUseCase;
import com.rewit.application.usecase.RecheckQuarantinedStorageObjectUseCase;
import com.rewit.application.usecase.ReconcileReviewMediaStorageUseCase;
import com.rewit.application.usecase.RunStorageGcCycleUseCase;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.time.Clock;

/**
 * Montagem do GC de storage (Step 28.5). Só existe com {@code rewit.storage-gc.enabled=true}: desligado,
 * nenhum componente do GC é criado.
 *
 * <p>A configuração é validada antes de qualquer montagem. Em dry-run, o use case de exclusão e a porta de
 * exclusão do storage não são sequer obtidos: o orquestrador é construído sem eles.
 */
@Configuration
@ConditionalOnProperty(prefix = "rewit.storage-gc", name = "enabled", havingValue = "true")
public class StorageGcConfig {

    @Bean
    public StorageGcMetrics storageGcMetrics(MeterRegistry meterRegistry, StorageQuarantineRepository quarantineRepository) {
        return new StorageGcMetrics(meterRegistry, quarantineRepository);
    }

    @Bean
    public PostgresAdvisoryStorageGcExecutionLock storageGcExecutionLock(DataSource dataSource) {
        return new PostgresAdvisoryStorageGcExecutionLock(dataSource);
    }

    @Bean
    public RunStorageGcCycleUseCase runStorageGcCycleUseCase(
            StorageGcProperties properties,
            ObjectStorageListingPort listingPort,
            ReviewMediaRepository reviewMediaRepository,
            StorageQuarantineRepository quarantineRepository,
            StorageGcExecutionLock executionLock,
            StorageGcMetrics metrics,
            ObjectProvider<ObjectStorageDeletionPort> deletionPort
    ) {
        properties.validateForExecution();

        // Uma página por chamada: o orquestrador aplica max-pages e o timeout entre páginas
        ReconcileReviewMediaStorageUseCase reconcile = new ReconcileReviewMediaStorageUseCase(
                listingPort, reviewMediaRepository, quarantineRepository, properties.getPageSize(), 1);
        RecheckQuarantinedStorageObjectUseCase recheck = new RecheckQuarantinedStorageObjectUseCase(
                quarantineRepository, new FixedStorageQuarantineGracePolicy(properties.getGracePeriod()));
        StorageGcLimits limits = new StorageGcLimits(
                properties.getMaxPages(),
                properties.getMaxCandidates(),
                properties.getMaxDeletes(),
                properties.getMaxDuration(),
                properties.getMaxConsecutiveFailures());
        Clock clock = Clock.systemUTC();

        if (properties.getDryRun()) {
            return RunStorageGcCycleUseCase.dryRun(executionLock, reconcile, quarantineRepository, recheck,
                    metrics, clock, limits);
        }
        PurgeConfirmedOrphanStorageObjectUseCase purge = new PurgeConfirmedOrphanStorageObjectUseCase(
                quarantineRepository, deletionPort.getObject());
        return RunStorageGcCycleUseCase.destructive(executionLock, reconcile, quarantineRepository, recheck, purge,
                metrics, clock, limits);
    }
}
