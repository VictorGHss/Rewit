package com.rewit.domain.enums;

/**
 * Origem da remoção lógica de uma fotografia de local (PlaceMedia).
 * Distingue remoção realizada pelo próprio autor da foto, pelo proprietário do estabelecimento
 * ou por intervenção administrativa por moderação.
 */
public enum RemovalSource {
    AUTHOR,
    BUSINESS_OWNER,
    MODERATOR
}
