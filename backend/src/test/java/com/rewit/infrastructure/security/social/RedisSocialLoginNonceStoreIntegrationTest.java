package com.rewit.infrastructure.security.social;

import com.rewit.common.exception.BusinessException;
import com.rewit.config.TestInfrastructureConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Nonces do login social no Redis real de teste: uso único entre instâncias concorrentes, expiração e falha fechada
 * com o Redis indisponível.
 */
@DisplayName("Login social: nonces de uso único no Redis real")
class RedisSocialLoginNonceStoreIntegrationTest {

    private static final String REDIS_HOST = TestInfrastructureConfig.current().redisHost();
    private static final int REDIS_PORT = TestInfrastructureConfig.current().redisPort();

    private final List<LettuceConnectionFactory> factories = new ArrayList<>();
    private StringRedisTemplate redis;
    private String nonceHash;

    @BeforeEach
    void setUp() {
        redis = template(REDIS_PORT, Duration.ofSeconds(5));
        nonceHash = "test-" + UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        redis.delete(RedisSocialLoginNonceStore.KEY_PREFIX + nonceHash);
        factories.forEach(LettuceConnectionFactory::destroy);
    }

    @Test
    @DisplayName("Nonce registrado é consumido uma única vez e a chave sempre tem TTL")
    void singleUseWithTtl() {
        RedisSocialLoginNonceStore store = new RedisSocialLoginNonceStore(redis);
        store.store(nonceHash, Duration.ofMinutes(10));

        Long ttl = redis.getExpire(RedisSocialLoginNonceStore.KEY_PREFIX + nonceHash, TimeUnit.SECONDS);
        assertNotNull(ttl);
        assertTrue(ttl > 0 && ttl <= 600, "TTL fora da janela: " + ttl);

        assertTrue(store.consume(nonceHash));
        assertFalse(store.consume(nonceHash));
        assertFalse(store.consume("nunca-emitido-" + UUID.randomUUID()));
    }

    @Test
    @DisplayName("Nonce expirado não é aceito")
    void expiredNonce() throws InterruptedException {
        RedisSocialLoginNonceStore store = new RedisSocialLoginNonceStore(redis);
        store.store(nonceHash, Duration.ofMillis(200));

        TimeUnit.MILLISECONDS.sleep(400);

        assertFalse(store.consume(nonceHash));
    }

    @Test
    @DisplayName("Consumo concorrente em instâncias distintas: exatamente uma vence")
    void concurrentConsumeAcrossInstances() throws Exception {
        new RedisSocialLoginNonceStore(redis).store(nonceHash, Duration.ofMinutes(1));
        int attempts = 8;
        ExecutorService executor = Executors.newFixedThreadPool(attempts);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < attempts; i++) {
                RedisSocialLoginNonceStore instance = new RedisSocialLoginNonceStore(template(REDIS_PORT, Duration.ofSeconds(5)));
                Callable<Boolean> consume = () -> {
                    start.await();
                    return instance.consume(nonceHash);
                };
                results.add(executor.submit(consume));
            }
            start.countDown();

            int granted = 0;
            for (Future<Boolean> result : results) {
                if (result.get(10, TimeUnit.SECONDS)) {
                    granted++;
                }
            }
            assertEquals(1, granted);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("Redis indisponível: emissão e consumo falham fechados com 503 SOCIAL_LOGIN_UNAVAILABLE")
    void unreachableRedisFailsClosed() {
        RedisSocialLoginNonceStore unreachable = new RedisSocialLoginNonceStore(template(1, Duration.ofSeconds(1)));

        for (org.junit.jupiter.api.function.Executable call : List.<org.junit.jupiter.api.function.Executable>of(
                () -> unreachable.store(nonceHash, Duration.ofMinutes(1)),
                () -> unreachable.consume(nonceHash))) {
            BusinessException ex = assertThrows(BusinessException.class, call);
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatus());
            assertEquals("SOCIAL_LOGIN_UNAVAILABLE", ex.getErrorCode());
        }
    }

    private StringRedisTemplate template(int port, Duration commandTimeout) {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(REDIS_HOST, port),
                LettuceClientConfiguration.builder().commandTimeout(commandTimeout).build());
        factory.afterPropertiesSet();
        factory.start();
        factories.add(factory);
        return new StringRedisTemplate(factory);
    }
}
