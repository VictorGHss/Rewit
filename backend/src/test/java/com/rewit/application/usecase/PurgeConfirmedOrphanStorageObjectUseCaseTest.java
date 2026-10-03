package com.rewit.application.usecase;

import com.rewit.application.dto.storage.ObjectDeletionResult;
import com.rewit.application.dto.storage.StorageQuarantineDtos.StoragePurgeOutcome;
import com.rewit.application.dto.storage.StorageQuarantineDtos.StoragePurgeResult;
import com.rewit.application.dto.storage.StorageReconciliationDtos.OrphanCandidate;
import com.rewit.application.port.ObjectStorageDeletionPort;
import com.rewit.application.port.ObjectStorageListingPort;
import com.rewit.application.port.ObjectStoragePort;
import com.rewit.application.storage.FixedStorageQuarantineGracePolicy;
import com.rewit.domain.enums.ReviewMediaStatus;
import com.rewit.domain.enums.StorageQuarantineStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Testes Unitários: PurgeConfirmedOrphanStorageObjectUseCase (Step 28.4)")
class PurgeConfirmedOrphanStorageObjectUseCaseTest {

    private static final Instant FIRST_OBSERVED = Instant.parse("2026-10-03T12:00:00Z");
    private static final Duration GRACE = Duration.ofMinutes(30);

    private final UUID reviewId = UUID.randomUUID();
    private final String key = "reviews/" + reviewId + "/" + UUID.randomUUID() + "/image.jpg";
    private final InMemoryStorageQuarantineRepository quarantine = new InMemoryStorageQuarantineRepository();
    private final InMemoryDeletionStorage storage = new InMemoryDeletionStorage();
    private final PurgeConfirmedOrphanStorageObjectUseCase useCase =
            new PurgeConfirmedOrphanStorageObjectUseCase(quarantine, storage);

    @Test
    @DisplayName("CONFIRMED_ORPHAN com objeto existente: exclusão física e quarentena encerrada")
    void confirmedOrphanWithExistingObjectIsDeleted() {
        confirmOrphan();
        storage.objects.add(key);

        StoragePurgeResult result = useCase.purge(key);

        assertEquals(StoragePurgeOutcome.PURGED, result.outcome());
        assertEquals(ObjectDeletionResult.DELETED, result.deletionResult());
        assertEquals(List.of(key), storage.deleteCalls);
        assertFalse(storage.objects.contains(key));
        assertFalse(quarantine.entries.containsKey(key));
        assertEquals(List.of(reviewId), quarantine.lockedReviewIds.subList(1, 2), "purge trava a review dona da chave");
    }

    @Test
    @DisplayName("CONFIRMED_ORPHAN com objeto já ausente: NOT_FOUND é sucesso idempotente e encerra a quarentena")
    void missingObjectIsIdempotentSuccess() {
        confirmOrphan();

        StoragePurgeResult result = useCase.purge(key);

        assertEquals(StoragePurgeOutcome.PURGED, result.outcome());
        assertEquals(ObjectDeletionResult.NOT_FOUND, result.deletionResult());
        assertFalse(quarantine.entries.containsKey(key));
    }

    @Test
    @DisplayName("Referência ACTIVE reaparecida: não exclui e libera a quarentena")
    void activeReferenceProtectsObject() {
        confirmOrphan();
        storage.objects.add(key);
        quarantine.references.put(key, ReviewMediaStatus.ACTIVE);

        StoragePurgeResult result = useCase.purge(key);

        assertEquals(StoragePurgeOutcome.WITH_REFERENCE, result.outcome());
        assertEquals(ReviewMediaStatus.ACTIVE, result.referenceStatus());
        assertTrue(storage.deleteCalls.isEmpty());
        assertTrue(storage.objects.contains(key));
        assertFalse(quarantine.entries.containsKey(key));
    }

