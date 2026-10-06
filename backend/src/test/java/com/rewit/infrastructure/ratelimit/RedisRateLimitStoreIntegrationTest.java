package com.rewit.infrastructure.ratelimit;

import com.rewit.application.ratelimit.RateLimitPermit;
import com.rewit.application.ratelimit.RateLimitSubject;
import com.rewit.application.ratelimit.RateLimitedAction;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Janela deslizante no Redis real de teste: atomicidade, distribuição entre instâncias, TTL e indisponibilidade.
 * Cada "instância" tem a sua própria conexão Lettuce, como pods distintos da API. Chaves aleatórias por teste.
 */
@DisplayName("Rate limiting: janela deslizante no Redis real")
class RedisRateLimitStoreIntegrationTest {

    private static final String REDIS_HOST = envOrDefault("REDIS_HOST", "localhost");
    private static final int REDIS_PORT = Integer.parseInt(envOrDefault("REDIS_PORT", "6379"));
    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final List<LettuceConnectionFactory> factories = new ArrayList<>();
    private StringRedisTemplate instanceA;
    private StringRedisTemplate instanceB;
    private RedisRateLimitStore storeA;
    private RedisRateLimitStore storeB;
    private String key;

    @BeforeEach
    void setUp() {
        instanceA = template(REDIS_PORT, Duration.ofSeconds(5));
        instanceB = template(REDIS_PORT, Duration.ofSeconds(5));
        storeA = new RedisRateLimitStore(instanceA);
        storeB = new RedisRateLimitStore(instanceB);
        key = RateLimitKeyDeriver.KEY_PREFIX + "test:" + UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        instanceA.delete(key);
        factories.forEach(LettuceConnectionFactory::destroy);
    }

    @Test
    @DisplayName("Concede até o limite e nega a seguinte; a chave sempre tem TTL de no máximo uma janela")
    void enforcesLimitAndAlwaysSetsTtl() {
        for (int i = 0; i < 3; i++) {
            assertTrue(storeA.tryAcquire(key, 3, WINDOW).isPresent());
            assertTtlWithinWindow();
        }
        assertTrue(storeA.tryAcquire(key, 3, WINDOW).isEmpty());
        assertTtlWithinWindow();
        assertEquals(3L, instanceA.opsForZSet().zCard(key));
    }

    @Test
    @DisplayName("Distribuição: duas instâncias com o mesmo Redis obedecem ao mesmo limite")
    void twoInstancesShareTheSameLimit() {
        assertTrue(storeA.tryAcquire(key, 4, WINDOW).isPresent());
        assertTrue(storeB.tryAcquire(key, 4, WINDOW).isPresent());
        assertTrue(storeA.tryAcquire(key, 4, WINDOW).isPresent());
        assertTrue(storeB.tryAcquire(key, 4, WINDOW).isPresent());

        assertTrue(storeA.tryAcquire(key, 4, WINDOW).isEmpty());
        assertTrue(storeB.tryAcquire(key, 4, WINDOW).isEmpty());
    }

