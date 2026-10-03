package com.rewit.application.usecase;

import com.rewit.application.dto.storage.StorageQuarantineDtos.QuarantineRecheckOutcome;
import com.rewit.application.dto.storage.StorageQuarantineDtos.QuarantineRecheckResult;
import com.rewit.application.dto.storage.StorageQuarantineDtos.QuarantineResolution;
import com.rewit.application.dto.storage.StorageQuarantineDtos.QuarantinedStorageObject;
import com.rewit.application.port.StorageQuarantineRepository;
import com.rewit.application.storage.StorageQuarantineGracePolicy;
import com.rewit.domain.model.ReviewMediaObjectKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Rechecagem de um objeto em quarentena (Step 28.3). Separa três perguntas e responde só a elas:
 * <ol>
 *   <li>o grace period, contado da primeira observação persistida, já terminou?</li>
 *   <li>existe referência em review_media (ACTIVE ou REMOVED)?</li>
 *   <li>a segunda consulta, sob o lock de criação de mídia, confirmou a ausência de referência?</li>
 * </ol>
 * Se o objeto pode ser fisicamente removido é uma quarta pergunta, fora deste use case:
 * {@link QuarantineRecheckOutcome#CONFIRMED_ORPHAN} não autoriza delete.
 *
 * <p>Depende apenas da quarentena e da política; não tem acesso ao storage. Livre de Spring,
 * sem agendamento nem exposição HTTP.
 */
public class RecheckQuarantinedStorageObjectUseCase {

    private static final Logger log = LoggerFactory.getLogger(RecheckQuarantinedStorageObjectUseCase.class);

    private final StorageQuarantineRepository quarantineRepository;
    private final StorageQuarantineGracePolicy gracePolicy;

    public RecheckQuarantinedStorageObjectUseCase(StorageQuarantineRepository quarantineRepository,
                                                  StorageQuarantineGracePolicy gracePolicy) {
        this.quarantineRepository = Objects.requireNonNull(quarantineRepository, "quarantineRepository must not be null");
        this.gracePolicy = Objects.requireNonNull(gracePolicy, "gracePolicy must not be null");
    }

    /**
     * @param objectKey chave gerenciada em quarentena; chaves fora do formato são rejeitadas
     * @param now       instante de referência da rechecagem
     */
    public QuarantineRecheckResult recheck(String objectKey, Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        if (!ReviewMediaObjectKey.isManagedKey(objectKey)) {
            throw new IllegalArgumentException("objectKey must be a managed review media key");
        }
        long startedAt = System.nanoTime();

        Optional<QuarantinedStorageObject> quarantined = quarantineRepository.findByObjectKey(objectKey);
        if (quarantined.isEmpty()) {
            return finish(new QuarantineRecheckResult(objectKey, QuarantineRecheckOutcome.NOT_QUARANTINED,
                    null, null, null, now), startedAt);
        }

        Instant firstObservedAt = quarantined.get().firstObservedAt();
        Instant eligibleAt = gracePolicy.eligibleAt(firstObservedAt);
        if (!gracePolicy.hasElapsed(firstObservedAt, now)) {
            return finish(new QuarantineRecheckResult(objectKey, QuarantineRecheckOutcome.GRACE_PERIOD_NOT_ELAPSED,
                    firstObservedAt, eligibleAt, null, now), startedAt);
        }

        QuarantineResolution resolution = quarantineRepository.resolveUnderCreationLock(
                objectKey, ReviewMediaObjectKey.reviewIdOf(objectKey), firstObservedAt, now);

        QuarantineRecheckResult result = switch (resolution.kind()) {
            case ENTRY_CHANGED -> new QuarantineRecheckResult(objectKey, QuarantineRecheckOutcome.NOT_QUARANTINED,
                    null, null, null, now);
            case REFERENCE_FOUND -> new QuarantineRecheckResult(objectKey, QuarantineRecheckOutcome.WITH_REFERENCE,
                    firstObservedAt, eligibleAt, resolution.referenceStatus(), now);
            case CONFIRMED_ORPHAN -> new QuarantineRecheckResult(objectKey, QuarantineRecheckOutcome.CONFIRMED_ORPHAN,
                    firstObservedAt, eligibleAt, null, now);
        };
        return finish(result, startedAt);
    }

    // Log sem object_key: somente desfecho e duração
    private static QuarantineRecheckResult finish(QuarantineRecheckResult result, long startedAt) {
        log.info("Rechecagem de quarentena de storage: desfecho={}, statusReferencia={}, duracaoMs={}",
                result.outcome(), result.referenceStatus(), Duration.ofNanos(System.nanoTime() - startedAt).toMillis());
        return result;
    }
}
