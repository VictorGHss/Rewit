package com.rewit.infrastructure.security.social;

import com.rewit.application.port.SocialLoginNonceStore;
import com.rewit.common.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.util.Objects;

/**
 * Nonces do login social no Redis: uma chave por hash, com TTL, consumida por {@code GETDEL} (atômico entre
 * instâncias). Sem Redis o login social falha fechado: nenhum nonce é emitido nem aceito sem confirmação, ao
 * contrário do rate limiting, que tem fallback local. Os logs não incluem a chave.
 */
public class RedisSocialLoginNonceStore implements SocialLoginNonceStore {

    private static final Logger log = LoggerFactory.getLogger(RedisSocialLoginNonceStore.class);

    static final String KEY_PREFIX = "rewit:social-login:nonce:";

    private final StringRedisTemplate redisTemplate;

    public RedisSocialLoginNonceStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate must not be null");
    }

    @Override
    public void store(String nonceHash, Duration ttl) {
        Objects.requireNonNull(nonceHash, "nonceHash must not be null");
        Objects.requireNonNull(ttl, "ttl must not be null");
        Boolean stored;
        try {
            stored = redisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + nonceHash, "1", ttl);
        } catch (DataAccessException e) {
            throw unavailable(e);
        }
        // 256 bits aleatórios não colidem; uma resposta diferente de true é tratada como falha do armazenamento
        if (!Boolean.TRUE.equals(stored)) {
            throw unavailable(null);
        }
    }

    @Override
    public boolean consume(String nonceHash) {
        Objects.requireNonNull(nonceHash, "nonceHash must not be null");
        try {
            return redisTemplate.opsForValue().getAndDelete(KEY_PREFIX + nonceHash) != null;
        } catch (DataAccessException e) {
            throw unavailable(e);
        }
    }

    private static BusinessException unavailable(Exception cause) {
        log.warn("Armazenamento de nonces do login social indisponível: {}",
                cause != null ? cause.getClass().getSimpleName() : "escrita não confirmada");
        return new BusinessException("Login social indisponível no momento. Tente novamente.",
                HttpStatus.SERVICE_UNAVAILABLE, "SOCIAL_LOGIN_UNAVAILABLE");
    }
}
