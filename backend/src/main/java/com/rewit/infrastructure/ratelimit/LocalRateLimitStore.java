package com.rewit.infrastructure.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Janela deslizante em memória, por instância: a mesma semântica do {@link RedisRateLimitStore}, usada como
 * fallback quando o Redis está indisponível. Cada chave é alterada atomicamente por {@code compute}.
 *
 * <p>O número de chaves é limitado: chaves expiradas são removidas periodicamente e, se o teto for atingido
 * mesmo assim, sujeitos novos são negados em vez de crescer a memória sem limite.
 */
class LocalRateLimitStore implements RateLimitStore {

    private static final long PURGE_INTERVAL_MILLIS = 60_000;

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong lastPurgeMillis;
    private final int maxKeys;
    private final Clock clock;

    LocalRateLimitStore(int maxKeys, Clock clock) {
        if (maxKeys <= 0) {
            throw new IllegalArgumentException("maxKeys must be positive");
        }
        this.maxKeys = maxKeys;
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.lastPurgeMillis = new AtomicLong(clock.millis());
    }

    @Override
    public Optional<String> tryAcquire(String key, int limit, Duration window) {
        long now = clock.millis();
        long windowMillis = window.toMillis();
        purgeIfDue(now);
        if (!windows.containsKey(key) && windows.size() >= maxKeys) {
            purge(now);
            if (windows.size() >= maxKeys) {
                return Optional.empty();
            }
        }
        String[] granted = new String[1];
        windows.compute(key, (k, current) -> {
            Window target = current != null ? current : new Window(windowMillis);
            target.prune(now);
            if (target.size() < limit) {
                granted[0] = Long.toString(sequence.incrementAndGet());
                target.add(now, granted[0]);
            }
            return target.isEmpty() ? null : target;
        });
        return Optional.ofNullable(granted[0]);
    }

    @Override
    public void release(String key, String member) {
        windows.computeIfPresent(key, (k, current) -> {
            current.remove(member);
            return current.isEmpty() ? null : current;
        });
    }

    int trackedKeys() {
        return windows.size();
    }

    private void purgeIfDue(long now) {
        long last = lastPurgeMillis.get();
        if (now - last >= PURGE_INTERVAL_MILLIS && lastPurgeMillis.compareAndSet(last, now)) {
            purge(now);
        }
    }

    private void purge(long now) {
        for (String key : new ArrayList<>(windows.keySet())) {
            windows.computeIfPresent(key, (k, current) -> {
                current.prune(now);
                return current.isEmpty() ? null : current;
            });
        }
    }

    /** Tentativas concedidas de uma chave, da mais antiga para a mais recente. Acesso só dentro de compute. */
    private static final class Window {

        private final long windowMillis;
        private final Deque<Attempt> attempts = new ArrayDeque<>();

        Window(long windowMillis) {
            this.windowMillis = windowMillis;
        }

        void prune(long now) {
            long windowStart = now - windowMillis;
            while (!attempts.isEmpty() && attempts.peekFirst().at() < windowStart) {
                attempts.pollFirst();
            }
        }

        void add(long now, String member) {
            attempts.addLast(new Attempt(now, member));
        }

        void remove(String member) {
            attempts.removeIf(attempt -> attempt.member().equals(member));
        }

        int size() {
            return attempts.size();
        }

        boolean isEmpty() {
            return attempts.isEmpty();
        }
    }

    private record Attempt(long at, String member) {
    }
}
