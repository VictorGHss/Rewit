package com.rewit.application.storage;

import java.time.Instant;

/**
 * Política de grace period da quarentena de storage (Step 28.3): responde se um objeto observado
 * sem referência já pode ser rechecado. Contado a partir da primeira observação persistida, nunca
 * de timestamps de review_media, pois o objeto pode não ter referência alguma.
 */
public interface StorageQuarantineGracePolicy {

    /**
     * @return primeiro instante em que a rechecagem é permitida
     */
    Instant eligibleAt(Instant firstObservedAt);

    default boolean hasElapsed(Instant firstObservedAt, Instant now) {
        return !now.isBefore(eligibleAt(firstObservedAt));
    }
}
