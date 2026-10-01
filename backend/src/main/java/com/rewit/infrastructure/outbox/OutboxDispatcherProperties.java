package com.rewit.infrastructure.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Propriedades de configuração do dispatcher do Outbox (Step 27.2, Parte X).
 * Todos os valores são externos e possuem defaults conservadores para
 * desenvolvimento local — sem afirmação de otimalidade; ajuste por ambiente.
 */
@Component
@ConfigurationProperties(prefix = "rewit.outbox")
public class OutboxDispatcherProperties {

    /** Liga/desliga o poller @Scheduled (testes e ambientes sem worker usam false). */
    private boolean pollerEnabled = true;

    /** Intervalo entre ciclos do poller (fixedDelay: um ciclo só inicia após o anterior terminar). */
    private long pollIntervalMs = 5000;

    /** Atraso inicial do primeiro ciclo após o boot (evita pressão no banco na subida). */
    private long initialDelayMs = 15000;

    /** Tamanho conservador do lote reivindicado por ciclo. */
    private int batchSize = 10;

    /** Duração da lease: PROCESSING com locked_at mais antigo que isso é recuperado. */
    private Duration leaseDuration = Duration.ofMinutes(2);

    /** Limite de caracteres do last_error persistido (sanitizado e truncado). */
    private int errorMaxLength = 512;

    /** Liga/desliga o job agendado de purge de mensagens COMPLETED antigas (testes usam false). */
    private boolean purgeEnabled = true;

    /** Intervalo entre passadas do purge (fixedDelay: uma passada só inicia após a anterior terminar). */
    private long purgeIntervalMs = 3600000;

    /** Atraso inicial da primeira passada de purge após o boot. */
    private long purgeInitialDelayMs = 60000;

    /** Idade mínima (desde updated_at) para uma mensagem COMPLETED ser elegível a purge. */
    private Duration purgeRetention = Duration.ofDays(30);

    /** Tamanho máximo do lote removido por passada do purge. */
    private int purgeBatchSize = 100;

    private final Retry retry = new Retry();

    public boolean isPollerEnabled() {
        return pollerEnabled;
    }

    public void setPollerEnabled(boolean pollerEnabled) {
        this.pollerEnabled = pollerEnabled;
    }

    public long getPollIntervalMs() {
        return pollIntervalMs;
    }

    public void setPollIntervalMs(long pollIntervalMs) {
        this.pollIntervalMs = pollIntervalMs;
    }

    public long getInitialDelayMs() {
        return initialDelayMs;
    }

    public void setInitialDelayMs(long initialDelayMs) {
        this.initialDelayMs = initialDelayMs;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public Duration getLeaseDuration() {
        return leaseDuration;
    }

    public void setLeaseDuration(Duration leaseDuration) {
        this.leaseDuration = leaseDuration;
    }

    public int getErrorMaxLength() {
        return errorMaxLength;
    }

    public void setErrorMaxLength(int errorMaxLength) {
        this.errorMaxLength = errorMaxLength;
    }

    public boolean isPurgeEnabled() {
        return purgeEnabled;
    }

    public void setPurgeEnabled(boolean purgeEnabled) {
        this.purgeEnabled = purgeEnabled;
    }

    public long getPurgeIntervalMs() {
        return purgeIntervalMs;
    }

    public void setPurgeIntervalMs(long purgeIntervalMs) {
        this.purgeIntervalMs = purgeIntervalMs;
    }

    public long getPurgeInitialDelayMs() {
        return purgeInitialDelayMs;
    }

    public void setPurgeInitialDelayMs(long purgeInitialDelayMs) {
        this.purgeInitialDelayMs = purgeInitialDelayMs;
    }

    public Duration getPurgeRetention() {
        return purgeRetention;
    }

    public void setPurgeRetention(Duration purgeRetention) {
        this.purgeRetention = purgeRetention;
    }

    public int getPurgeBatchSize() {
        return purgeBatchSize;
    }

    public void setPurgeBatchSize(int purgeBatchSize) {
        this.purgeBatchSize = purgeBatchSize;
    }

    public Retry getRetry() {
        return retry;
    }

    public static class Retry {

        /** Atraso da primeira retentativa (após a tentativa 1 falhar). */
        private Duration initialDelay = Duration.ofSeconds(30);

        /** Fator multiplicador do backoff exponencial por tentativa. */
        private double multiplier = 2.0;

        /** Teto do atraso entre retentativas. */
        private Duration maxDelay = Duration.ofMinutes(10);

        /** Número máximo de tentativas antes de FAILED terminal. */
        private int maxAttempts = 5;

        public Duration getInitialDelay() {
            return initialDelay;
        }

        public void setInitialDelay(Duration initialDelay) {
            this.initialDelay = initialDelay;
        }

        public double getMultiplier() {
            return multiplier;
        }

        public void setMultiplier(double multiplier) {
            this.multiplier = multiplier;
        }

        public Duration getMaxDelay() {
            return maxDelay;
        }

        public void setMaxDelay(Duration maxDelay) {
            this.maxDelay = maxDelay;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }
    }
}
