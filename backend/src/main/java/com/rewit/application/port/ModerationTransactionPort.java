package com.rewit.application.port;

import java.util.function.Supplier;

/**
 * Porta de aplicação para execução transacional explícita sob a role administrativa {@code rewit_mod}
 * (S3-A13-F2), preservando o isolamento de fronteiras da Clean Architecture na camada {@code application}.
 */
public interface ModerationTransactionPort {

    <T> T execute(Supplier<T> action);

    void run(Runnable action);
}
