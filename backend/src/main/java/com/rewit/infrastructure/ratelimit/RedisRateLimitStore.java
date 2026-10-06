package com.rewit.infrastructure.ratelimit;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Janela deslizante em um sorted set do Redis, uma chave por sujeito e ação. Cada tentativa concedida é um membro
 * com o instante em milissegundos como score.
 *
 * <p>O script Lua executa limpeza, contagem, registro e TTL de forma atômica no Redis: requisições concorrentes,
 * de qualquer instância, nunca concedem mais que o limite, e a chave nunca existe sem TTL. O instante vem do
 * relógio do Redis ({@code TIME}), o mesmo para todas as instâncias. Uma chave expira uma janela após a última
 * tentativa concedida; tentativas negadas não renovam o TTL.
 */
class RedisRateLimitStore implements RateLimitStore {

    // ARGV[1] = limite, ARGV[2] = janela em ms, ARGV[3] = identificador da nova tentativa.
    // Os instantes são formatados com %.0f: em ms cabem exatos no double do Lua, e o tostring padrão os truncaria.
    private static final RedisScript<String> ACQUIRE = RedisScript.of("""
            local time = redis.call('TIME')
            local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
            local windowStart = now - tonumber(ARGV[2])
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', '(' .. string.format('%.0f', windowStart))
            if redis.call('ZCARD', KEYS[1]) >= tonumber(ARGV[1]) then
                local oldest = redis.call('ZRANGE', KEYS[1], 0, 0, 'WITHSCORES')
                local retryAfterMs = tonumber(ARGV[2])
                if #oldest >= 2 then
                    local oldestScore = tonumber(oldest[2])
                    local remainingMs = (oldestScore + tonumber(ARGV[2])) - now
                    if remainingMs > 0 then
                        retryAfterMs = remainingMs
                    else
                        retryAfterMs = 1
                    end
                end
                local retryAfterSec = math.max(1, math.ceil(retryAfterMs / 1000))
                return 'RETRY:' .. string.format('%.0f', retryAfterSec)
            end
            redis.call('ZADD', KEYS[1], string.format('%.0f', now), ARGV[3])
            redis.call('PEXPIRE', KEYS[1], ARGV[2])
            return 'OK:' .. ARGV[3]
            """, String.class);

    private final StringRedisTemplate redisTemplate;

    RedisRateLimitStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "StringRedisTemplate must not be null");
    }

    @Override
    public RateLimitStoreResult acquire(String key, int limit, Duration window) {
        String member = UUID.randomUUID().toString();
        try {
            String result = redisTemplate.execute(ACQUIRE, List.of(key),
                    Integer.toString(limit), Long.toString(window.toMillis()), member);
            if (result != null && result.startsWith("OK:")) {
                return RateLimitStoreResult.granted(result.substring(3));
            }
            if (result != null && result.startsWith("RETRY:")) {
                long seconds = Math.max(1, Long.parseLong(result.substring(6)));
                return RateLimitStoreResult.rejected(Duration.ofSeconds(seconds));
            }
            long windowSeconds = Math.max(1, window.toSeconds());
            return RateLimitStoreResult.rejected(Duration.ofSeconds(windowSeconds));
        } catch (DataAccessException e) {
            throw new RateLimitBackendException(e);
        }
    }

    @Override
    public Optional<String> tryAcquire(String key, int limit, Duration window) {
        return acquire(key, limit, window).memberOptional();
    }

    @Override
    public void release(String key, String member) {
        try {
            redisTemplate.opsForZSet().remove(key, member);
        } catch (DataAccessException e) {
            throw new RateLimitBackendException(e);
        }
    }
}
