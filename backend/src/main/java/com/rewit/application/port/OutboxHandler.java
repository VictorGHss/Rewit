package com.rewit.application.port;

import com.rewit.domain.model.OutboxMessage;

/**
 * Contrato de processamento de uma mensagem do Transactional Outbox (Step 27.2).
 *
 * <p>Abstração exclusiva da camada application/domínio: implementações não podem
 * depender de Spring Scheduling, JPA, {@code EntityManager}, controllers ou
 * {@code Authentication}.
 *
 * <p>O dispatcher invoca {@code handle} FORA de qualquer transação PostgreSQL.
 * A entrega é at-least-once: implementações devem ser idempotentes. Toda falha
 * deve ser sinalizada por exceção — preferencialmente {@code OutboxTransientException}
 * ou {@code OutboxPermanentException} para classificação explícita.
 */
public interface OutboxHandler {

    /**
     * Indica se este handler processa o tipo de mensagem informado.
     *
     * @param messageType tipo/roteamento da mensagem (ex.: {@code PUSH_DELIVERY})
     * @return true quando este handler sabe processar o tipo
     */
    boolean supports(String messageType);

    /**
     * Executa o efeito externo da mensagem.
     *
     * @param message mensagem reivindicada pelo dispatcher, já em PROCESSING
     * @throws Exception qualquer falha; a classificação transient/permanent
     *                   decide entre retry com backoff e FAILED imediato
     */
    void handle(OutboxMessage message);
}
