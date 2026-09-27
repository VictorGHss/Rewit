package com.rewit.application.port;

/**
 * Porta de saída da aplicação para hashing e verificação segura de senhas.
 * Mantém os casos de uso independentes do algoritmo criptográfico específico (Argon2, etc).
 */
public interface PasswordHasher {

    String hash(String rawPassword);

    boolean matches(String rawPassword, String encodedPassword);
}
