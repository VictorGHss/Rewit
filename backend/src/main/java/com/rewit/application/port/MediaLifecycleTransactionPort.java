package com.rewit.application.port;

import java.util.function.Supplier;

/**
 * Porta de aplicação para executar blocos transacionais sob a identidade dedicada de ciclo de vida
 * de mídias ({@code rewit_media}, SEC-F2-03), garantindo que o contexto de role seja ativado antes
 * da abertura da transação física.
 */
public interface MediaLifecycleTransactionPort {

    <T> T execute(Supplier<T> action);

    void run(Runnable action);
}
