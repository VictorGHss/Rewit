package com.rewit.infrastructure.ratelimit;

import com.rewit.infrastructure.ratelimit.RateLimitTestSupport.MutableClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Rate limiting: janela deslizante em memória (fallback)")
class LocalRateLimitStoreTest {

    private static final Duration WINDOW = Duration.ofSeconds(60);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-06T12:00:00Z"));
    private final LocalRateLimitStore store = new LocalRateLimitStore(1000, clock);

    @Test
    @DisplayName("Concede até o limite e nega a tentativa seguinte, por chave")
    void grantsUpToLimitPerKey() {
        for (int i = 0; i < 3; i++) {
            assertTrue(store.tryAcquire("k1", 3, WINDOW).isPresent());
        }
        assertTrue(store.tryAcquire("k1", 3, WINDOW).isEmpty());
        assertTrue(store.tryAcquire("k2", 3, WINDOW).isPresent(), "outra chave tem janela própria");
    }

    @Test
    @DisplayName("Janela deslizante: libera conforme as tentativas mais antigas saem; a fronteira exata ainda conta")
    void slidingWindowReleasesOldestAttempts() {
        store.tryAcquire("k", 2, WINDOW);
        clock.advance(Duration.ofSeconds(30));
        store.tryAcquire("k", 2, WINDOW);
        assertTrue(store.tryAcquire("k", 2, WINDOW).isEmpty());

        // Exatamente uma janela após a primeira tentativa ela ainda está dentro (mesma regra dos limitadores antigos)
        clock.advance(Duration.ofSeconds(30));
        assertTrue(store.tryAcquire("k", 2, WINDOW).isEmpty());

        clock.advance(Duration.ofMillis(1));
        assertTrue(store.tryAcquire("k", 2, WINDOW).isPresent(), "a primeira tentativa saiu da janela");
        assertTrue(store.tryAcquire("k", 2, WINDOW).isEmpty());
    }

    @Test
    @DisplayName("Tentativa devolvida libera a posição; devolver de novo não tem efeito")
    void releaseFreesSlot() {
        Optional<String> first = store.tryAcquire("k", 1, WINDOW);
        assertTrue(store.tryAcquire("k", 1, WINDOW).isEmpty());

        store.release("k", first.orElseThrow());
        store.release("k", first.orElseThrow());

        assertTrue(store.tryAcquire("k", 1, WINDOW).isPresent());
    }

    @Test
    @DisplayName("Chaves expiradas são removidas; no teto, sujeitos novos são negados e os existentes seguem contados")
    void maxKeysBoundsMemory() {
        LocalRateLimitStore bounded = new LocalRateLimitStore(2, clock);
        assertTrue(bounded.tryAcquire("a", 5, WINDOW).isPresent());
        assertTrue(bounded.tryAcquire("b", 5, WINDOW).isPresent());

        assertTrue(bounded.tryAcquire("c", 5, WINDOW).isEmpty(), "teto atingido com chaves vivas");
        assertTrue(bounded.tryAcquire("a", 5, WINDOW).isPresent(), "chave já rastreada continua funcionando");

        clock.advance(WINDOW.plusMillis(1));
        assertTrue(bounded.tryAcquire("c", 5, WINDOW).isPresent(), "chaves expiradas liberam espaço");
        assertEquals(1, bounded.trackedKeys());
    }

    @Test
    @DisplayName("Concorrência: 64 threads na mesma chave concedem exatamente o limite")
    void concurrentAcquisitionsNeverExceedLimit() throws Exception {
        LocalRateLimitStore concurrent = new LocalRateLimitStore(1000, Clock.systemUTC());
        int threads = 64;
        int limit = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(executor.submit(() -> {
                    start.await();
                    return concurrent.tryAcquire("hot", limit, WINDOW).isPresent();
                }));
            }
            start.countDown();
            int granted = 0;
            for (Future<Boolean> result : results) {
                if (result.get(10, TimeUnit.SECONDS)) {
                    granted++;
                }
            }
            assertEquals(limit, granted);
        } finally {
            executor.shutdownNow();
        }
        assertFalse(concurrent.tryAcquire("hot", limit, WINDOW).isPresent());
    }
}
