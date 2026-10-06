package com.rewit.application.port;

/**
 * Porta de saída da aplicação para hashing e verificação segura de senhas.
 * Mantém os casos de uso independentes do algoritmo criptográfico específico (Argon2, etc).
 */
public interface PasswordHasher {

    String hash(String rawPassword);

    boolean matches(String rawPassword, String encodedPassword);

    /**
     * Executa uma verificação com o mesmo custo de {@link #matches} contra um hash descartável, sem resultado.
     * Usada quando não há hash a verificar (conta inexistente, desativada ou federada), para que o tempo de
     * resposta não revele se a conta existe.
     */
    void simulateVerification(String rawPassword);
}
