package com.rewit.domain.enums;

/**
 * Estado de um objeto de storage em quarentena (Step 28.3).
 * Nenhum dos estados autoriza remoção física.
 */
public enum StorageQuarantineStatus {
    /** Observado sem referência em review_media; aguardando grace period e rechecagem. */
    OBSERVED,
    /** Rechecagem sob o lock de criação confirmou ausência de referência. */
    CONFIRMED_ORPHAN
}
