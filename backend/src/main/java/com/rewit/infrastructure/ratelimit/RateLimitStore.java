package com.rewit.infrastructure.ratelimit;

import java.time.Duration;
import java.util.Optional;

/**
 * Armazenamento da janela deslizante de uma chave. Verificar o limite e registrar a tentativa é uma única
 * operação atômica por chave.
 */
interface RateLimitStore {

    /**
     * Remove as tentativas mais antigas que a janela e, se restarem menos de {@code limit}, registra uma nova.
     *
     * @return identificador da tentativa registrada, ou vazio se o limite foi atingido
     * @throws RateLimitBackendException se o armazenamento não respondeu
     */
    Optional<String> tryAcquire(String key, int limit, Duration window);

    /**
     * Remove uma tentativa registrada. Sem efeito se ela já saiu da janela.
     *
     * @throws RateLimitBackendException se o armazenamento não respondeu
     */
    void release(String key, String member);
}
