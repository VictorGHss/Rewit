package com.rewit.application.outbox;

/**
 * Falha permanente de processamento do Outbox (Step 27.2): rejeição determinística
 * que não mudará entre tentativas (payload inválido, destino inexistente, violação
 * de regra declarada pelo handler). Levando a FAILED imediato, sem retry.
 */
public final class OutboxPermanentException extends RuntimeException {

    public OutboxPermanentException(String message) {
        super(message);
    }

    public OutboxPermanentException(String message, Throwable cause) {
        super(message, cause);
    }
}