    @Test
    @DisplayName("Referência REMOVED reaparecida: continua sendo referência; não exclui")
    void removedReferenceProtectsObject() {
        confirmOrphan();
        storage.objects.add(key);
        quarantine.references.put(key, ReviewMediaStatus.REMOVED);

        StoragePurgeResult result = useCase.purge(key);

        assertEquals(StoragePurgeOutcome.WITH_REFERENCE, result.outcome());
        assertEquals(ReviewMediaStatus.REMOVED, result.referenceStatus());
        assertTrue(storage.deleteCalls.isEmpty());
    }

    @Test
    @DisplayName("Chave sem quarentena: nenhuma operação de storage")
    void notQuarantinedNeverDeletes() {
        storage.objects.add(key);

        assertEquals(StoragePurgeOutcome.NOT_QUARANTINED, useCase.purge(key).outcome());
        assertTrue(storage.deleteCalls.isEmpty());
        assertTrue(quarantine.lockedReviewIds.isEmpty());
    }

    @Test
    @DisplayName("Quarentena ainda OBSERVED: nenhuma operação de storage nem lock")
    void observedButNotConfirmedNeverDeletes() {
        observe();
        storage.objects.add(key);

        assertEquals(StoragePurgeOutcome.NOT_CONFIRMED, useCase.purge(key).outcome());
        assertTrue(storage.deleteCalls.isEmpty());
        assertTrue(quarantine.lockedReviewIds.isEmpty());
        assertEquals(StorageQuarantineStatus.OBSERVED, quarantine.entries.get(key).status());
    }

    @Test
    @DisplayName("Falha transitória: quarentena mantida como CONFIRMED_ORPHAN e nova tentativa conclui")
    void transientFailureKeepsQuarantineForRetry() {
        confirmOrphan();
        storage.objects.add(key);
        storage.forcedResult = ObjectDeletionResult.TRANSIENT_FAILURE;

        StoragePurgeResult failed = useCase.purge(key);

        assertEquals(StoragePurgeOutcome.RETRYABLE_FAILURE, failed.outcome());
        assertEquals(ObjectDeletionResult.TRANSIENT_FAILURE, failed.deletionResult());
        assertEquals(StorageQuarantineStatus.CONFIRMED_ORPHAN, quarantine.entries.get(key).status());

        storage.forcedResult = null;
        assertEquals(StoragePurgeOutcome.PURGED, useCase.purge(key).outcome());
        assertFalse(quarantine.entries.containsKey(key));
    }

    @Test
    @DisplayName("Falha permanente: quarentena mantida para investigação, sem mascarar o resultado")
    void permanentFailureKeepsQuarantine() {
        confirmOrphan();
        storage.objects.add(key);
        storage.forcedResult = ObjectDeletionResult.PERMANENT_FAILURE;

        StoragePurgeResult result = useCase.purge(key);

        assertEquals(StoragePurgeOutcome.PERMANENT_FAILURE, result.outcome());
        assertEquals(ObjectDeletionResult.PERMANENT_FAILURE, result.deletionResult());
        assertEquals(StorageQuarantineStatus.CONFIRMED_ORPHAN, quarantine.entries.get(key).status());
    }

    @Test
    @DisplayName("Chave inválida: nenhuma consulta, lock ou exclusão; a quarentena não é tocada")
    void invalidKeyNeverDeletes() {
        for (String invalid : Arrays.asList(null, "avatars/x.jpg", "reviews/manual-upload.jpg",
                "reviews/" + reviewId + "/" + UUID.randomUUID() + "/image.gif")) {
            assertEquals(StoragePurgeOutcome.INVALID_OBJECT_KEY, useCase.purge(invalid).outcome(), String.valueOf(invalid));
        }
        assertTrue(storage.deleteCalls.isEmpty());
        assertTrue(quarantine.lockedReviewIds.isEmpty());
    }

