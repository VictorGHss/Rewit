package com.rewit.domain.enums;

/**
 * Estados do ciclo de vida de uma discussão/comentário de avaliação no Rewit.
 */
public enum DiscussionStatus {
    ACTIVE,         // Comentário publicado e visível publicamente
    UNDER_REVIEW,   // Suspenso preventivamente para análise de moderação
    REMOVED         // Excluído logicamente pelo autor ou por moderação
}
