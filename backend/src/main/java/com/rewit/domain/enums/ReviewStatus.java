package com.rewit.domain.enums;

/**
 * Estados do ciclo de vida de uma publicação de avaliação no Rewit.
 */
public enum ReviewStatus {
    ACTIVE,         // Visível normalmente no feed
    UNDER_REVIEW,   // Denunciada aguardando moderação
    REMOVED         // Excluída por violação grave de diretrizes
}
