package com.rewit.application.usecase;

import com.rewit.application.dto.storage.ObjectDeletionResult;
import com.rewit.application.dto.storage.StorageQuarantineDtos.QuarantinePurgeResolution;
import com.rewit.application.dto.storage.StorageQuarantineDtos.QuarantineResolution;
import com.rewit.application.dto.storage.StorageQuarantineDtos.QuarantinedStorageObject;
import com.rewit.application.dto.storage.StorageReconciliationDtos.OrphanCandidate;
import com.rewit.application.port.StorageQuarantineRepository;
import com.rewit.domain.enums.ReviewMediaStatus;
import com.rewit.domain.enums.StorageQuarantineStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Quarentena em memória com a mesma semântica do adapter PostgreSQL, para testes de use case.
 * As garantias de banco (unicidade concorrente, lock de criação) são cobertas em integração.
 */
final class InMemoryStorageQuarantineRepository implements StorageQuarantineRepository {

    final Map<String, QuarantinedStorageObject> entries = new HashMap<>();
    final Map<String, ReviewMediaStatus> references = new HashMap<>();
    final List<List<String>> recordedBatches = new ArrayList<>();
    final List<UUID> lockedReviewIds = new ArrayList<>();
    /** Simula queda entre a exclusão no storage e a remoção da linha: a transação é desfeita. */
    boolean crashAfterStorageDeletion;

    @Override
    public void recordObservations(Collection<OrphanCandidate> candidates) {
        recordedBatches.add(candidates.stream().map(candidate -> candidate.objectKey()).toList());
        for (OrphanCandidate candidate : candidates) {
            QuarantinedStorageObject current = entries.get(candidate.objectKey());
            if (current == null) {
                entries.put(candidate.objectKey(), new QuarantinedStorageObject(candidate.objectKey(),
                        StorageQuarantineStatus.OBSERVED, candidate.observedAt(), candidate.observedAt(),
                        candidate.lastModified(), null));
            } else {
                Instant lastObserved = candidate.observedAt().isAfter(current.lastObservedAt())
                        ? candidate.observedAt() : current.lastObservedAt();
                Instant lastModified = candidate.lastModified() != null ? candidate.lastModified() : current.lastModifiedAt();
                entries.put(candidate.objectKey(), new QuarantinedStorageObject(candidate.objectKey(), current.status(),
                        current.firstObservedAt(), lastObserved, lastModified, current.confirmedAt()));
            }
        }
    }

    @Override
    public Optional<QuarantinedStorageObject> findByObjectKey(String objectKey) {
        return Optional.ofNullable(entries.get(objectKey));
    }

    @Override
    public QuarantineResolution resolveUnderCreationLock(String objectKey, UUID reviewId,
                                                         Instant expectedFirstObservedAt, Instant now) {
        lockedReviewIds.add(reviewId);
        QuarantinedStorageObject current = entries.get(objectKey);
        if (current == null || !current.firstObservedAt().equals(expectedFirstObservedAt)) {
            return new QuarantineResolution(QuarantineResolution.Kind.ENTRY_CHANGED, null);
        }
        ReviewMediaStatus reference = references.get(objectKey);
        if (reference != null) {
            entries.remove(objectKey);
            return new QuarantineResolution(QuarantineResolution.Kind.REFERENCE_FOUND, reference);
        }
        Instant confirmedAt = current.confirmedAt() != null ? current.confirmedAt() : now;
        entries.put(objectKey, new QuarantinedStorageObject(objectKey, StorageQuarantineStatus.CONFIRMED_ORPHAN,
                current.firstObservedAt(), current.lastObservedAt(), current.lastModifiedAt(), confirmedAt));
        return new QuarantineResolution(QuarantineResolution.Kind.CONFIRMED_ORPHAN, null);
    }

    @Override
    public QuarantinePurgeResolution purgeConfirmedOrphanUnderCreationLock(String objectKey, UUID reviewId,
                                                                           Instant expectedFirstObservedAt,
                                                                           Function<String, ObjectDeletionResult> physicalDeletion) {
        lockedReviewIds.add(reviewId);
        QuarantinedStorageObject current = entries.get(objectKey);
        if (current == null || current.status() != StorageQuarantineStatus.CONFIRMED_ORPHAN
                || !current.firstObservedAt().equals(expectedFirstObservedAt)) {
            return new QuarantinePurgeResolution(QuarantinePurgeResolution.Kind.ENTRY_CHANGED, null, null);
        }
        ReviewMediaStatus reference = references.get(objectKey);
        if (reference != null) {
            entries.remove(objectKey);
            return new QuarantinePurgeResolution(QuarantinePurgeResolution.Kind.REFERENCE_FOUND, reference, null);
        }
        ObjectDeletionResult deletionResult = physicalDeletion.apply(objectKey);
        if (crashAfterStorageDeletion) {
            throw new IllegalStateException("queda simulada antes do commit");
        }
        if (deletionResult.isAbsentAfterwards()) {
            entries.remove(objectKey);
        }
        return new QuarantinePurgeResolution(QuarantinePurgeResolution.Kind.STORAGE_ATTEMPTED, null, deletionResult);
    }
}
