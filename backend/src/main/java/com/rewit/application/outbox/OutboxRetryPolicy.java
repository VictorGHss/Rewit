package com.rewit.application.outbox;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Política determinística de retry do dispatcher do Outbox (Step 27.2, Partes C/D):
 * backoff exponencial com multiplicador e teto de atraso, mais o limite operacional
 * de tentativas.
 *
 * <p>Sem jitter: o atraso é função pura de (attempts, now), determinístico e
 * testável. {@code nextAttemptAt} nunca fica no passado (é sempre now + atraso
 * positivo) e nunca ultrapassa o teto configurado. O cálculo é à prova de overflow:
 * a exponenciação ocorre em double com guarda contra infinito antes do cast.
 *
 * <p>Semântica de attempts herdada do claim (Step 27.1): cada claim incrementa
 * attempts, portanto o valor informado aqui é o número da tentativa que ACABOU de
 * falhar. A primeira tentativa (attempts = 1) reagenda com o atraso inicial.
 *
 * <p>Os valores são alimentados por {@code rewit.outbox.retry.*}; os defaults são
 * conservadores para desenvolvimento local e não há afirmação de otimalidade.
 */
public final class OutboxRetryPolicy {

    private final Duration initialDelay;
    private final double multiplier;
    private final Duration maxDelay;
    private final int maxAttempts;

    public OutboxRetryPolicy(Duration initialDelay, double multiplier, Duration maxDelay, int maxAttempts) {
        if (initialDelay == null || initialDelay.isZero() || initialDelay.isNegative()) {
            throw new IllegalArgumentException("O atraso inicial do retry deve ser positivo");
        }
        if (multiplier < 1.0d) {
            throw new IllegalArgumentException("O multiplicador do retry deve ser >= 1.0");
        }
        if (maxDelay == null || maxDelay.compareTo(initialDelay) < 0) {
            throw new IllegalArgumentException("O atraso máximo do retry deve ser >= ao atraso inicial");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("O número máximo de tentativas deve ser >= 1");
        }
        this.initialDelay = initialDelay;
        this.multiplier = multiplier;
        this.maxDelay = maxDelay;
        this.maxAttempts = maxAttempts;
    }

    /**
     * Existe crédito de retry após falhar a tentativa {@code attempts}?
     * attempts &lt; maxAttempts indica novo ciclo PENDING; caso contrário, FAILED.
     */
    public boolean shouldRetry(int attempts) {
        return attempts < maxAttempts;
    }

    /**
     * Próximo instante de elegibilidade: now + delay(attempts). Determinístico,
     * sempre futuro em relação a {@code now} e limitado ao teto configurado.
     */
    public Instant nextAttemptAt(int attempts, Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        return now.plusMillis(delayMillisFor(attempts));
    }

    private long delayMillisFor(int attempts) {
        int failedAttempt = Math.max(1, attempts);
        double rawDelay = initialDelay.toMillis() * Math.pow(multiplier, failedAttempt - 1);
        if (Double.isInfinite(rawDelay) || Double.isNaN(rawDelay)) {
            return maxDelay.toMillis();
        }
        return (long) Math.min(rawDelay, (double) maxDelay.toMillis());
    }
}
