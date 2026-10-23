package com.rewit.domain.enums;

/**
 * Status do ciclo de vida de uma fotografia vinculada a um local (Place).
 * Conforme migration V31: CHECK (status IN ('ACTIVE', 'REMOVED', 'UNDER_REVIEW')).
 */
public enum PlaceMediaStatus {
    ACTIVE,
    UNDER_REVIEW,
    REMOVED
}
