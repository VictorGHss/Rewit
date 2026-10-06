package com.rewit.infrastructure.ratelimit;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Resultado de uma tentativa de aquisição em um {@link RateLimitStore}.
 *
 * @param member identificador da tentativa concedida, ou {@code null} se o limite foi atingido
 * @param retryAfter tempo estimado para nova tentativa quando rejeitado, ou {@code null} se concedido
 */
record RateLimitStoreResult(String member, Duration retryAfter) {

    static RateLimitStoreResult granted(String member) {
        return new RateLimitStoreResult(Objects.requireNonNull(member, "member must not be null"), null);
    }

    static RateLimitStoreResult rejected(Duration retryAfter) {
        return new RateLimitStoreResult(null, Objects.requireNonNull(retryAfter, "retryAfter must not be null"));
    }

    boolean isGranted() {
        return member != null;
    }

    Optional<String> memberOptional() {
        return Optional.ofNullable(member);
    }
}