    @Test
    @DisplayName("Janela de queda: exclusão física sem commit mantém a quarentena; a nova tentativa obtém NOT_FOUND e conclui")
    void crashWindowIsRecoveredByIdempotentRetry() {
        confirmOrphan();
        storage.objects.add(key);
        quarantine.crashAfterStorageDeletion = true;

        assertThrows(IllegalStateException.class, () -> useCase.purge(key));
        assertFalse(storage.objects.contains(key), "o storage não é desfeito pelo rollback");
        assertEquals(StorageQuarantineStatus.CONFIRMED_ORPHAN, quarantine.entries.get(key).status());

        quarantine.crashAfterStorageDeletion = false;
        StoragePurgeResult retry = useCase.purge(key);

        assertEquals(StoragePurgeOutcome.PURGED, retry.outcome());
        assertEquals(ObjectDeletionResult.NOT_FOUND, retry.deletionResult());
        assertFalse(quarantine.entries.containsKey(key));
    }

    @Test
    @DisplayName("Segunda execução após a exclusão é idempotente e não toca o storage")
    void secondPurgeIsIdempotent() {
        confirmOrphan();
        storage.objects.add(key);

        assertEquals(StoragePurgeOutcome.PURGED, useCase.purge(key).outcome());
        assertEquals(StoragePurgeOutcome.NOT_QUARANTINED, useCase.purge(key).outcome());
        assertEquals(1, storage.deleteCalls.size());
    }

    @Test
    @DisplayName("O use case só recebe a capacidade de exclusão, sem gravação, leitura ou listagem")
    void useCaseOnlyHasDeletionCapability() {
        List<Class<?>> forbidden = List.of(ObjectStoragePort.class, ObjectStorageListingPort.class);
        for (Field field : PurgeConfirmedOrphanStorageObjectUseCase.class.getDeclaredFields()) {
            assertFalse(forbidden.contains(field.getType()), "campo " + field.getName());
        }
        for (Constructor<?> constructor : PurgeConfirmedOrphanStorageObjectUseCase.class.getConstructors()) {
            List<Class<?>> parameters = Arrays.asList(constructor.getParameterTypes());
            assertTrue(parameters.contains(ObjectStorageDeletionPort.class));
            parameters.forEach(parameter -> assertFalse(forbidden.contains(parameter), parameter.getName()));
        }
    }

    @Test
    @DisplayName("Contrato do resultado de exclusão: somente DELETED e NOT_FOUND significam objeto ausente")
    void deletionResultContract() {
        assertTrue(ObjectDeletionResult.DELETED.isAbsentAfterwards());
        assertTrue(ObjectDeletionResult.NOT_FOUND.isAbsentAfterwards());
        assertFalse(ObjectDeletionResult.TRANSIENT_FAILURE.isAbsentAfterwards());
        assertFalse(ObjectDeletionResult.PERMANENT_FAILURE.isAbsentAfterwards());
    }

    private void observe() {
        quarantine.recordObservations(List.of(new OrphanCandidate(key, 10, null, FIRST_OBSERVED)));
    }

    // Caminho real até CONFIRMED_ORPHAN: observação persistida + rechecagem após o grace period
    private void confirmOrphan() {
        observe();
        new RecheckQuarantinedStorageObjectUseCase(quarantine, new FixedStorageQuarantineGracePolicy(GRACE))
                .recheck(key, FIRST_OBSERVED.plus(GRACE));
        assertEquals(StorageQuarantineStatus.CONFIRMED_ORPHAN, quarantine.entries.get(key).status());
    }

    /** Storage em memória com a semântica real observada: exclusão de chave ausente é NOT_FOUND. */
    private static final class InMemoryDeletionStorage implements ObjectStorageDeletionPort {

        private final Set<String> objects = new HashSet<>();
        private final List<String> deleteCalls = new ArrayList<>();
        private ObjectDeletionResult forcedResult;

        @Override
        public ObjectDeletionResult delete(String key) {
            deleteCalls.add(key);
            if (forcedResult != null) {
                return forcedResult;
            }
            return objects.remove(key) ? ObjectDeletionResult.DELETED : ObjectDeletionResult.NOT_FOUND;
        }
    }
}
