package com.rewit.infrastructure.ratelimit;

import com.rewit.application.ratelimit.RateLimitSubject;
import com.rewit.application.ratelimit.RateLimitedAction;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.Locale;
import java.util.Objects;

/**
 * Deriva a chave de armazenamento de um sujeito por HMAC-SHA256: e-mail e id de usuário nunca aparecem em claro
 * no Redis, e sem o segredo não é possível testar se um e-mail conhecido tem contador.
 * Formato: {@code rewit:rate-limit:<acao>:<hmac base64url>}.
 */
public class RateLimitKeyDeriver {

    static final String KEY_PREFIX = "rewit:rate-limit:";
    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec key;

    public RateLimitKeyDeriver(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("rate limit key secret must not be blank");
        }
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM);
    }

    public String derive(RateLimitedAction action, RateLimitSubject subject) {
        Objects.requireNonNull(action, "action must not be null");
        Objects.requireNonNull(subject, "subject must not be null");
        byte[] digest = hmac(action.name() + '\n' + subject.value());
        return KEY_PREFIX + action.name().toLowerCase(Locale.ROOT) + ':'
                + Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }

    private byte[] hmac(String input) {
        try {
            // Mac não é thread-safe: uma instância por derivação
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 indisponível na JVM", e);
        }
    }
}
