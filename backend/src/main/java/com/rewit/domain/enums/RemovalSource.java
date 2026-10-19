package com.rewit.domain.enums;

/**
 * Origem da remoção lógica de uma fotografia de local (PlaceMedia).
 * Distingue remoção realizada pelo próprio autor da foto versus intervenção administrativa por moderação.
 */
public enum RemovalSource {
    AUTHOR,
    MODERATOR
}

