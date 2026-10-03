package com.rewit.application.usecase;

import com.rewit.application.dto.storage.StorageReconciliationDtos.OrphanCandidate;
import com.rewit.application.dto.storage.StorageReconciliationDtos.ReviewMediaReference;
import com.rewit.application.dto.storage.StorageReconciliationDtos.StorageReconciliationReport;
import com.rewit.application.dto.storage.StorageReconciliationDtos.StoredObject;
import com.rewit.application.dto.storage.StorageReconciliationDtos.StoredObjectPage;
import com.rewit.application.port.ObjectStorageListingPort;
import com.rewit.application.port.ObjectStoragePort;
import com.rewit.application.port.ReviewMediaRepository;
import com.rewit.domain.enums.ReviewMediaStatus;
import com.rewit.domain.model.ReviewMedia;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Testes Unitários: ReconcileReviewMediaStorageUseCase (Step 28.1)")
class ReconcileReviewMediaStorageUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-10-03T12:00:00Z");
    private static final Instant MODIFIED = Instant.parse("2026-10-01T08:30:00Z");

    private final FakeReviewMediaRepository repository = new FakeReviewMediaRepository();

    @Test
    @DisplayName("Objeto com referência ACTIVE não é candidato")
    void activeReferenceIsNotCandidate() {
        String key = managedKey();
        SortedStorage storage = new SortedStorage().add(key);
        repository.register(key, ReviewMediaStatus.ACTIVE);

        StorageReconciliationReport report = useCase(storage, 10, 10).reconcile(null, NOW);

        assertTrue(report.orphanCandidates().isEmpty());
        assertEquals(1, report.activeReferences());
        assertTrue(report.removedReferences().isEmpty());
        assertTrue(report.complete());
    }

    @Test
    @DisplayName("Objeto gerenciado sem referência vira candidato observado, com os dados da observação")
    void unreferencedObjectIsObservedCandidate() {
        String key = managedKey();
        SortedStorage storage = new SortedStorage().add(new StoredObject(key, 2048, MODIFIED));

        StorageReconciliationReport report = useCase(storage, 10, 10).reconcile(null, NOW);

        assertEquals(List.of(new OrphanCandidate(key, 2048, MODIFIED, NOW)), report.orphanCandidates());
        assertEquals(0, report.activeReferences());
        assertEquals(NOW, report.observedAt());
    }

    @Test
    @DisplayName("Referência REMOVED com objeto presente não é órfã e é reportada à parte")
    void removedReferenceIsReportedButNotCandidate() {
        String key = managedKey();
        SortedStorage storage = new SortedStorage().add(key);
        ReviewMediaReference reference = repository.register(key, ReviewMediaStatus.REMOVED);

        StorageReconciliationReport report = useCase(storage, 10, 10).reconcile(null, NOW);

        assertTrue(report.orphanCandidates().isEmpty());
        assertEquals(List.of(reference), report.removedReferences());
        assertEquals(0, report.activeReferences());
    }

    @Test
    @DisplayName("Objeto fora de reviews/ é ignorado mesmo se a listagem o devolver")
    void objectOutsideManagedPrefixIsIgnored() {
        ScriptedStorage storage = new ScriptedStorage(page(null, "avatars/user.jpg", "reviews-archive/old.jpg"));

        StorageReconciliationReport report = useCase(storage, 10, 10).reconcile(null, NOW);

        assertTrue(report.orphanCandidates().isEmpty());
        assertEquals(2, report.outOfNamespaceIgnored());
        assertEquals(2, report.objectsExamined());
        assertTrue(repository.queriedKeys.isEmpty(), "chaves fora do prefixo não devem ser consultadas");
    }

    @Test
    @DisplayName("Chave sob reviews/ fora do formato gerado pela aplicação não é candidata")
    void unrecognizedKeyUnderPrefixIsNotCandidate() {
        SortedStorage storage = new SortedStorage().add("reviews/manual-upload.jpg");

        StorageReconciliationReport report = useCase(storage, 10, 10).reconcile(null, NOW);

        assertTrue(report.orphanCandidates().isEmpty());
        assertEquals(1, report.unrecognizedKeys());
    }

    @Test
    @DisplayName("Múltiplas páginas são todas processadas, em ordem, com consulta ao banco por página")
    void multiplePagesAreAllProcessed() {
        List<String> keys = sortedManagedKeys(5);
        SortedStorage storage = new SortedStorage();
        keys.forEach(storage::add);
        repository.register(keys.get(1), ReviewMediaStatus.ACTIVE);
        repository.register(keys.get(3), ReviewMediaStatus.ACTIVE);

        StorageReconciliationReport report = useCase(storage, 2, 10).reconcile(null, NOW);

        assertTrue(report.complete());
        assertNull(report.nextStartAfter());
        assertEquals(3, report.pagesScanned());
        assertEquals(5, report.objectsExamined());
        assertEquals(2, report.activeReferences());
        assertEquals(List.of(keys.get(0), keys.get(2), keys.get(4)), candidateKeys(report));
        assertEquals(Arrays.asList(null, keys.get(1), keys.get(3)), storage.requestedMarkers);
        assertEquals(List.of(List.of(keys.get(0), keys.get(1)), List.of(keys.get(2), keys.get(3)), List.of(keys.get(4))),
                repository.queriedKeys);
    }

    @Test
    @DisplayName("Orçamento de páginas interrompe a passada e o marcador permite retomar sem perder objetos")
    void pageBudgetReturnsResumableMarker() {
        List<String> keys = sortedManagedKeys(3);
        SortedStorage storage = new SortedStorage();
        keys.forEach(storage::add);
        ReconcileReviewMediaStorageUseCase useCase = useCase(storage, 2, 1);

        StorageReconciliationReport first = useCase.reconcile(null, NOW);
        StorageReconciliationReport second = useCase.reconcile(first.nextStartAfter(), NOW);

        assertFalse(first.complete());
        assertEquals(keys.get(1), first.nextStartAfter());
        assertEquals(List.of(keys.get(0), keys.get(1)), candidateKeys(first));
        assertTrue(second.complete());
        assertEquals(keys.get(1), second.startAfter());
        assertEquals(List.of(keys.get(2)), candidateKeys(second));
    }

    @Test
    @DisplayName("Prefixo vazio gera resultado vazio e completo, sem consultar o banco")
    void emptyListingProducesEmptyReport() {
        StorageReconciliationReport report = useCase(new SortedStorage(), 10, 10).reconcile(null, NOW);

        assertTrue(report.complete());
        assertEquals(1, report.pagesScanned());
        assertEquals(0, report.objectsExamined());
        assertTrue(report.orphanCandidates().isEmpty());
        assertTrue(report.removedReferences().isEmpty());
        assertTrue(repository.queriedKeys.isEmpty());
    }

    @Test
    @DisplayName("Chave repetida na listagem é processada uma única vez, de forma determinística")
    void duplicateKeysAreProcessedOnce() {
        List<String> keys = sortedManagedKeys(2);
        ScriptedStorage storage = new ScriptedStorage(
                page(keys.get(0), keys.get(0), keys.get(0)),
                page(null, keys.get(0), keys.get(1)));

        StorageReconciliationReport report = useCase(storage, 10, 10).reconcile(null, NOW);

        assertEquals(List.of(keys.get(0), keys.get(1)), candidateKeys(report));
        assertEquals(2, report.duplicatesIgnored());
        assertEquals(4, report.objectsExamined());
    }

    @Test
    @DisplayName("Marcador inicial fora do prefixo gerenciado é rejeitado antes de listar")
    void startAfterOutsideManagedPrefixIsRejected() {
        SortedStorage storage = new SortedStorage();
        ReconcileReviewMediaStorageUseCase useCase = useCase(storage, 10, 10);

        assertThrows(IllegalArgumentException.class, () -> useCase.reconcile("avatars/", NOW));
        assertTrue(storage.requestedMarkers.isEmpty());
    }

    @Test
    @DisplayName("Listagem que não avança o marcador interrompe a passada em vez de repetir páginas")
    void nonAdvancingListingFails() {
        String key = managedKey();
        ScriptedStorage storage = new ScriptedStorage(page(key, key), page(key));
        ReconcileReviewMediaStorageUseCase useCase = useCase(storage, 10, 10);

        assertThrows(IllegalStateException.class, () -> useCase.reconcile(null, NOW));
    }

    @Test
    @DisplayName("Configuração inválida é rejeitada na construção")
    void invalidConfigurationIsRejected() {
        SortedStorage storage = new SortedStorage();
        assertThrows(IllegalArgumentException.class, () -> useCase(storage, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> useCase(storage, 1, 0));
        assertThrows(NullPointerException.class, () -> useCase(storage, 1, 1).reconcile(null, null));
    }

    @Test
    @DisplayName("O use case não recebe a porta com capacidade de remoção do storage")
    void useCaseHasNoStorageDeleteCapability() {
        for (Field field : ReconcileReviewMediaStorageUseCase.class.getDeclaredFields()) {
            assertNotEquals(ObjectStoragePort.class, field.getType(), "campo " + field.getName());
        }
        for (Constructor<?> constructor : ReconcileReviewMediaStorageUseCase.class.getConstructors()) {
            assertFalse(Arrays.asList(constructor.getParameterTypes()).contains(ObjectStoragePort.class));
        }
    }

    private ReconcileReviewMediaStorageUseCase useCase(ObjectStorageListingPort storage, int pageSize, int maxPages) {
        return new ReconcileReviewMediaStorageUseCase(storage, repository, pageSize, maxPages);
    }

    private static String managedKey() {
        return "reviews/" + UUID.randomUUID() + "/" + UUID.randomUUID() + "/image.jpg";
    }

    private static List<String> sortedManagedKeys(int count) {
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            keys.add(managedKey());
        }
        keys.sort(null);
        return keys;
    }

    private static List<String> candidateKeys(StorageReconciliationReport report) {
        return report.orphanCandidates().stream().map(candidate -> candidate.objectKey()).toList();
    }

    private static StoredObjectPage page(String nextStartAfter, String... keys) {
        return new StoredObjectPage(
                Arrays.stream(keys).map(key -> new StoredObject(key, 1, MODIFIED)).toList(),
                nextStartAfter);
    }

    /** Listagem com a semântica do S3: ordem lexicográfica, prefixo, start-after e max-keys. */
    private static final class SortedStorage implements ObjectStorageListingPort {

        private final TreeMap<String, StoredObject> objects = new TreeMap<>();
        private final List<String> requestedMarkers = new ArrayList<>();

        SortedStorage add(String key) {
            return add(new StoredObject(key, 1, MODIFIED));
        }

        SortedStorage add(StoredObject object) {
            objects.put(object.key(), object);
            return this;
        }

        @Override
        public StoredObjectPage listObjects(String prefix, String startAfter, int maxKeys) {
            requestedMarkers.add(startAfter);
            Map<String, StoredObject> tail = startAfter == null ? objects : objects.tailMap(startAfter, false);
            List<StoredObject> page = tail.values().stream()
                    .filter(object -> object.key().startsWith(prefix))
                    .limit(maxKeys)
                    .toList();
            String next = page.size() == maxKeys ? page.get(page.size() - 1).key() : null;
            return new StoredObjectPage(page, next);
        }
    }

    /** Devolve páginas pré-definidas, inclusive respostas que um storage real não deveria produzir. */
    private static final class ScriptedStorage implements ObjectStorageListingPort {

        private final Deque<StoredObjectPage> pages;

        ScriptedStorage(StoredObjectPage... pages) {
            this.pages = new ArrayDeque<>(List.of(pages));
        }

        @Override
        public StoredObjectPage listObjects(String prefix, String startAfter, int maxKeys) {
            return pages.removeFirst();
        }
    }

    private static final class FakeReviewMediaRepository implements ReviewMediaRepository {

        private final Map<String, ReviewMediaReference> references = new HashMap<>();
        private final List<List<String>> queriedKeys = new ArrayList<>();

        ReviewMediaReference register(String key, ReviewMediaStatus status) {
            ReviewMediaReference reference = new ReviewMediaReference(
                    UUID.randomUUID(), UUID.randomUUID(), key, status, MODIFIED, MODIFIED);
            references.put(key, reference);
            return reference;
        }

        @Override
        public List<ReviewMediaReference> findReferencesByObjectKeys(Collection<String> objectKeys) {
            queriedKeys.add(List.copyOf(objectKeys));
            return objectKeys.stream().map(references::get).filter(reference -> reference != null).toList();
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
}
