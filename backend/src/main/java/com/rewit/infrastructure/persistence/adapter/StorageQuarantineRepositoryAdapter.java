package com.rewit.infrastructure.persistence.adapter;

import com.rewit.application.dto.storage.StorageQuarantineDtos.QuarantineResolution;
import com.rewit.application.dto.storage.StorageQuarantineDtos.QuarantinedStorageObject;
import com.rewit.application.dto.storage.StorageReconciliationDtos.OrphanCandidate;
import com.rewit.application.port.StorageQuarantineRepository;
import com.rewit.domain.enums.ReviewMediaStatus;
import com.rewit.infrastructure.persistence.entity.ReviewMediaJpaEntity;
import com.rewit.infrastructure.persistence.repository.ReviewJpaRepository;
import com.rewit.infrastructure.persistence.repository.ReviewMediaJpaRepository;
import com.rewit.infrastructure.persistence.repository.StorageObjectQuarantineJpaRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Adaptador de persistência da quarentena de storage (Step 28.3).
 *
 * <p><b>Lock de criação de mídia.</b> {@code ReviewMediaService.uploadMedia} adquire
 * {@link ReviewJpaRepository#findByIdForUpdate} na review antes de enviar o objeto ao storage e só
 * o libera no commit da linha em review_media. Como toda chave contém o reviewId e cada upload gera
 * um mediaId novo, uma transação que detém esse mesmo lock e não encontra referência sabe que não há
 * upload em andamento para a chave. Review inexistente dispensa o lock: nenhum upload consegue
 * criar referência para ela (o upload exige a linha e a FK de review_media também).
 * Qualquer etapa futura de remoção física deve repetir esta consulta e remover o objeto
 * dentro da mesma transação que detém este lock.
 */
@Component
public class StorageQuarantineRepositoryAdapter implements StorageQuarantineRepository {

    private final StorageObjectQuarantineJpaRepository quarantineJpaRepository;
    private final ReviewMediaJpaRepository reviewMediaJpaRepository;
    private final ReviewJpaRepository reviewJpaRepository;

    public StorageQuarantineRepositoryAdapter(StorageObjectQuarantineJpaRepository quarantineJpaRepository,
                                              ReviewMediaJpaRepository reviewMediaJpaRepository,
                                              ReviewJpaRepository reviewJpaRepository) {
        this.quarantineJpaRepository = Objects.requireNonNull(quarantineJpaRepository, "quarantineJpaRepository must not be null");
        this.reviewMediaJpaRepository = Objects.requireNonNull(reviewMediaJpaRepository, "reviewMediaJpaRepository must not be null");
        this.reviewJpaRepository = Objects.requireNonNull(reviewJpaRepository, "reviewJpaRepository must not be null");
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
    @Transactional
    public QuarantineResolution resolveUnderCreationLock(String objectKey, UUID reviewId,
                                                         Instant expectedFirstObservedAt, Instant now) {
        Objects.requireNonNull(objectKey, "objectKey must not be null");
        Objects.requireNonNull(reviewId, "reviewId must not be null");
        Objects.requireNonNull(expectedFirstObservedAt, "expectedFirstObservedAt must not be null");
        Objects.requireNonNull(now, "now must not be null");

        // 1. Mesmo lock do upload; ordem fixa (review → quarentena) em todos os caminhos que travam ambos
        reviewJpaRepository.findByIdForUpdate(reviewId);

        // 2. A decisão vale somente para a observação avaliada pelo chamador
        Optional<UUID> entryId = quarantineJpaRepository.lockEntry(objectKey, expectedFirstObservedAt);
        if (entryId.isEmpty()) {
            return new QuarantineResolution(QuarantineResolution.Kind.ENTRY_CHANGED, null);
        }

        // 3. Segunda consulta: qualquer linha em review_media, inclusive REMOVED, é referência
        List<ReviewMediaJpaEntity> references = reviewMediaJpaRepository.findByObjectKeyIn(List.of(objectKey));
        if (!references.isEmpty()) {
            quarantineJpaRepository.releaseEntry(entryId.get());
            return new QuarantineResolution(QuarantineResolution.Kind.REFERENCE_FOUND,
                    ReviewMediaStatus.valueOf(references.get(0).getStatus()));
        }

        quarantineJpaRepository.markConfirmedOrphan(entryId.get(), now);
        return new QuarantineResolution(QuarantineResolution.Kind.CONFIRMED_ORPHAN, null);
    }
}
