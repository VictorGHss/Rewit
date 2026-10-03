package com.rewit.application.usecase;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.rewit.application.dto.storage.ObjectDeletionResult;
import com.rewit.application.dto.storage.StorageGcDtos.StorageGcCycleReport;
import com.rewit.application.dto.storage.StorageGcDtos.StorageGcLimits;
import com.rewit.application.dto.storage.StorageGcDtos.StorageGcRunStatus;
import com.rewit.application.dto.storage.StorageReconciliationDtos.OrphanCandidate;
import com.rewit.application.dto.storage.StorageReconciliationDtos.ReviewMediaReference;
import com.rewit.application.dto.storage.StorageReconciliationDtos.StoredObject;
import com.rewit.application.dto.storage.StorageReconciliationDtos.StoredObjectPage;
import com.rewit.application.port.ObjectStorageDeletionPort;
import com.rewit.application.port.ObjectStorageListingPort;
import com.rewit.application.port.ReviewMediaRepository;
import com.rewit.application.port.StorageGcExecutionLock;
import com.rewit.application.storage.FixedStorageQuarantineGracePolicy;
import com.rewit.domain.enums.ReviewMediaStatus;
import com.rewit.domain.enums.StorageQuarantineStatus;
import com.rewit.domain.model.ReviewMedia;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Testes Unitários: RunStorageGcCycleUseCase (Step 28.5)")
class RunStorageGcCycleUseCaseTest {

    private static final Instant T0 = Instant.parse("2026-10-03T03:00:00Z");
    private static final Duration GRACE = Duration.ofMinutes(30);

    private final InMemoryStorageQuarantineRepository quarantine = new InMemoryStorageQuarantineRepository();
    private final InMemoryObjectStore store = new InMemoryObjectStore();
    private final MutableClock clock = new MutableClock(T0);
    private final List<StorageGcCycleReport> observed = new ArrayList<>();
    private StorageGcExecutionLock lock = new FreeLock();

    // ---------- dry-run ----------

    @Test
    @DisplayName("Dry-run lista, observa e recheca, mas nunca chama o storage delete")
    void dryRunNeverDeletes() {
        String a = store.add(managedKey());
        String b = store.add(managedKey());
        RunStorageGcCycleUseCase gc = dryRun(limits(10, 10, 10, 10));

        StorageGcCycleReport first = gc.runCycle();
        clock.set(T0.plus(GRACE));
        StorageGcCycleReport second = gc.runCycle();

        assertEquals(2, first.candidatesObserved());
        assertEquals(2, first.gracePending());
        assertEquals(2, second.confirmedOrphans());
        assertEquals(2, second.wouldDelete());
        assertEquals(0, second.deletesAttempted());
        assertEquals(0, second.deleted());
        assertTrue(second.dryRun());
        assertEquals(StorageGcRunStatus.COMPLETED, second.status());
        assertTrue(store.deleteCalls.isEmpty(), "dry-run não pode chamar o storage delete");
        assertTrue(store.objects.keySet().containsAll(List.of(a, b)));
        assertEquals(StorageQuarantineStatus.CONFIRMED_ORPHAN, quarantine.entries.get(a).status());
    }

    @Test
    @DisplayName("A instância dry-run não possui o use case de exclusão, e sua fábrica não aceita um")
    void dryRunHasNoDeletionCapabilityByConstruction() throws Exception {
        assertTrue(dryRun(limits(1, 1, 1, 1)).isDryRun());
        assertFalse(destructive(limits(1, 1, 1, 1)).isDryRun());

        Method factory = Arrays.stream(RunStorageGcCycleUseCase.class.getMethods())
                .filter(method -> method.getName().equals("dryRun")).findFirst().orElseThrow();
        List<Class<?>> parameters = Arrays.asList(factory.getParameterTypes());
        assertFalse(parameters.contains(PurgeConfirmedOrphanStorageObjectUseCase.class));
        assertFalse(parameters.contains(ObjectStorageDeletionPort.class));
    }

    // ---------- ciclo destrutivo ----------

