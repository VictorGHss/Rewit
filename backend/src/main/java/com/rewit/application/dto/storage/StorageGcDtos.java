package com.rewit.application.dto.storage;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Contratos operacionais do ciclo de GC de storage (Step 28.5). Uso exclusivamente interno.
 */
public final class StorageGcDtos {

    private StorageGcDtos() {}

    /**
     * Limites de um ciclo. Todos obrigatórios e positivos: não há ciclo ilimitado.
     *
     * @param maxPages               páginas de listagem por ciclo
     * @param maxCandidates          linhas OBSERVED rechecadas por ciclo
     * @param maxDeletes             linhas CONFIRMED_ORPHAN levadas à exclusão (ou contadas, em dry-run) por ciclo
     * @param maxDuration            duração máxima; verificada entre etapas, sem interromper uma chamada em curso
     * @param maxConsecutiveFailures falhas consecutivas que abortam o ciclo
     */
    public record StorageGcLimits(
            int maxPages,
            int maxCandidates,
            int maxDeletes,
            Duration maxDuration,
            int maxConsecutiveFailures
    ) {
        public StorageGcLimits {
            Objects.requireNonNull(maxDuration, "maxDuration must not be null");
            if (maxPages <= 0 || maxCandidates <= 0 || maxDeletes <= 0 || maxConsecutiveFailures <= 0) {
                throw new IllegalArgumentException("storage GC limits must be positive");
            }
            if (maxDuration.isZero() || maxDuration.isNegative()) {
                throw new IllegalArgumentException("maxDuration must be positive");
            }
        }
    }

    /** Situação final de um ciclo. */
    public enum StorageGcRunStatus {
        /** Prefixo varrido até o fim e nenhuma fase limitada. */
        COMPLETED,
        /** Algum limite foi atingido (páginas, candidatos ou exclusões): pode haver trabalho restante. */
        PARTIAL,
        /** A duração máxima foi atingida entre etapas. */
        TIMED_OUT,
        /** Falhas consecutivas ou falha permanente de storage interromperam o ciclo. */
        ABORTED,
        /** Falha de infraestrutura (listagem, banco ou lock); nenhuma exclusão posterior foi tentada. */
        FAILED,
        /** Outra execução detém o lock global do GC. */
        SKIPPED_LOCKED
    }

    /**
     * Relatório agregado de um ciclo, sem object keys.
     *
     * @param objectsIgnored           fora do prefixo, formato não reconhecido ou repetidos na listagem
     * @param candidatesObserved       objetos sem referência registrados na quarentena
     * @param rechecks                 rechecagens executadas
     * @param gracePending             rechecagens ainda dentro do grace period
     * @param protectedByReference     candidatos liberados por referência ACTIVE/REMOVED (rechecagem ou exclusão)
     * @param confirmedOrphans         rechecagens que confirmaram ausência de referência
     * @param deleteCandidates         linhas CONFIRMED_ORPHAN selecionadas para a fase de exclusão
     * @param wouldDelete              exclusões que seriam tentadas (somente dry-run; nada foi removido)
     * @param deletesAttempted         chamadas reais ao storage
     * @param deleted                  objetos removidos
     * @param alreadyAbsent            objetos já ausentes (NOT_FOUND, sucesso idempotente)
     * @param candidateErrors          erros isolados em candidatos (inclui chaves inválidas na quarentena)
     */
    public record StorageGcCycleReport(
            StorageGcRunStatus status,
            boolean dryRun,
            Instant startedAt,
            Duration duration,
            int pagesScanned,
            long objectsListed,
            long objectsIgnored,
            long candidatesObserved,
            boolean listingComplete,
            long rechecks,
            long gracePending,
            long protectedByReference,
            long confirmedOrphans,
            long deleteCandidates,
            long wouldDelete,
            long deletesAttempted,
            long deleted,
            long alreadyAbsent,
            long retryableFailures,
            long permanentFailures,
            long candidateErrors
    ) {
        public StorageGcCycleReport {
            Objects.requireNonNull(status, "status must not be null");
            Objects.requireNonNull(startedAt, "startedAt must not be null");
            Objects.requireNonNull(duration, "duration must not be null");
        }
    }
}
