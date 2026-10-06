package com.rewit.application.ratelimit;

import java.util.Objects;
import java.util.UUID;

/**
 * Sujeito contado por um limite: um usuário autenticado, uma identidade de login (e-mail normalizado) ou o
 * contador global de uma ação. O valor pode ser dado pessoal: o adaptador só o usa derivado por HMAC e nunca o
 * persiste nem o registra em claro. {@link #toString()} não expõe o valor.
 */
public final class RateLimitSubject {

    private static final RateLimitSubject GLOBAL = new RateLimitSubject("global");

    private final String value;

    private RateLimitSubject(String value) {
        this.value = value;
    }

    public static RateLimitSubject ofUser(UUID userId) {
        Objects.requireNonNull(userId, "userId must not be null");
        return new RateLimitSubject("user:" + userId);
    }

    /**
     * Identidade informada pelo cliente antes da autenticação, já normalizada pelo chamador.
     */
    public static RateLimitSubject ofIdentity(String normalizedIdentity) {
        if (normalizedIdentity == null || normalizedIdentity.isBlank()) {
            throw new IllegalArgumentException("normalizedIdentity must not be blank");
        }
        return new RateLimitSubject("identity:" + normalizedIdentity);
    }

    /**
     * Contador único da ação, compartilhado por todos os clientes.
     */
    public static RateLimitSubject global() {
        return GLOBAL;
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof RateLimitSubject subject && value.equals(subject.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return "RateLimitSubject[redacted]";
    }
}
