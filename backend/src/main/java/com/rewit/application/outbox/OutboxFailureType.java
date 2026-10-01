package com.rewit.application.outbox;

/**
 * Classificação explícita do resultado de uma falha de handler do Outbox (Step 27.2).
 */
public enum OutboxFailureType {

    /**
     * Condição de ambiente/infraestrutura: retry com backoff enquanto houver
     * tentativas disponíveis; ao esgotar o teto, a mensagem vai a FAILED.
     */
    TRANSIENT,

    /**
     * Rejeição determinística: FAILED imediato, sem retry — não retestar
     * indefinidamente um erro de negócio permanente.
     */
    PERMANENT
}
