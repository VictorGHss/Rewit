package com.rewit.application.port;

import java.time.Duration;

/**
 * Porta de aplicação para os nonces de uso único do login social. Guarda apenas o hash do nonce, com expiração.
 */
public interface SocialLoginNonceStore {

    /**
     * Registra o hash de um nonce recém-emitido.
     *
     * @throws com.rewit.common.exception.BusinessException 503 {@code SOCIAL_LOGIN_UNAVAILABLE} se o armazenamento
     *         estiver indisponível
     */
    void store(String nonceHash, Duration ttl);

    /**
     * Consome o nonce de forma atômica: devolve {@code true} uma única vez, para um hash registrado e ainda válido.
     *
     * @throws com.rewit.common.exception.BusinessException 503 {@code SOCIAL_LOGIN_UNAVAILABLE} se o armazenamento
     *         estiver indisponível (nunca concede o nonce sem confirmação)
     */
    boolean consume(String nonceHash);
}
