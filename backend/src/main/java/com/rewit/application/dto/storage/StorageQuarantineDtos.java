package com.rewit.application.dto.storage;

import com.rewit.domain.enums.ReviewMediaStatus;
import com.rewit.domain.enums.StorageQuarantineStatus;

import java.time.Instant;
import java.util.Objects;

/**
 * Contratos da quarentena persistente e da rechecagem de objetos de storage (Step 28.3).
 *
 * <p>Uso exclusivamente interno, como {@link StorageReconciliationDtos}. Nenhum resultado daqui
 * autoriza remoção física: isso pertence a uma etapa posterior.
 */
public final class StorageQuarantineDtos {

    private StorageQuarantineDtos() {}

    /**
     * Linha de quarentena: a observação de um objeto sem referência, não uma cópia de review_media.
     *
     * @param firstObservedAt primeira observação persistida; início do grace period
     * @param lastObservedAt  observação mais recente
     * @param lastModifiedAt  última modificação informada pelo storage; pode ser null
     * @param confirmedAt     instante da rechecagem que confirmou a ausência de referência; null se OBSERVED
     */
    public record QuarantinedStorageObject(
            String objectKey,
            StorageQuarantineStatus status,
            Instant firstObservedAt,
            Instant lastObservedAt,
            Instant lastModifiedAt,
            Instant confirmedAt
    ) {}

    /**
     * Resultado da transição atômica executada sob o lock de criação de mídia.
     *
     * @param referenceStatus status da referência encontrada; null quando não há referência
     */
    public record QuarantineResolution(
            Kind kind,
            ReviewMediaStatus referenceStatus
    ) {
        public enum Kind {
            /** A linha avaliada não existe mais ou foi recriada com outra primeira observação; nada mudou. */
            ENTRY_CHANGED,
            /** Existe linha em review_media (qualquer status): a quarentena foi liberada. */
            REFERENCE_FOUND,
            /** Nenhuma referência sob o lock: a quarentena foi marcada CONFIRMED_ORPHAN. */
            CONFIRMED_ORPHAN
        }

        public QuarantineResolution {
            Objects.requireNonNull(kind, "kind must not be null");
        }
    }

    /**
     * Resultado da exclusão de um CONFIRMED_ORPHAN executada sob o lock de criação de mídia (Step 28.4).
     *
     * @param referenceStatus status da referência encontrada; somente em REFERENCE_FOUND
     * @param deletionResult  resultado do storage; somente em STORAGE_ATTEMPTED
     */
    public record QuarantinePurgeResolution(
            Kind kind,
            ReviewMediaStatus referenceStatus,
            ObjectDeletionResult deletionResult
    ) {
        public enum Kind {
            /** Não há linha CONFIRMED_ORPHAN correspondente à observação avaliada; storage intocado. */
            ENTRY_CHANGED,
            /** Existe linha em review_media (qualquer status): storage intocado, quarentena liberada. */
            REFERENCE_FOUND,
            /** Sem referência sob o lock: a exclusão física foi tentada; a quarentena só sai se o objeto ficou ausente. */
            STORAGE_ATTEMPTED
        }

        public QuarantinePurgeResolution {
            Objects.requireNonNull(kind, "kind must not be null");
        }
    }

    /** Desfecho da exclusão física segura de um objeto em quarentena (Step 28.4). */
    public enum StoragePurgeOutcome {
        /** Chave fora do formato gerenciado: nada consultado nem removido. */
        INVALID_OBJECT_KEY,
        /** Não há quarentena (ou ela mudou desde a leitura); storage intocado. */
        NOT_QUARANTINED,
        /** A quarentena existe mas não está CONFIRMED_ORPHAN; storage intocado. */
        NOT_CONFIRMED,
        /** A consulta sob o lock encontrou review_media ACTIVE ou REMOVED; storage intocado, quarentena liberada. */
        WITH_REFERENCE,
        /** O objeto foi removido (DELETED) ou já estava ausente (NOT_FOUND); quarentena encerrada. */
        PURGED,
        /** Falha transitória do storage; quarentena mantida como CONFIRMED_ORPHAN para nova tentativa. */
        RETRYABLE_FAILURE,
        /** Falha permanente do storage; quarentena mantida para investigação. */
        PERMANENT_FAILURE
    }

    /**
     * @param deletionResult  resultado do storage quando a exclusão foi tentada; null caso contrário
     * @param referenceStatus status da referência encontrada; somente em WITH_REFERENCE
     */
    public record StoragePurgeResult(
            String objectKey,
            StoragePurgeOutcome outcome,
            ObjectDeletionResult deletionResult,
            ReviewMediaStatus referenceStatus
    ) {}

    /** Desfecho da rechecagem de um objeto em quarentena. */
    public enum QuarantineRecheckOutcome {
        /** Não há quarentena correspondente à observação avaliada; nada a rechecar. */
        NOT_QUARANTINED,
        /** O grace period, contado de firstObservedAt, ainda não terminou; o banco não foi consultado. */
        GRACE_PERIOD_NOT_ELAPSED,
        /** A segunda consulta encontrou referência em review_media (ACTIVE ou REMOVED); quarentena liberada. */
        WITH_REFERENCE,
        /** A segunda consulta, sob o lock de criação, confirmou ausência de referência. Não autoriza delete. */
        CONFIRMED_ORPHAN
    }

    /**
     * @param firstObservedAt início do grace period avaliado; null se NOT_QUARANTINED
     * @param eligibleAt      primeiro instante em que a rechecagem é permitida; null se NOT_QUARANTINED
     * @param referenceStatus status da referência encontrada; preenchido somente em WITH_REFERENCE
     * @param checkedAt       instante de referência da rechecagem
     */
    public record QuarantineRecheckResult(
            String objectKey,
            QuarantineRecheckOutcome outcome,
            Instant firstObservedAt,
            Instant eligibleAt,
            ReviewMediaStatus referenceStatus,
            Instant checkedAt
    ) {}
}
