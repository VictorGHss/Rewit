package com.rewit.infrastructure.persistence.adapter;

import com.rewit.application.dto.storage.ObjectDeletionResult;
import com.rewit.application.dto.storage.StorageQuarantineDtos.QuarantinePurgeResolution;
import com.rewit.application.dto.storage.StorageQuarantineDtos.QuarantineResolution;
import com.rewit.application.dto.storage.StorageQuarantineDtos.QuarantinedStorageObject;
import com.rewit.application.dto.storage.StorageReconciliationDtos.OrphanCandidate;
import com.rewit.application.port.StorageQuarantineRepository;
import com.rewit.domain.enums.ReviewMediaStatus;
import com.rewit.domain.enums.StorageQuarantineStatus;
import com.rewit.infrastructure.persistence.entity.ReviewMediaJpaEntity;
import com.rewit.infrastructure.persistence.repository.ReviewJpaRepository;
import com.rewit.infrastructure.persistence.repository.ReviewMediaJpaRepository;
import com.rewit.infrastructure.persistence.repository.StorageObjectQuarantineJpaRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Adaptador de persistência da quarentena de storage (Step 28.3).
 *
 * <p><b>Lock de criação de mídia.</b> {@code ReviewMediaService.uploadMedia} adquire
 * {@link ReviewJpaRepository#findByIdForUpdate} na review antes de enviar o objeto ao storage e só
 * o libera no commit da linha em review_media. Como toda chave contém o reviewId e cada upload gera
 * um mediaId novo, uma transação que detém esse mesmo lock e não encontra referência sabe que não há
 * upload em andamento para a chave. Review inexistente dispensa o lock: nenhum upload consegue
 * criar referência para ela (o upload exige a linha e a FK de review_media também).
 * A exclusão física ({@link #purgeConfirmedOrphanUnderCreationLock}) repete a consulta e chama o
 * storage com esse lock mantido pela transação PostgreSQL. O storage é uma operação externa: não
 * participa da transação e não é desfeito por rollback.
 */
@Component
public class StorageQuarantineRepositoryAdapter implements StorageQuarantineRepository {

    private final StorageObjectQuarantineJpaRepository quarantineJpaRepository;
    private final ReviewMediaJpaRepository reviewMediaJpaRepository;
    private final ReviewJpaRepository reviewJpaRepository;
    private final com.rewit.infrastructure.persistence.repository.PlaceMediaJpaRepository placeMediaJpaRepository;
    private final com.rewit.infrastructure.persistence.repository.PlaceJpaRepository placeJpaRepository;

    public StorageQuarantineRepositoryAdapter(StorageObjectQuarantineJpaRepository quarantineJpaRepository,
                                              ReviewMediaJpaRepository reviewMediaJpaRepository,
                                              ReviewJpaRepository reviewJpaRepository,
                                              com.rewit.infrastructure.persistence.repository.PlaceMediaJpaRepository placeMediaJpaRepository,
                                              com.rewit.infrastructure.persistence.repository.PlaceJpaRepository placeJpaRepository) {
        this.quarantineJpaRepository = Objects.requireNonNull(quarantineJpaRepository, "quarantineJpaRepository must not be null");
        this.reviewMediaJpaRepository = Objects.requireNonNull(reviewMediaJpaRepository, "reviewMediaJpaRepository must not be null");
        this.reviewJpaRepository = Objects.requireNonNull(reviewJpaRepository, "reviewJpaRepository must not be null");
        this.placeMediaJpaRepository = Objects.requireNonNull(placeMediaJpaRepository, "placeMediaJpaRepository must not be null");
        this.placeJpaRepository = Objects.requireNonNull(placeJpaRepository, "placeJpaRepository must not be null");
    }

    @Override
    @Transactional
    public void recordObservations(Collection<OrphanCandidate> candidates) {
        Objects.requireNonNull(candidates, "candidates must not be null");
        for (OrphanCandidate candidate : candidates) {
            if (candidate.lastModified() != null) {
                quarantineJpaRepository.upsertObservation(candidate.objectKey(), candidate.observedAt(), candidate.lastModified());
            } else {
                quarantineJpaRepository.upsertObservationWithoutLastModified(candidate.objectKey(), candidate.observedAt());
            }
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<QuarantinedStorageObject> findByObjectKey(String objectKey) {
        if (objectKey == null) {
            return Optional.empty();
        }
        return quarantineJpaRepository.findByObjectKey(objectKey).map(entity -> entity.toDto());
    }

    @Override
    @Transactional(readOnly = true)
    public List<QuarantinedStorageObject> findByStatus(StorageQuarantineStatus status, int limit) {
        Objects.requireNonNull(status, "status must not be null");
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        return quarantineJpaRepository.findByStatusOrderByFirstObservedAtAscIdAsc(status.name(), PageRequest.of(0, limit))
                .stream()
                .map(entity -> entity.toDto())
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public long countByStatus(StorageQuarantineStatus status) {
        return quarantineJpaRepository.countByStatus(Objects.requireNonNull(status, "status must not be null").name());
    }

    @Override
    @Transactional
    public QuarantineResolution resolveUnderCreationLock(String objectKey, UUID entityId,
                                                         Instant expectedFirstObservedAt, Instant now) {
        Objects.requireNonNull(objectKey, "objectKey must not be null");
        Objects.requireNonNull(entityId, "entityId must not be null");
        Objects.requireNonNull(expectedFirstObservedAt, "expectedFirstObservedAt must not be null");
        Objects.requireNonNull(now, "now must not be null");

        // 1. Mesmo lock do upload; ordem fixa (entidade dona → quarentena) em todos os caminhos que travam ambos
        if (objectKey.startsWith("places/")) {
            placeJpaRepository.findByIdForUpdate(entityId);
        } else {
            reviewJpaRepository.findByIdForUpdate(entityId);
        }

        // 2. A decisão vale somente para a observação avaliada pelo chamador
        Optional<UUID> entryId = quarantineJpaRepository.lockEntry(objectKey, expectedFirstObservedAt);
        if (entryId.isEmpty()) {
            return new QuarantineResolution(QuarantineResolution.Kind.ENTRY_CHANGED, null);
        }

        // 3. Segunda consulta: qualquer linha em place_media ou review_media (não purgada) protege
        if (objectKey.startsWith("places/")) {
            List<com.rewit.infrastructure.persistence.entity.PlaceMediaJpaEntity> references =
                    placeMediaJpaRepository.findByObjectKeyIn(List.of(objectKey));
            if (!references.isEmpty()) {
                var ref = references.get(0);
                if (ref.getPurgedAt() == null) {
                    quarantineJpaRepository.releaseEntry(entryId.get());
                    ReviewMediaStatus refStatus = "REMOVED".equalsIgnoreCase(ref.getStatus())
                            ? ReviewMediaStatus.REMOVED : ReviewMediaStatus.ACTIVE;
                    return new QuarantineResolution(QuarantineResolution.Kind.REFERENCE_FOUND, refStatus);
                }
            }
        } else {
            List<ReviewMediaJpaEntity> references = reviewMediaJpaRepository.findByObjectKeyIn(List.of(objectKey));
            if (!references.isEmpty()) {
                quarantineJpaRepository.releaseEntry(entryId.get());
                return new QuarantineResolution(QuarantineResolution.Kind.REFERENCE_FOUND,
                        ReviewMediaStatus.valueOf(references.get(0).getStatus()));
            }
        }

        quarantineJpaRepository.markConfirmedOrphan(entryId.get(), now);
        return new QuarantineResolution(QuarantineResolution.Kind.CONFIRMED_ORPHAN, null);
    }

    @Override
    @Transactional
    public QuarantinePurgeResolution purgeConfirmedOrphanUnderCreationLock(String objectKey, UUID entityId,
                                                                           Instant expectedFirstObservedAt,
                                                                           Function<String, ObjectDeletionResult> physicalDeletion) {
        Objects.requireNonNull(objectKey, "objectKey must not be null");
        Objects.requireNonNull(entityId, "entityId must not be null");
        Objects.requireNonNull(expectedFirstObservedAt, "expectedFirstObservedAt must not be null");
        Objects.requireNonNull(physicalDeletion, "physicalDeletion must not be null");

        // 1. Mesmo lock do upload e da rechecagem, na mesma ordem (entidade dona → quarentena)
        if (objectKey.startsWith("places/")) {
            placeJpaRepository.findByIdForUpdate(entityId);
        } else {
            reviewJpaRepository.findByIdForUpdate(entityId);
        }

        // 2. Somente a observação avaliada, e somente se ainda estiver CONFIRMED_ORPHAN
        Optional<UUID> entryId = quarantineJpaRepository.lockConfirmedEntry(objectKey, expectedFirstObservedAt);
        if (entryId.isEmpty()) {
            return new QuarantinePurgeResolution(QuarantinePurgeResolution.Kind.ENTRY_CHANGED, null, null);
        }

        // 3. A consulta sob o lock é a autoridade: ACTIVE ou REMOVED (não purgado) protegem o objeto
        if (objectKey.startsWith("places/")) {
            List<com.rewit.infrastructure.persistence.entity.PlaceMediaJpaEntity> references =
                    placeMediaJpaRepository.findByObjectKeyIn(List.of(objectKey));
            if (!references.isEmpty()) {
                var ref = references.get(0);
                if (ref.getPurgedAt() == null) {
                    quarantineJpaRepository.releaseEntry(entryId.get());
                    ReviewMediaStatus refStatus = "REMOVED".equalsIgnoreCase(ref.getStatus())
                            ? ReviewMediaStatus.REMOVED : ReviewMediaStatus.ACTIVE;
                    return new QuarantinePurgeResolution(QuarantinePurgeResolution.Kind.REFERENCE_FOUND, refStatus, null);
                }
            }
        } else {
            List<ReviewMediaJpaEntity> references = reviewMediaJpaRepository.findByObjectKeyIn(List.of(objectKey));
            if (!references.isEmpty()) {
                quarantineJpaRepository.releaseEntry(entryId.get());
                return new QuarantinePurgeResolution(QuarantinePurgeResolution.Kind.REFERENCE_FOUND,
                        ReviewMediaStatus.valueOf(references.get(0).getStatus()), null);
            }
        }

        // 4. Operação externa, com os locks mantidos; não é revertida por rollback
        ObjectDeletionResult deletionResult = Objects.requireNonNull(physicalDeletion.apply(objectKey),
                "physicalDeletion must return a result");

        // 5. A linha só sai depois do storage: se o commit falhar, a próxima execução obtém NOT_FOUND
        if (deletionResult.isAbsentAfterwards()) {
            quarantineJpaRepository.releaseEntry(entryId.get());
            if (objectKey.startsWith("places/")) {
                List<com.rewit.infrastructure.persistence.entity.PlaceMediaJpaEntity> references =
                        placeMediaJpaRepository.findByObjectKeyIn(List.of(objectKey));
                for (var ref : references) {
                    ref.setPurgedAt(Instant.now());
                    ref.setUpdatedAt(Instant.now());
                    placeMediaJpaRepository.save(ref);
                }
            }
        }
        return new QuarantinePurgeResolution(QuarantinePurgeResolution.Kind.STORAGE_ATTEMPTED, null, deletionResult);
    }
}
