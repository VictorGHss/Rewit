package com.rewit.infrastructure.security;

import com.rewit.application.port.PasswordHasher;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.UUID;

/**
 * Adaptador de infraestrutura para hashing e validação de senhas com Argon2id.
 * Utiliza a implementação oficial do Spring Security baseada em BouncyCastle.
 * Parâmetros ajustados para robustez criptográfica e eficiência em ambiente com ~4GB de RAM:
 * - saltLength: 16 bytes (128 bits)
 * - hashLength: 32 bytes (256 bits)
 * - parallelism: 1 thread
 * - memory: 19456 KiB (19 MiB)
 * - iterations: 2 iterações
 */
@Component
public class Argon2PasswordHasher implements PasswordHasher {

    private final PasswordEncoder delegate;

    // Hash de uma senha aleatória descartada, com os mesmos parâmetros do encoder: verificar contra ele custa o
    // mesmo que verificar uma senha real
    private final String dummyHash;

    public Argon2PasswordHasher() {
        this(new Argon2PasswordEncoder(16, 32, 1, 19456, 2));
    }

    public Argon2PasswordHasher(PasswordEncoder delegate) {
        this.delegate = Objects.requireNonNull(delegate, "PasswordEncoder delegate must not be null");
        this.dummyHash = delegate.encode(UUID.randomUUID().toString());
    }

    @Override
    public String hash(String rawPassword) {
        if (rawPassword == null || rawPassword.isBlank()) {
            throw new IllegalArgumentException("A senha para hashing não pode ser nula ou em branco");
        }
        return delegate.encode(rawPassword);
    }

    @Override
    public boolean matches(String rawPassword, String encodedPassword) {
        if (rawPassword == null || encodedPassword == null) {
            return false;
        }
        return delegate.matches(rawPassword, encodedPassword);
    }

    @Override
    public void simulateVerification(String rawPassword) {
        delegate.matches(rawPassword != null ? rawPassword : "", dummyHash);
    }

    public PasswordEncoder getDelegate() {
        return delegate;
    }
}
