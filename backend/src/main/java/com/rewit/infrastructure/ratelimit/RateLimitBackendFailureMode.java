package com.rewit.infrastructure.ratelimit;

/**
 * Decisão do rate limiting enquanto o Redis está indisponível ({@code rewit.rate-limit.backend.failure-mode}).
 */
public enum RateLimitBackendFailureMode {

    /**
     * Padrão. Aplica os mesmos limites em memória, por instância: com N instâncias o limite efetivo fica em
     * até N vezes o configurado, mas nenhuma ação deixa de ser limitada e nenhuma deixa de funcionar.
     */
    LOCAL_FALLBACK,

    /** Nega todas as tentativas: o Redis passa a ser dependência de disponibilidade das ações limitadas. */
    DENY,

    /** Concede todas as tentativas: desliga a proteção durante a indisponibilidade. */
    ALLOW
}
