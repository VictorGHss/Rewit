package com.rewit.domain.enums;

/**
 * Status do ciclo de vida de uma mídia de avaliação.
 * Conforme migration V9: CHECK (status IN ('ACTIVE', 'REMOVED')).
 */
public enum ReviewMediaStatus {
    ACTIVE,
    REMOVED
}
