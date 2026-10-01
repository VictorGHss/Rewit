package com.rewit.application.outbox;

/**
 * Falha transitória de processamento do Outbox (Step 27.2): condições de
 * ambiente/infraestrutura que podem mudar entre tentativas (timeout de rede,
 * indisponibilidade temporária de provider). Elegível a retry com backoff,
 * limitado pelo teto de tentativas da política de retry.
 */
public final class OutboxTransientException extends RuntimeException {

    public OutboxTransientException(String message) {
        super(message);
    }

    public OutboxTransientException(String message, Throwable cause) {
        super(message, cause);
    }
}
