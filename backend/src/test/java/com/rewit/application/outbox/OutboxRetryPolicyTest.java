package com.rewit.application.outbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Testes de Unidade: Política de Retry do Outbox (Step 27.2, Partes C/D)")
class OutboxRetryPolicyTest {

    private static final Instant NOW = Instant.parse("2026-03-01T12:00:00Z");

    private OutboxRetryPolicy defaultPolicy() {
        return new OutboxRetryPolicy(Duration.ofSeconds(30), 2.0, Duration.ofMinutes(10), 5);
    }

    @Test
    @DisplayName("C.1: primeira tentativa (attempts=1) reagenda com o atraso inicial determinístico")
    void shouldUseInitialDelayForFirstAttempt() {
        OutboxRetryPolicy policy = defaultPolicy();

        Instant next = policy.nextAttemptAt(1, NOW);

        assertEquals(NOW.plusSeconds(30), next);
    }

    @Test
    @DisplayName("C.2: backoff exponencial determinístico e repetível (30s, 60s, 120s, 240s)")
    void shouldGrowExponentiallyAndDeterministically() {
        OutboxRetryPolicy policy = defaultPolicy();

        assertEquals(NOW.plusSeconds(60), policy.nextAttemptAt(2, NOW));
        assertEquals(NOW.plusSeconds(120), policy.nextAttemptAt(3, NOW));
        assertEquals(NOW.plusSeconds(240), policy.nextAttemptAt(4, NOW));
        assertEquals(policy.nextAttemptAt(2, NOW), policy.nextAttemptAt(2, NOW),
                "Mesmos inputs devem produzir a mesma saída (sem jitter)");
    }

    @Test
    @DisplayName("C.3: atraso é limitado ao teto max-delay mesmo com tentativas altas")
    void shouldCapDelayAtMaxDelay() {
        OutboxRetryPolicy policy = defaultPolicy();

        Instant capped = policy.nextAttemptAt(20, NOW);

        assertEquals(NOW.plus(Duration.ofMinutes(10)), capped);
    }

    @Test
    @DisplayName("C.4: attempts não positivo é tratado como primeira tentativa (clamp para 1)")
    void shouldClampNonPositiveAttemptsToFirstAttempt() {
        OutboxRetryPolicy policy = defaultPolicy();

        assertEquals(NOW.plusSeconds(30), policy.nextAttemptAt(0, NOW));
    }

    @Test
    @DisplayName("C.5: guarda contra overflow — exponencial infinito cai no teto sem lançar exceção")
    void shouldHandleExponentialOverflowWithCap() {
        OutboxRetryPolicy policy = new OutboxRetryPolicy(Duration.ofSeconds(1), 1e300, Duration.ofMinutes(10), 5);

        Instant capped = policy.nextAttemptAt(10, NOW);

        assertEquals(NOW.plus(Duration.ofMinutes(10)), capped);
    }

    @Test
    @DisplayName("D.1: shouldRetry permite enquanto attempts < maxAttempts e veda a partir dele")
    void shouldRetryRespectsMaxAttemptsBoundary() {
        OutboxRetryPolicy policy = defaultPolicy();

        assertTrue(policy.shouldRetry(1));
        assertTrue(policy.shouldRetry(4));
        assertFalse(policy.shouldRetry(5), "Na tentativa igual ao máximo não há mais crédito de retry");
        assertFalse(policy.shouldRetry(6));
    }

    @Test
    @DisplayName("nextAttemptAt é sempre futuro em relação a now (nunca no passado)")
    void shouldAlwaysScheduleInTheFuture() {
        OutboxRetryPolicy policy = defaultPolicy();

        for (int attempts = 1; attempts <= 8; attempts++) {
            assertTrue(policy.nextAttemptAt(attempts, NOW).isAfter(NOW),
                    "Tentativa " + attempts + " deve reagendar no futuro");
        }
    }

    @Test
    @DisplayName("Validação de construção: atraso positivo, multiplicador >= 1.0, teto >= inicial e maxAttempts >= 1")
    void shouldValidateConstructionArguments() {
        assertThrows(IllegalArgumentException.class, () ->
                new OutboxRetryPolicy(null, 2.0, Duration.ofMinutes(10), 5));
        assertThrows(IllegalArgumentException.class, () ->
                new OutboxRetryPolicy(Duration.ofSeconds(-30), 2.0, Duration.ofMinutes(10), 5));
        assertThrows(IllegalArgumentException.class, () ->
                new OutboxRetryPolicy(Duration.ofSeconds(30), 0.5, Duration.ofMinutes(10), 5));
        assertThrows(IllegalArgumentException.class, () ->
                new OutboxRetryPolicy(Duration.ofSeconds(30), 2.0, Duration.ofSeconds(10), 5));
        assertThrows(IllegalArgumentException.class, () ->
                new OutboxRetryPolicy(Duration.ofSeconds(30), 2.0, Duration.ofMinutes(10), 0));
    }
}
