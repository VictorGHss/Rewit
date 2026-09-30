package com.rewit.infrastructure.security;

import com.rewit.common.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Limitador de taxa (Rate Limiter) em memória (process-local) para prevenir abuso na criação de comentários (Step 20.0).
 * Implementa janela deslizante de 60 segundos com no máximo 15 criações por usuário.
 *
 * Limitação arquitetural conhecida: Esta implementação opera em memória local da JVM. Em ambientes distribuídos
 * com múltiplos pods/instâncias horizontais, o rate limiting deve ser migrado para armazenamento compartilhado (ex: Redis).
 */
@Component
public class DiscussionRateLimiter {

    public static final int MAX_REQUESTS_PER_WINDOW = 15;
    public static final long WINDOW_SECONDS = 60;
    private static final int EVICTION_THRESHOLD = 500;

    private final Map<UUID, Deque<Instant>> userRequestHistory = new ConcurrentHashMap<>();

    /**
     * Valida se o usuário pode submeter um novo comentário dentro do limite configurado.
     * Lança BusinessException com HTTP 429 se o limite for atingido.
     *
     * @param userId identificador do usuário autor
     */
    public synchronized void checkRateLimit(UUID userId) {
        if (userId == null) {
            return;
        }

        Instant now = Instant.now();
        Instant windowStart = now.minusSeconds(WINDOW_SECONDS);

        // Limpeza periódica preventiva contra vazamento de memória quando o mapa acumular usuários inativos
        if (userRequestHistory.size() > EVICTION_THRESHOLD) {
            userRequestHistory.entrySet().removeIf(entry -> {
                Deque<Instant> queue = entry.getValue();
                while (!queue.isEmpty() && queue.peekFirst().isBefore(windowStart)) {
                    queue.pollFirst();
                }
                return queue.isEmpty();
            });
        }

        Deque<Instant> timestamps = userRequestHistory.computeIfAbsent(userId, k -> new ArrayDeque<>());

        // Remove registros fora da janela deslizante
        while (!timestamps.isEmpty() && timestamps.peekFirst().isBefore(windowStart)) {
            timestamps.pollFirst();
        }

        if (timestamps.size() >= MAX_REQUESTS_PER_WINDOW) {
            throw new BusinessException(
                    "Limite de criação de comentários excedido. Tente novamente mais tarde.",
                    HttpStatus.TOO_MANY_REQUESTS,
                    "RATE_LIMIT_EXCEEDED"
            );
        }

        timestamps.addLast(now);
    }

    /**
     * Limpa o histórico de rate limit (útil para suíte de testes).
     */
    public synchronized void reset() {
        userRequestHistory.clear();
    }
}
