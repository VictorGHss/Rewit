package com.rewit.application.query;

/**
 * Escopo de consulta para reviews.
 * Este enum decide quais avaliações entram no conjunto elegível para leitura, sem substituir a regra
 * de autorização final em ReviewVisibilityPolicy.
 */
public enum ReviewVisibilityScope {
    ONLY_PUBLIC,
    FOLLOWERS,
    PRIVATE,
    ANY;

    public boolean requiresRequester() {
        return this == FOLLOWERS || this == PRIVATE;
    }
}
