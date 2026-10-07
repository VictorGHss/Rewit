package com.rewit.infrastructure.account;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Propriedades do job de purge de contas excluídas (C2.3), prefixo {@code rewit.account.purge}. Mesmo padrão do
 * cleanup de {@code auth_sessions}: ligado, uma passada por dia, lotes limitados. Não há propriedade de retenção: o
 * prazo de 30 dias é regra de domínio ({@code User.PURGE_GRACE_PERIOD}).
 */
@Component
@ConfigurationProperties(prefix = "rewit.account.purge")
public class AccountPurgeJobProperties {

    /** Liga/desliga o job agendado (testes usam false). */
    private boolean enabled = true;

    /** Intervalo entre passadas (fixedDelay: uma passada só inicia após a anterior terminar). Padrão: 24 horas. */
    private long intervalMs = 86_400_000;

    /** Atraso inicial da primeira passada após o boot. */
    private long initialDelayMs = 600_000;

    /** Contas por lote. */
    private int batchSize = 100;

    /** Lotes por passada. */
    private int maxBatchesPerRun = 10;

    /**
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
            throw new IllegalStateException("Configuração inválida de rewit.account.purge: " + String.join("; ", errors));
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