    @Test
    @DisplayName("Ciclo destrutivo: observa, respeita o grace period, recheca e só então exclui; referência protege")
    void destructiveCycleDeletesOnlyConfirmedOrphans() {
        String orphan = store.add(managedKey());
        String referenced = store.add(managedKey());
        quarantine.references.put(referenced, ReviewMediaStatus.ACTIVE);
        RunStorageGcCycleUseCase gc = destructive(limits(10, 10, 10, 10));

        StorageGcCycleReport first = gc.runCycle();
        assertEquals(1, first.candidatesObserved());
        assertEquals(1, first.gracePending());
        assertTrue(store.deleteCalls.isEmpty(), "dentro do grace period nada é excluído");

        clock.set(T0.plus(GRACE));
        StorageGcCycleReport second = gc.runCycle();

        assertEquals(1, second.confirmedOrphans());
        assertEquals(1, second.deletesAttempted());
        assertEquals(1, second.deleted());
        assertEquals(List.of(orphan), store.deleteCalls);
        assertTrue(store.objects.containsKey(referenced));
        assertFalse(quarantine.entries.containsKey(orphan));

        StorageGcCycleReport third = gc.runCycle();
        assertEquals(0, third.deletesAttempted(), "nova execução é idempotente");
        assertEquals(1, store.deleteCalls.size());
    }

    @Test
    @DisplayName("Objeto já ausente na exclusão conta como sucesso idempotente")
    void alreadyAbsentIsCounted() {
        String key = managedKey();
        confirm(key);

        StorageGcCycleReport report = destructive(limits(10, 10, 10, 10)).runCycle();

        assertEquals(1, report.alreadyAbsent());
        assertEquals(0, report.deleted());
        assertFalse(quarantine.entries.containsKey(key));
    }

    // ---------- limites ----------

    @Test
    @DisplayName("max-pages encerra o ciclo como PARTIAL, e o próximo continua do marcador sem recarregar o início")
    void maxPagesIsPartialAndResumable() {
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            keys.add(store.add(managedKey()));
        }
        keys.sort(null);
        RunStorageGcCycleUseCase gc = dryRun(limits(1, 10, 10, 10));

        StorageGcCycleReport first = gc.runCycle();
        gc.runCycle();
        StorageGcCycleReport third = gc.runCycle();
        gc.runCycle();

