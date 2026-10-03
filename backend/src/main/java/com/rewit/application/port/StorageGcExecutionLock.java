package com.rewit.application.port;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Exclusão mútua global do ciclo de GC de storage entre instâncias (Step 28.5).
 * Não envolve linhas de domínio: nunca bloqueia uploads ou operações de usuário.
 */
public interface StorageGcExecutionLock {

    /**
     * Executa {@code cycle} somente se o lock global estiver livre, sem esperar por ele.
     *
     * @return o resultado do ciclo, ou vazio se outra execução detém o lock
     */
    <T> Optional<T> runExclusively(Supplier<T> cycle);
}
