package com.rewit.infrastructure.ratelimit;

/**
 * Falha de comunicação com o armazenamento distribuído do rate limiting. A causa fica disponível para
 * diagnóstico, mas só a classe dela é registrada em log.
 */
class RateLimitBackendException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    RateLimitBackendException(Throwable cause) {
        super("Armazenamento do rate limiting indisponível", cause);
    }
}
