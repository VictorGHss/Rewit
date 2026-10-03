package com.rewit.application.port;

import com.rewit.application.dto.storage.ObjectDeletionResult;
import com.rewit.application.dto.storage.StorageQuarantineDtos.QuarantinePurgeResolution;
import com.rewit.application.dto.storage.StorageQuarantineDtos.QuarantineResolution;
import com.rewit.application.dto.storage.StorageQuarantineDtos.QuarantinedStorageObject;
import com.rewit.application.dto.storage.StorageReconciliationDtos.OrphanCandidate;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Porta de persistência da quarentena de objetos de storage sem referência (Step 28.3).
 *
 * <p>Não dá acesso ao storage. A única operação que pode resultar em remoção física é
 * {@link #purgeConfirmedOrphanUnderCreationLock}, e somente pela função recebida do chamador.
 */
public interface StorageQuarantineRepository {

    /**
     * Registra observações de candidatos, uma linha por object_key (garantido por constraint única).
     * Chave nova: first_observed_at = last_observed_at = observedAt. Chave conhecida: preserva
     * first_observed_at, avança last_observed_at (nunca retrocede) e atualiza last_modified_at
     * quando o storage o informar.
     */
    void recordObservations(Collection<OrphanCandidate> candidates);

    Optional<QuarantinedStorageObject> findByObjectKey(String objectKey);

    /**
     * Segunda consulta a review_media, atômica e sob o mesmo lock de linha em {@code reviews} que o
     * upload de mídia mantém do envio ao storage até o commit da referência:
     * <ol>
     *   <li>adquire o lock de criação da review (se ela existir);</li>
     *   <li>trava a linha de quarentena, exigindo o {@code expectedFirstObservedAt} avaliado pelo chamador;</li>
     *   <li>havendo referência em qualquer status, libera a quarentena; senão, marca CONFIRMED_ORPHAN.</li>
     * </ol>
     *
     * @param reviewId review extraída da chave, dona do lock de criação
     */
    QuarantineResolution resolveUnderCreationLock(String objectKey, UUID reviewId,
                                                  Instant expectedFirstObservedAt, Instant now);

    /**
     * Exclusão física de um CONFIRMED_ORPHAN, numa transação PostgreSQL que mantém os locks durante a
     * chamada externa ao storage. Ordem obrigatória:
     * <ol>
     *   <li>lock de criação da review (o mesmo de {@link #resolveUnderCreationLock});</li>
     *   <li>lock da linha de quarentena, exigindo CONFIRMED_ORPHAN e o {@code expectedFirstObservedAt};</li>
     *   <li>nova consulta a review_media: havendo referência (qualquer status), libera a quarentena sem tocar o storage;</li>
     *   <li>sem referência, invoca {@code physicalDeletion};</li>
     *   <li>somente se o objeto ficou ausente (DELETED ou NOT_FOUND), remove a linha de quarentena;</li>
     *   <li>commit.</li>
     * </ol>
     * Storage e PostgreSQL não formam transação distribuída: rollback não desfaz a exclusão física. Se a
     * transação falhar após o storage, a linha permanece e a próxima execução obtém NOT_FOUND.
     */
    QuarantinePurgeResolution purgeConfirmedOrphanUnderCreationLock(String objectKey, UUID reviewId,
                                                                    Instant expectedFirstObservedAt,
                                                                    Function<String, ObjectDeletionResult> physicalDeletion);
}
