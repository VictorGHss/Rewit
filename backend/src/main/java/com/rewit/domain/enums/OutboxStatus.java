package com.rewit.domain.enums;

/**
 * Estados do ciclo de vida de uma mensagem do Outbox transacional (Step 27.1).
 *
 * <ul>
 *   <li>{@link #PENDING} — mensagem enfileirada, elegível para claim;</li>
 *   <li>{@link #PROCESSING} — reivindicada por um worker via claim (lock registrado
 *       em locked_at/locked_by);</li>
 *   <li>{@link #COMPLETED} — processada com sucesso (estado terminal);</li>
 *   <li>{@link #FAILED} — esgotou as tentativas ou falhou permanentemente
 *       (estado terminal neste foundation; requeue administrativo é futuro).</li>
 * </ul>
 */
public enum OutboxStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED
}
