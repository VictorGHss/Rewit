package com.rewit.domain.enums;

/**
 * Por que a moderação não restauraria agora uma resposta oficial removida (C11). A lista vazia é a única restaurável;
 * a restauração revalida tudo com a resposta travada.
 */
public enum BusinessResponseRestorationBlocker {
    /** Removida pela própria empresa: só a remoção da moderação é desfeita por ela. */
    REMOVED_BY_BUSINESS,
    /** A empresa tem outra resposta ativa no mesmo alvo, mesmo que oculta. */
    OTHER_ACTIVE_RESPONSE,
    ACCOUNT_NOT_APPROVED,
    ADMINISTRATOR_NOT_OPERATIONAL,
    PLACE_NOT_REPRESENTED;

    public static BusinessResponseRestorationBlocker of(BusinessRepresentationIneligibility reason) {
        return switch (reason) {
            case ACCOUNT_NOT_APPROVED -> ACCOUNT_NOT_APPROVED;
            case ADMINISTRATOR_NOT_OPERATIONAL -> ADMINISTRATOR_NOT_OPERATIONAL;
            case PLACE_NOT_REPRESENTED -> PLACE_NOT_REPRESENTED;
        };
    }
}
