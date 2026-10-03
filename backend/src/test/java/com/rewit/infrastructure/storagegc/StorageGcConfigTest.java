package com.rewit.infrastructure.storagegc;

import com.rewit.application.dto.storage.StorageGcDtos.StorageGcCycleReport;
import com.rewit.application.dto.storage.StorageQuarantineDtos.QuarantineResolution;
import com.rewit.application.dto.storage.StorageQuarantineDtos.QuarantinedStorageObject;
import com.rewit.application.dto.storage.StorageReconciliationDtos.StoredObjectPage;
import com.rewit.application.port.ObjectStorageDeletionPort;
import com.rewit.application.port.ObjectStorageListingPort;
import com.rewit.application.port.ReviewMediaRepository;
import com.rewit.application.port.StorageGcExecutionLock;
import com.rewit.application.port.StorageQuarantineRepository;
import com.rewit.application.usecase.PurgeConfirmedOrphanStorageObjectUseCase;
import com.rewit.application.usecase.RunStorageGcCycleUseCase;
import com.rewit.domain.enums.StorageQuarantineStatus;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@DisplayName("Testes de Configuração: storage GC (Step 28.5)")
class StorageGcConfigTest {

    private static final String[] VALID = {
            "rewit.storage-gc.interval-ms=600000",
            "rewit.storage-gc.page-size=100",
            "rewit.storage-gc.max-pages=10",
            "rewit.storage-gc.max-candidates=50",
            "rewit.storage-gc.max-deletes=20",
            "rewit.storage-gc.grace-period=PT24H",
            "rewit.storage-gc.max-duration=PT5M"
    };

