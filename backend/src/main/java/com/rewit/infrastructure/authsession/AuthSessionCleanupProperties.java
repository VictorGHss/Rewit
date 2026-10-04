package com.rewit.infrastructure.authsession;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Propriedades do cleanup de {@code auth_sessions} (Step 29.3), prefixo {@code rewit.auth-session-cleanup}.
 *
 * <p>Os defaults seguem o precedente do purge do Outbox ({@code rewit.outbox.purge-*}): ligado, uma passada
 * por hora, um lote de 100 linhas por fase. Não há propriedade de retenção: a elegibilidade é estrutural
 * ({@code expires_at} e {@code replaced_by_session_id}), definida na ADR-011.
 */
@Component
@ConfigurationProperties(prefix = "rewit.auth-session-cleanup")
public class AuthSessionCleanupProperties {

    /** Liga/desliga o job agendado de cleanup (testes usam false). */
    private boolean enabled = true;

    /** Intervalo entre passadas (fixedDelay: uma passada só inicia após a anterior terminar). */
    private long intervalMs = 3600000;

    /** Atraso inicial da primeira passada após o boot. */
    private long initialDelayMs = 60000;

    /** Linhas por lote, em cada fase. */
    private int batchSize = 100;

    /** Lotes por fase em cada passada. */
    private int maxBatchesPerRun = 1;

    /**
     * Valida a configuração antes de montar o job.
     *
     * @throws IllegalStateException com todas as violações encontradas
     */
    public void validate() {
        List<String> errors = new ArrayList<>();
        if (intervalMs <= 0) {
            errors.add("interval-ms deve ser positivo");
        }
        if (initialDelayMs < 0) {
            errors.add("initial-delay-ms não pode ser negativo");
        }
        if (batchSize <= 0) {
            errors.add("batch-size deve ser positivo");
        }
        if (maxBatchesPerRun <= 0) {
            errors.add("max-batches-per-run deve ser positivo");
        }
        if (!errors.isEmpty()) {
            throw new IllegalStateException("Configuração inválida de rewit.auth-session-cleanup: " + String.join("; ", errors));
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getIntervalMs() {
        return intervalMs;
    }

    public void setIntervalMs(long intervalMs) {
        this.intervalMs = intervalMs;
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

    public int getMaxBatchesPerRun() {
        return maxBatchesPerRun;
    }

    public void setMaxBatchesPerRun(int maxBatchesPerRun) {
        this.maxBatchesPerRun = maxBatchesPerRun;
    }
}
