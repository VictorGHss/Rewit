package com.rewit.application.usecase;

import com.rewit.application.dto.storage.StorageQuarantineDtos.QuarantinePurgeResolution;
import com.rewit.application.dto.storage.StorageQuarantineDtos.QuarantinedStorageObject;
import com.rewit.application.dto.storage.StorageQuarantineDtos.StoragePurgeOutcome;
import com.rewit.application.dto.storage.StorageQuarantineDtos.StoragePurgeResult;
import com.rewit.application.port.ObjectStorageDeletionPort;
import com.rewit.application.port.StorageQuarantineRepository;
import com.rewit.domain.enums.StorageQuarantineStatus;
import com.rewit.domain.model.ReviewMediaObjectKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Exclusão física segura de um objeto CONFIRMED_ORPHAN (Step 28.4).
 *
 * <p>CONFIRMED_ORPHAN torna o objeto elegível, mas não dispensa nova verificação: a exclusão repete a
 * consulta a review_media sob o lock de criação de mídia (o mesmo do upload e da rechecagem) e só então
 * chama o storage. A ordem é lock → nova consulta → exclusão no storage → remoção da quarentena → commit.
 * Storage e PostgreSQL não formam transação distribuída; a ordem garante que uma falha em qualquer ponto
 * deixe a quarentena persistida para nova tentativa, e NOT_FOUND do storage conta como sucesso.
 *
 * <p>Recebe apenas {@link ObjectStorageDeletionPort}: sem gravação nem leitura de objetos, e sempre no
 * bucket configurado. Livre de Spring, sem agendamento nem exposição HTTP.
 */
public class PurgeConfirmedOrphanStorageObjectUseCase {

    private static final Logger log = LoggerFactory.getLogger(PurgeConfirmedOrphanStorageObjectUseCase.class);

    private final StorageQuarantineRepository quarantineRepository;
    private final ObjectStorageDeletionPort deletionPort;

    public PurgeConfirmedOrphanStorageObjectUseCase(StorageQuarantineRepository quarantineRepository,
                                                    ObjectStorageDeletionPort deletionPort) {
        this.quarantineRepository = Objects.requireNonNull(quarantineRepository, "quarantineRepository must not be null");
        this.deletionPort = Objects.requireNonNull(deletionPort, "deletionPort must not be null");
    }

    /**
     * @param objectKey chave de um objeto em quarentena; chaves fora do formato gerenciado nunca chegam ao storage
     */
    public StoragePurgeResult purge(String objectKey) {
        long startedAt = System.nanoTime();

        // Validação refeita aqui: o reviewId do lock só é derivado de uma chave no formato gerenciado
        if (!ReviewMediaObjectKey.isManagedKey(objectKey)) {
            return finish(new StoragePurgeResult(objectKey, StoragePurgeOutcome.INVALID_OBJECT_KEY, null, null), startedAt);
        }

        Optional<QuarantinedStorageObject> quarantined = quarantineRepository.findByObjectKey(objectKey);
        if (quarantined.isEmpty()) {
            return finish(new StoragePurgeResult(objectKey, StoragePurgeOutcome.NOT_QUARANTINED, null, null), startedAt);
        }
        if (quarantined.get().status() != StorageQuarantineStatus.CONFIRMED_ORPHAN) {
            return finish(new StoragePurgeResult(objectKey, StoragePurgeOutcome.NOT_CONFIRMED, null, null), startedAt);
        }

        QuarantinePurgeResolution resolution = quarantineRepository.purgeConfirmedOrphanUnderCreationLock(
                objectKey,
                ReviewMediaObjectKey.reviewIdOf(objectKey),
                quarantined.get().firstObservedAt(),
                key -> deletionPort.delete(key));

        StoragePurgeResult result = switch (resolution.kind()) {
            case ENTRY_CHANGED -> new StoragePurgeResult(objectKey, StoragePurgeOutcome.NOT_QUARANTINED, null, null);
            case REFERENCE_FOUND -> new StoragePurgeResult(objectKey, StoragePurgeOutcome.WITH_REFERENCE,
                    null, resolution.referenceStatus());
            case STORAGE_ATTEMPTED -> new StoragePurgeResult(objectKey, outcomeOf(resolution), resolution.deletionResult(), null);
        };
        return finish(result, startedAt);
    }

    private static StoragePurgeOutcome outcomeOf(QuarantinePurgeResolution resolution) {
        return switch (resolution.deletionResult()) {
            case DELETED, NOT_FOUND -> StoragePurgeOutcome.PURGED;
            case TRANSIENT_FAILURE -> StoragePurgeOutcome.RETRYABLE_FAILURE;
            case PERMANENT_FAILURE -> StoragePurgeOutcome.PERMANENT_FAILURE;
        };
    }

    // Log sem object_key: desfecho, resultado do storage, status da referência e duração
    private static StoragePurgeResult finish(StoragePurgeResult result, long startedAt) {
        long durationMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
        if (result.outcome() == StoragePurgeOutcome.RETRYABLE_FAILURE || result.outcome() == StoragePurgeOutcome.PERMANENT_FAILURE) {
            log.warn("Exclusão de órfão de storage: desfecho={}, resultadoStorage={}, duracaoMs={}",
                    result.outcome(), result.deletionResult(), durationMs);
        } else {
            log.info("Exclusão de órfão de storage: desfecho={}, resultadoStorage={}, statusReferencia={}, duracaoMs={}",
                    result.outcome(), result.deletionResult(), result.referenceStatus(), durationMs);
        }
        return result;
    }
}