    private final ObjectStorageDeletionPort deletionPort = mock(ObjectStorageDeletionPort.class);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfig.class, StorageGcConfig.class, StorageGcScheduler.class,
                    StorageGcDisabledNotice.class)
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withBean(DataSource.class, () -> mock(DataSource.class))
            .withBean(ObjectStorageListingPort.class, () -> mock(ObjectStorageListingPort.class))
            .withBean(ReviewMediaRepository.class, () -> mock(ReviewMediaRepository.class))
            .withBean(StorageQuarantineRepository.class, () -> mock(StorageQuarantineRepository.class))
            .withBean(ObjectStorageDeletionPort.class, () -> deletionPort);

    @Configuration
    @EnableConfigurationProperties(StorageGcProperties.class)
    static class PropertiesConfig {
    }

    @Test
    @DisplayName("Sem configuração, o GC fica desligado e nenhum componente dele é criado")
    void disabledByDefault() {
        runner.run(context -> {
            assertNull(context.getStartupFailure());
            assertTrue(context.getBeansOfType(RunStorageGcCycleUseCase.class).isEmpty());
            assertTrue(context.getBeansOfType(StorageGcScheduler.class).isEmpty());
            assertTrue(context.getBeansOfType(StorageGcExecutionLock.class).isEmpty());
            assertEquals(1, context.getBeansOfType(StorageGcDisabledNotice.class).size());
        });
    }

    @Test
    @DisplayName("enabled=true sozinho não basta: o boot falha listando a configuração obrigatória")
    void enabledAloneFailsFast() {
        runner.withPropertyValues("rewit.storage-gc.enabled=true").run(context -> {
            Throwable failure = context.getStartupFailure();
            assertNotNull(failure);
            String message = rootMessage(failure);
            for (String required : List.of("dry-run", "interval-ms", "page-size", "max-pages", "max-candidates",
                    "max-deletes", "grace-period", "max-duration")) {
                assertTrue(message.contains(required), required + " em: " + message);
            }
        });
    }

    @Test
    @DisplayName("Dry-run habilitado monta o orquestrador sem exclusão e sem tocar a porta de exclusão")
    void dryRunWiringHasNoDeletion() {
        runner.withPropertyValues(properties(true)).run(context -> {
            assertNull(context.getStartupFailure());
            RunStorageGcCycleUseCase gc = context.getBean(RunStorageGcCycleUseCase.class);
            assertTrue(gc.isDryRun());
            assertEquals(1, context.getBeansOfType(StorageGcScheduler.class).size());
            assertTrue(context.getBeansOfType(PurgeConfirmedOrphanStorageObjectUseCase.class).isEmpty());
            verifyNoInteractions(deletionPort);
        });
    }

    @Test
    @DisplayName("Modo destrutivo só com dry-run=false explícito")
    void destructiveRequiresExplicitFalse() {
        runner.withPropertyValues(properties(false)).run(context -> {
            assertNull(context.getStartupFailure());
            assertFalse(context.getBean(RunStorageGcCycleUseCase.class).isDryRun());
        });
    }

    @Test
    @DisplayName("Limites e durações inválidos falham no boot")
    void invalidLimitsFail() {
        for (String invalid : List.of("rewit.storage-gc.page-size=0", "rewit.storage-gc.page-size=1001",
                "rewit.storage-gc.max-pages=-1", "rewit.storage-gc.max-deletes=10001",
                "rewit.storage-gc.grace-period=PT0S", "rewit.storage-gc.max-duration=-PT1M",
                "rewit.storage-gc.max-consecutive-failures=0")) {
            runner.withPropertyValues(properties(true)).withPropertyValues(invalid)
                    .run(context -> assertNotNull(context.getStartupFailure(), invalid));
        }
    }

    @Test
    @DisplayName("O grace period da política vem da configuração e é a única autoridade temporal")
    void gracePeriodComesFromConfiguration() {
        Instant firstObserved = Instant.now().minus(Duration.ofHours(2));
        assertEquals(1, cycleWithGrace(Duration.ofHours(1), firstObserved).confirmedOrphans());
        assertEquals(1, cycleWithGrace(Duration.ofHours(3), firstObserved).gracePending());
    }

    private StorageGcCycleReport cycleWithGrace(Duration grace, Instant firstObserved) {
        String key = "reviews/" + UUID.randomUUID() + "/" + UUID.randomUUID() + "/image.jpg";
        QuarantinedStorageObject entry = new QuarantinedStorageObject(key, StorageQuarantineStatus.OBSERVED,
                firstObserved, firstObserved, null, null);
        StorageQuarantineRepository quarantine = mock(StorageQuarantineRepository.class);
        when(quarantine.findByStatus(eq(StorageQuarantineStatus.OBSERVED), anyInt())).thenReturn(List.of(entry));
        when(quarantine.findByStatus(eq(StorageQuarantineStatus.CONFIRMED_ORPHAN), anyInt())).thenReturn(List.of());
        when(quarantine.findByObjectKey(key)).thenReturn(Optional.of(entry));
        when(quarantine.resolveUnderCreationLock(eq(key), any(), eq(firstObserved), any()))
                .thenReturn(new QuarantineResolution(QuarantineResolution.Kind.CONFIRMED_ORPHAN, null));
        ObjectStorageListingPort listing = mock(ObjectStorageListingPort.class);
        when(listing.listObjects(any(), any(), anyInt())).thenReturn(new StoredObjectPage(List.of(), null));

        StorageGcProperties properties = new StorageGcProperties();
        properties.setEnabled(true);
        properties.setDryRun(true);
        properties.setIntervalMs(600000L);
        properties.setPageSize(10);
        properties.setMaxPages(1);
        properties.setMaxCandidates(10);
        properties.setMaxDeletes(10);
        properties.setGracePeriod(grace);
        properties.setMaxDuration(Duration.ofMinutes(5));

        RunStorageGcCycleUseCase gc = new StorageGcConfig().runStorageGcCycleUseCase(properties, listing,
                mock(ReviewMediaRepository.class), quarantine, new DirectLock(),
                new StorageGcMetrics(new SimpleMeterRegistry(), quarantine), new ForbiddenDeletionProvider());
        return gc.runCycle();
    }

    private static String[] properties(boolean dryRun) {
        String[] all = new String[VALID.length + 2];
        System.arraycopy(VALID, 0, all, 0, VALID.length);
        all[VALID.length] = "rewit.storage-gc.enabled=true";
        all[VALID.length + 1] = "rewit.storage-gc.dry-run=" + dryRun;
        return all;
    }

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return String.valueOf(current.getMessage());
    }

    /** Em dry-run a porta de exclusão nunca pode ser obtida. */
    private static final class ForbiddenDeletionProvider implements ObjectProvider<ObjectStorageDeletionPort> {

        @Override
        public ObjectStorageDeletionPort getObject() {
            throw new AssertionError("dry-run não pode obter a porta de exclusão");
        }

        @Override
        public ObjectStorageDeletionPort getObject(Object... args) {
            return getObject();
        }

        @Override
        public ObjectStorageDeletionPort getIfAvailable() {
            return getObject();
        }

        @Override
        public ObjectStorageDeletionPort getIfUnique() {
            return getObject();
        }
    }

    private static final class DirectLock implements StorageGcExecutionLock {
        @Override
        public <T> Optional<T> runExclusively(Supplier<T> cycle) {
            return Optional.of(cycle.get());
        }
    }
}
