package com.rewit.application.port;

import java.util.UUID;

/**
 * Porta de saída da aplicação para hashing e verificação segura de senhas vinculadas à conta (UUID).
 * Utiliza derivação com HMAC (pepper externo) e Argon2id.
 */
public interface PasswordHasher {

    /**
     * Calcula o hash da senha vinculando-a criptograficamente ao identificador único do usuário (UUID).
     */
    String hash(UUID userId, String rawPassword);

    /**
     * Verifica a senha contra o hash codificado, assegurando o contexto do usuário.
     */
    boolean matches(UUID userId, String rawPassword, String encodedPassword);

    /**
     * Indica se o hash fornecido necessita de atualização (ex.: formato legado selado ou chave rotacionada).
     */
    boolean needsUpgrade(String encodedPassword);

    /**
     * Executa uma verificação simulada com custo temporal e computacional idêntico a {@link #matches},
     * sem expor se a conta existe.
     */
    void simulateVerification(String rawPassword);
}
