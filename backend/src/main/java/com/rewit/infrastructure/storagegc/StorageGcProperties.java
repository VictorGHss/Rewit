package com.rewit.infrastructure.storagegc;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Propriedades do GC de storage (Step 28.5), prefixo {@code rewit.storage-gc}.
 *
 * <p>Desligado por padrão. Ao ligar ({@code enabled=true}), modo, grace period, limites, timeout e
 * intervalo passam a ser obrigatórios e são validados no boot por {@link #validateForExecution()}:
 * não há valor padrão para nenhum deles, para que nenhuma implantação comece a remover objetos sem
 * configuração explícita.
 */
@Component
@ConfigurationProperties(prefix = "rewit.storage-gc")
public class StorageGcProperties {

    /** Teto do S3 ListObjectsV2 para max-keys. */
    static final int MAX_PAGE_SIZE = 1000;

    /** Teto de segurança para os demais limites por ciclo. */
    static final int MAX_LIMIT = 10_000;

    /** Liga o scheduler e a montagem do GC. Desligado por padrão. */
    private boolean enabled = false;

    /** Modo dry-run: lista, observa e recheca, mas nunca chama o storage delete. Obrigatório quando habilitado. */
    private Boolean dryRun;

    /** Intervalo entre ciclos (fixedDelay). Obrigatório quando habilitado. */
    private Long intervalMs;

    /** Atraso do primeiro ciclo após o boot. */
    private long initialDelayMs = 60000;

    /** Objetos por página de listagem (max-keys). Obrigatório quando habilitado. */
    private Integer pageSize;

    /** Páginas de listagem por ciclo. Obrigatório quando habilitado. */
    private Integer maxPages;

    /** Linhas OBSERVED rechecadas por ciclo. Obrigatório quando habilitado. */
    private Integer maxCandidates;

    /** Linhas CONFIRMED_ORPHAN levadas à exclusão por ciclo. Obrigatório quando habilitado. */
    private Integer maxDeletes;

    /** Grace period contado da primeira observação persistida. Obrigatório quando habilitado; sem padrão. */
    private Duration gracePeriod;

    /** Duração máxima de um ciclo. Obrigatório quando habilitado. */
    private Duration maxDuration;

    /** Falhas consecutivas que abortam o ciclo. */
    private int maxConsecutiveFailures = 3;

    /**
     * Valida a configuração exigida para executar o GC.
     *
     * @throws IllegalStateException com todas as violações encontradas
     */
    public void validateForExecution() {
        List<String> errors = new ArrayList<>();
        if (dryRun == null) {
            errors.add("dry-run deve ser definido explicitamente (true ou false)");
        }
        if (intervalMs == null || intervalMs <= 0) {
            errors.add("interval-ms deve ser positivo");
        }
        if (initialDelayMs < 0) {
            errors.add("initial-delay-ms não pode ser negativo");
        }
        requireWithin(errors, "page-size", pageSize, MAX_PAGE_SIZE);
        requireWithin(errors, "max-pages", maxPages, MAX_LIMIT);
        requireWithin(errors, "max-candidates", maxCandidates, MAX_LIMIT);
        requireWithin(errors, "max-deletes", maxDeletes, MAX_LIMIT);
        requirePositive(errors, "grace-period", gracePeriod);
        requirePositive(errors, "max-duration", maxDuration);
        if (maxConsecutiveFailures <= 0 || maxConsecutiveFailures > MAX_LIMIT) {
            errors.add("max-consecutive-failures deve estar entre 1 e " + MAX_LIMIT);
        }
        if (!errors.isEmpty()) {
            throw new IllegalStateException("Configuração inválida de rewit.storage-gc: " + String.join("; ", errors));
        }
    }

    private static void requireWithin(List<String> errors, String name, Integer value, int max) {
        if (value == null || value <= 0 || value > max) {
            errors.add(name + " deve estar entre 1 e " + max);
        }
    }

    private static void requirePositive(List<String> errors, String name, Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            errors.add(name + " deve ser uma duração positiva");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Boolean getDryRun() {
        return dryRun;
    }

    public void setDryRun(Boolean dryRun) {
        this.dryRun = dryRun;
    }

    public Long getIntervalMs() {
        return intervalMs;
    }

    public void setIntervalMs(Long intervalMs) {
        this.intervalMs = intervalMs;
    }

    public long getInitialDelayMs() {
        return initialDelayMs;
    }

    public void setInitialDelayMs(long initialDelayMs) {
        this.initialDelayMs = initialDelayMs;
    }

    public Integer getPageSize() {
        return pageSize;
    }

    public void setPageSize(Integer pageSize) {
        this.pageSize = pageSize;
    }

    public Integer getMaxPages() {
        return maxPages;
    }

    public void setMaxPages(Integer maxPages) {
        this.maxPages = maxPages;
    }

    public Integer getMaxCandidates() {
        return maxCandidates;
    }

    public void setMaxCandidates(Integer maxCandidates) {
        this.maxCandidates = maxCandidates;
    }

    public Integer getMaxDeletes() {
        return maxDeletes;
    }

    public void setMaxDeletes(Integer maxDeletes) {
        this.maxDeletes = maxDeletes;
    }

    public Duration getGracePeriod() {
        return gracePeriod;
    }

    public void setGracePeriod(Duration gracePeriod) {
        this.gracePeriod = gracePeriod;
    }

    public Duration getMaxDuration() {
        return maxDuration;
    }

    public void setMaxDuration(Duration maxDuration) {
        this.maxDuration = maxDuration;
    }

    public int getMaxConsecutiveFailures() {
        return maxConsecutiveFailures;
    }

    public void setMaxConsecutiveFailures(int maxConsecutiveFailures) {
        this.maxConsecutiveFailures = maxConsecutiveFailures;
    }
}