        assertEquals(StorageGcRunStatus.PARTIAL, first.status());
        assertFalse(first.listingComplete());
        assertEquals(1, first.pagesScanned());
        assertTrue(third.listingComplete());
        assertEquals(Arrays.asList(null, keys.get(1), keys.get(3), null), store.requestedMarkers);
        assertTrue(store.requestedPageSizes.stream().allMatch(size -> size == 2), "page-size respeitado");
    }

    @Test
    @DisplayName("Nenhuma página além de max-pages é lida no ciclo")
    void noPageBeyondMaxPages() {
        for (int i = 0; i < 9; i++) {
            store.add(managedKey());
        }

        StorageGcCycleReport report = dryRun(limits(2, 10, 10, 10)).runCycle();

        assertEquals(2, store.requestedMarkers.size());
        assertEquals(4, report.objectsListed());
    }

    @Test
    @DisplayName("max-candidates limita as rechecagens do ciclo")
    void maxCandidatesLimitsRechecks() {
        for (int i = 0; i < 5; i++) {
            observeWithoutObject(managedKey(), T0.minus(GRACE));
        }

        StorageGcCycleReport report = dryRun(limits(10, 2, 10, 10)).runCycle();

        assertEquals(2, report.rechecks());
        assertEquals(2, report.confirmedOrphans());
        assertEquals(StorageGcRunStatus.PARTIAL, report.status());
        assertEquals(3, quarantine.countByStatus(StorageQuarantineStatus.OBSERVED));
    }

    @Test
    @DisplayName("max-deletes limita as exclusões do ciclo")
    void maxDeletesLimitsRemovals() {
        for (int i = 0; i < 4; i++) {
            String key = store.add(managedKey());
            confirm(key);
        }

        StorageGcCycleReport report = destructive(limits(10, 10, 2, 10)).runCycle();

        assertEquals(2, report.deletesAttempted());
        assertEquals(2, store.deleteCalls.size());
        assertEquals(2, quarantine.countByStatus(StorageQuarantineStatus.CONFIRMED_ORPHAN));
        assertEquals(StorageGcRunStatus.PARTIAL, report.status());
    }

    // ---------- falhas ----------

    @Test
    @DisplayName("Storage indisponível na listagem: ciclo FAILED, sem rechecagem nem exclusão")
    void storageUnavailableAbortsCycle() {
        String key = store.add(managedKey());
        confirm(key);
        store.listingFailure = new RuntimeException("storage indisponível");
        observeWithoutObject(managedKey(), T0.minus(GRACE));

        StorageGcCycleReport report = destructive(limits(10, 10, 10, 10)).runCycle();

        assertEquals(StorageGcRunStatus.FAILED, report.status());
        assertEquals(0, report.rechecks());
        assertTrue(store.deleteCalls.isEmpty());
        assertEquals(StorageQuarantineStatus.CONFIRMED_ORPHAN, quarantine.entries.get(key).status());
    }

    @Test
    @DisplayName("Banco indisponível: ciclo FAILED, sem exclusão, quarentena preservada")
    void databaseUnavailableAbortsCycle() {
        String key = store.add(managedKey());
        confirm(key);
        quarantine.findByStatusFailure = new IllegalStateException("conexão recusada");

        StorageGcCycleReport report = destructive(limits(10, 10, 10, 10)).runCycle();

        assertEquals(StorageGcRunStatus.FAILED, report.status());
        assertTrue(store.deleteCalls.isEmpty());
        assertTrue(quarantine.entries.containsKey(key));
        assertEquals(1, observed.size(), "falha também é reportada ao observador");
    }

    @Test
    @DisplayName("Timeout: o ciclo para entre etapas com status TIMED_OUT")
    void timeoutStopsCycle() {
        for (int i = 0; i < 5; i++) {
            observeWithoutObject(managedKey(), T0.minus(GRACE));
        }
        clock.autoAdvance = Duration.ofMinutes(1);

        StorageGcCycleReport report = dryRun(new StorageGcLimits(10, 10, 10, Duration.ofMinutes(4), 3)).runCycle();

        assertEquals(StorageGcRunStatus.TIMED_OUT, report.status());
        assertTrue(report.rechecks() < 5);
    }

    @Test
    @DisplayName("Erro isolado de um candidato não interrompe os demais nem altera sua quarentena")
    void isolatedCandidateErrorDoesNotStopOthers() {
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            keys.add(store.add(managedKey()));
            confirm(keys.get(i));
        }
        String failing = quarantine.findByStatus(StorageQuarantineStatus.CONFIRMED_ORPHAN, 10).get(1).objectKey();
        store.throwOn = failing;

        StorageGcCycleReport report = destructive(limits(10, 10, 10, 10)).runCycle();

        assertEquals(1, report.candidateErrors());
        assertEquals(2, report.deleted());
        assertEquals(StorageGcRunStatus.COMPLETED, report.status());
        assertEquals(StorageQuarantineStatus.CONFIRMED_ORPHAN, quarantine.entries.get(failing).status());
    }

    @Test
    @DisplayName("Falhas transitórias consecutivas abortam o ciclo; a quarentena permanece para retry")
    void consecutiveTransientFailuresAbort() {
        for (int i = 0; i < 4; i++) {
            confirm(store.add(managedKey()));
        }
        store.forcedResult = ObjectDeletionResult.TRANSIENT_FAILURE;

        StorageGcCycleReport report = destructive(new StorageGcLimits(10, 10, 10, Duration.ofHours(1), 2)).runCycle();

        assertEquals(StorageGcRunStatus.ABORTED, report.status());
        assertEquals(2, report.retryableFailures());
        assertEquals(2, store.deleteCalls.size());
        assertEquals(4, quarantine.countByStatus(StorageQuarantineStatus.CONFIRMED_ORPHAN));
    }

    @Test
    @DisplayName("Falha permanente interrompe as exclusões do ciclo imediatamente")
    void permanentFailureAbortsDeletes() {
        for (int i = 0; i < 3; i++) {
            confirm(store.add(managedKey()));
        }
        store.forcedResult = ObjectDeletionResult.PERMANENT_FAILURE;

        StorageGcCycleReport report = destructive(limits(10, 10, 10, 10)).runCycle();

        assertEquals(StorageGcRunStatus.ABORTED, report.status());
        assertEquals(1, report.permanentFailures());
        assertEquals(1, store.deleteCalls.size());
        assertEquals(3, quarantine.countByStatus(StorageQuarantineStatus.CONFIRMED_ORPHAN));
    }

    // ---------- lock ----------

    @Test
    @DisplayName("Lock global ocupado: ciclo ignorado sem listar nem excluir")
    void skippedWhenLockIsHeld() {
        confirm(store.add(managedKey()));
        lock = new BusyLock();

        StorageGcCycleReport report = destructive(limits(10, 10, 10, 10)).runCycle();

        assertEquals(StorageGcRunStatus.SKIPPED_LOCKED, report.status());
        assertTrue(store.requestedMarkers.isEmpty());
        assertTrue(store.deleteCalls.isEmpty());
        assertEquals(List.of(report), observed);
    }

    @Test
    @DisplayName("Falha ao adquirir o lock: ciclo FAILED, sem trabalho")
    void lockFailureIsReported() {
        lock = new FailingLock();

        StorageGcCycleReport report = destructive(limits(10, 10, 10, 10)).runCycle();

        assertEquals(StorageGcRunStatus.FAILED, report.status());
        assertTrue(store.requestedMarkers.isEmpty());
    }

    // ---------- logs ----------

    @Test
    @DisplayName("Logs não contêm object keys, e o dry-run nunca é descrito como remoção")
    void logsAreSanitizedAndDistinguishDryRun() {
        String key = store.add(managedKey());
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        Logger rewit = (Logger) LoggerFactory.getLogger("com.rewit");
        Level previous = rewit.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        rewit.setLevel(Level.DEBUG);
        try {
            RunStorageGcCycleUseCase gc = dryRun(limits(10, 10, 10, 10));
            gc.runCycle();
            clock.set(T0.plus(GRACE));
            gc.runCycle();
            destructive(limits(10, 10, 10, 10)).runCycle();
        } finally {
            root.detachAppender(appender);
            rewit.setLevel(previous);
        }

        List<String> messages = appender.list.stream().map(event -> event.getFormattedMessage()).toList();
        assertFalse(messages.isEmpty());
        String[] keyParts = key.split("/");
        assertTrue(messages.stream().noneMatch(message -> message.contains(key)
                        || message.contains(keyParts[1]) || message.contains(keyParts[2])),
                "nenhum log pode conter a object key nem seus identificadores");
        List<String> dryRunSummaries = messages.stream()
                .filter(message -> message.contains("ciclo encerrado") && message.contains("modo=DRY_RUN")).toList();
        assertEquals(2, dryRunSummaries.size());
        assertTrue(dryRunSummaries.stream().allMatch(message -> message.contains("nada foi removido")
                && !message.contains("removidos=")));
        assertTrue(messages.stream().anyMatch(message -> message.contains("modo=DESTRUTIVO") && message.contains("removidos=1")));
    }

    // ---------- montagem ----------

    private RunStorageGcCycleUseCase dryRun(StorageGcLimits limits) {
        return RunStorageGcCycleUseCase.dryRun(lock, reconcile(), quarantine, recheck(), report -> observed.add(report), clock, limits);
    }

    private RunStorageGcCycleUseCase destructive(StorageGcLimits limits) {
        return RunStorageGcCycleUseCase.destructive(lock, reconcile(), quarantine, recheck(),
                new PurgeConfirmedOrphanStorageObjectUseCase(quarantine, store), report -> observed.add(report), clock, limits);
    }

    private ReconcileReviewMediaStorageUseCase reconcile() {
        return new ReconcileReviewMediaStorageUseCase(store, new QuarantineBackedReferences(quarantine), quarantine, 2, 1);
    }

    private RecheckQuarantinedStorageObjectUseCase recheck() {
        return new RecheckQuarantinedStorageObjectUseCase(quarantine, new FixedStorageQuarantineGracePolicy(GRACE));
    }

    private static StorageGcLimits limits(int maxPages, int maxCandidates, int maxDeletes, int maxConsecutiveFailures) {
        return new StorageGcLimits(maxPages, maxCandidates, maxDeletes, Duration.ofHours(1), maxConsecutiveFailures);
    }

    private static String managedKey() {
        return "reviews/" + UUID.randomUUID() + "/" + UUID.randomUUID() + "/image.jpg";
    }

    private void observeWithoutObject(String key, Instant observedAt) {
        quarantine.recordObservations(List.of(new OrphanCandidate(key, 1, null, observedAt)));
    }

    private void confirm(String key) {
        observeWithoutObject(key, T0.minus(GRACE));
        recheck().recheck(key, T0);
    }

    /** Storage em memória: listagem no estilo S3 e exclusão com a semântica real (ausente = NOT_FOUND). */
    private static final class InMemoryObjectStore implements ObjectStorageListingPort, ObjectStorageDeletionPort {

        private final TreeMap<String, StoredObject> objects = new TreeMap<>();
        private final List<String> requestedMarkers = new ArrayList<>();
        private final List<Integer> requestedPageSizes = new ArrayList<>();
        private final List<String> deleteCalls = new ArrayList<>();
        private RuntimeException listingFailure;
        private ObjectDeletionResult forcedResult;
        private String throwOn;

        String add(String key) {
            objects.put(key, new StoredObject(key, 10, T0.minusSeconds(60)));
            return key;
        }

        @Override
        public StoredObjectPage listObjects(String prefix, String startAfter, int maxKeys) {
            if (listingFailure != null) {
                throw listingFailure;
            }
            requestedMarkers.add(startAfter);
            requestedPageSizes.add(maxKeys);
            Map<String, StoredObject> tail = startAfter == null ? objects : objects.tailMap(startAfter, false);
            List<StoredObject> page = tail.values().stream()
                    .filter(object -> object.key().startsWith(prefix)).limit(maxKeys).toList();
            return new StoredObjectPage(page, page.size() == maxKeys ? page.get(page.size() - 1).key() : null);
        }

        @Override
        public ObjectDeletionResult delete(String key) {
            deleteCalls.add(key);
            if (key.equals(throwOn)) {
                throw new IllegalStateException("erro inesperado isolado");
            }
            if (forcedResult != null) {
                return forcedResult;
            }
            return objects.remove(key) != null ? ObjectDeletionResult.DELETED : ObjectDeletionResult.NOT_FOUND;
        }
    }

    /** Referências de review_media lidas do mesmo mapa usado pela rechecagem e pela exclusão. */
    private static final class QuarantineBackedReferences implements ReviewMediaRepository {

        private final InMemoryStorageQuarantineRepository quarantine;

        QuarantineBackedReferences(InMemoryStorageQuarantineRepository quarantine) {
            this.quarantine = quarantine;
        }

        @Override
        public List<ReviewMediaReference> findReferencesByObjectKeys(Collection<String> objectKeys) {
            Map<String, ReviewMediaStatus> references = new HashMap<>(quarantine.references);
            return objectKeys.stream()
                    .filter(key -> references.containsKey(key))
                    .map(key -> new ReviewMediaReference(UUID.randomUUID(), UUID.randomUUID(), key,
                            references.get(key), T0, T0))
                    .toList();
        }

        @Override
        public ReviewMedia save(ReviewMedia media) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<ReviewMedia> findById(UUID id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ReviewMedia> findActiveByReviewId(UUID reviewId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long countActiveByReviewId(UUID reviewId) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class FreeLock implements StorageGcExecutionLock {
        @Override
        public <T> Optional<T> runExclusively(Supplier<T> cycle) {
            return Optional.of(cycle.get());
        }
    }

    private static final class BusyLock implements StorageGcExecutionLock {
        @Override
        public <T> Optional<T> runExclusively(Supplier<T> cycle) {
            return Optional.empty();
        }
    }

    private static final class FailingLock implements StorageGcExecutionLock {
        @Override
        public <T> Optional<T> runExclusively(Supplier<T> cycle) {
            throw new IllegalStateException("banco indisponível");
        }
    }

    /** Relógio determinístico; opcionalmente avança a cada leitura para simular tempo decorrido. */
    private static final class MutableClock extends Clock {

        private Instant now;
        private Duration autoAdvance = Duration.ZERO;

        MutableClock(Instant now) {
            this.now = now;
        }

        void set(Instant instant) {
            this.now = instant;
        }

        @Override
        public Instant instant() {
            Instant current = now;
            now = now.plus(autoAdvance);
            return current;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
