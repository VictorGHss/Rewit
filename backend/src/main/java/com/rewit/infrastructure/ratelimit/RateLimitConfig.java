package com.rewit.infrastructure.ratelimit;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.util.UUID;

/**
 * Montagem do rate limiting distribuído. A configuração é validada antes da montagem: com o rate limiting ligado,
 * a aplicação não sobe sem {@code rewit.rate-limit.key-secret}.
 */
@Configuration
public class RateLimitConfig {

    @Bean
    public ResilientRateLimiter rateLimiter(RateLimitProperties properties,
                                            RedisConnectionFactory redisConnectionFactory,
                                            MeterRegistry meterRegistry) {
        properties.validate();
        // Desligado, o segredo pode faltar; as chaves derivadas nunca são usadas
        String secret = properties.getKeySecret() != null && !properties.getKeySecret().isBlank()
                ? properties.getKeySecret()
                : UUID.randomUUID().toString();
        Clock clock = Clock.systemUTC();
        return new ResilientRateLimiter(
                properties,
                new RateLimitKeyDeriver(secret),
                new RedisRateLimitStore(new StringRedisTemplate(redisConnectionFactory)),
                new LocalRateLimitStore(properties.getBackend().getLocalFallbackMaxKeys(), clock),
                new RateLimitMetrics(meterRegistry),
                clock
        );
    }
}