    @Test
    @DisplayName("Atomicidade: 64 requisições concorrentes em duas instâncias concedem exatamente o limite")
    void concurrentRequestsAcrossInstancesNeverExceedLimit() throws Exception {
        int requests = 64;
        int limit = 10;
        ExecutorService executor = Executors.newFixedThreadPool(requests);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                RedisRateLimitStore store = i % 2 == 0 ? storeA : storeB;
                results.add(executor.submit(() -> {
                    start.await();
                    return store.tryAcquire(key, limit, WINDOW).isPresent();
                }));
            }
            start.countDown();
            int granted = 0;
            for (Future<Boolean> result : results) {
                if (result.get(30, TimeUnit.SECONDS)) {
                    granted++;
                }
            }
            assertEquals(limit, granted);
        } finally {
            executor.shutdownNow();
        }
        assertEquals((long) limit, instanceA.opsForZSet().zCard(key));
    }

    @Test
    @DisplayName("Janela: novas tentativas são liberadas após a expiração, e a chave some sozinha")
    void windowExpiryReleasesAttemptsAndKey() throws Exception {
        Duration shortWindow = Duration.ofMillis(500);
        assertTrue(storeA.tryAcquire(key, 2, shortWindow).isPresent());
        assertTrue(storeB.tryAcquire(key, 2, shortWindow).isPresent());
        assertTrue(storeA.tryAcquire(key, 2, shortWindow).isEmpty());

        Thread.sleep(shortWindow.toMillis() + 200);

        assertFalse(instanceA.hasKey(key), "TTL removeu a chave depois da janela");
        assertTrue(storeB.tryAcquire(key, 2, shortWindow).isPresent());
        assertTrue(storeA.tryAcquire(key, 2, shortWindow).isPresent());
        assertTrue(storeA.tryAcquire(key, 2, shortWindow).isEmpty());
    }

    @Test
    @DisplayName("Tentativa devolvida por outra instância libera a posição")
    void releaseFromAnotherInstanceFreesSlot() {
        Optional<String> member = storeA.tryAcquire(key, 1, WINDOW);
        assertTrue(storeB.tryAcquire(key, 1, WINDOW).isEmpty());

        storeB.release(key, member.orElseThrow());

        assertTrue(storeA.tryAcquire(key, 1, WINDOW).isPresent());
    }

    @Test
    @DisplayName("Redis inacessível: o armazenamento sinaliza falha de backend em vez de conceder ou negar")
    void unreachableRedisSignalsBackendFailure() {
        RedisRateLimitStore unreachable = new RedisRateLimitStore(template(1, Duration.ofSeconds(1)));

        assertThrows(RateLimitBackendException.class, () -> unreachable.tryAcquire(key, 5, WINDOW));
        assertThrows(RateLimitBackendException.class, () -> unreachable.release(key, "membro"));
    }

    @Test
    @DisplayName("Ponta a ponta: duas instâncias do rate limiter com o mesmo segredo compartilham o limite do sujeito")
    void limitersOnTwoInstancesShareSubjectLimit() {
        RateLimitProperties properties = RateLimitTestSupport.properties();
        properties.getAuth().getLogin().setLimit(3);
        RateLimitSubject subject = RateLimitSubject.ofIdentity("e2e-" + UUID.randomUUID() + "@rewit.com");
        ResilientRateLimiter limiterA = limiter(properties, storeA);
        ResilientRateLimiter limiterB = limiter(properties, storeB);

        RateLimitPermit first = limiterA.tryAcquire(RateLimitedAction.LOGIN, subject).orElseThrow();
        limiterB.tryAcquire(RateLimitedAction.LOGIN, subject).orElseThrow();
        limiterA.tryAcquire(RateLimitedAction.LOGIN, subject).orElseThrow();
        assertTrue(limiterB.tryAcquire(RateLimitedAction.LOGIN, subject).isEmpty());

        limiterA.release(first);
        assertTrue(limiterB.tryAcquire(RateLimitedAction.LOGIN, subject).isPresent());

        instanceA.delete(new RateLimitKeyDeriver(RateLimitTestSupport.TEST_KEY_SECRET).derive(RateLimitedAction.LOGIN, subject));
    }

    @Test
    @DisplayName("Ponta a ponta com Redis inacessível: LOCAL_FALLBACK mantém o limite e o gauge indica modo degradado")
    void limiterWithUnreachableRedisFallsBackLocally() {
        RateLimitProperties properties = RateLimitTestSupport.properties();
        properties.getContent().getMediaUpload().setLimit(2);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Clock clock = Clock.systemUTC();
        ResilientRateLimiter limiter = new ResilientRateLimiter(properties,
                new RateLimitKeyDeriver(RateLimitTestSupport.TEST_KEY_SECRET),
                new RedisRateLimitStore(template(1, Duration.ofSeconds(1))),
                new LocalRateLimitStore(1000, clock), new RateLimitMetrics(registry), clock);
        RateLimitSubject user = RateLimitSubject.ofUser(UUID.randomUUID());

        assertTrue(limiter.tryAcquire(RateLimitedAction.MEDIA_UPLOAD, user).isPresent());
        assertTrue(limiter.tryAcquire(RateLimitedAction.MEDIA_UPLOAD, user).isPresent());
        assertTrue(limiter.tryAcquire(RateLimitedAction.MEDIA_UPLOAD, user).isEmpty());

        assertEquals(0.0, registry.get(RateLimitMetrics.GAUGE_BACKEND_AVAILABLE).gauge().value());
        assertEquals(1.0, registry.get(RateLimitMetrics.COUNTER_BACKEND_ERRORS).counter().count());
    }

    private ResilientRateLimiter limiter(RateLimitProperties properties, RedisRateLimitStore store) {
        Clock clock = Clock.systemUTC();
        return new ResilientRateLimiter(properties, new RateLimitKeyDeriver(properties.getKeySecret()), store,
                new LocalRateLimitStore(1000, clock), new RateLimitMetrics(new SimpleMeterRegistry()), clock);
    }

    private void assertTtlWithinWindow() {
        Long ttl = instanceA.getExpire(key, TimeUnit.MILLISECONDS);
        assertTrue(ttl != null && ttl > 0 && ttl <= WINDOW.toMillis(), "TTL fora da janela: " + ttl);
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

    private static String envOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
