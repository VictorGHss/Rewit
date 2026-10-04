package com.rewit.application.usecase;

import com.rewit.application.port.AuthSessionRepository;

import java.time.Instant;
import java.util.Objects;

/**
 * Executa uma passada do cleanup de {@code auth_sessions} (Step 29.3, ADR-011).
 *
 * <p>Duas fases, cada lote em transação própria (o repositório abre uma por chamada):
 * <ol>
 *   <li><b>Metadados</b>: limpa {@code ip_address} e {@code user_agent} de sessões que deixaram de estar
 *       ativas ({@code revoked_at} preenchido ou {@code expires_at < now}). Roda primeiro para que a
 *       minimização desses dados não dependa do sucesso do purge.</li>
 *   <li><b>Purge</b>: remove sessões com {@code expires_at < now} e sem sucessora de rotação. Antecessoras de
 *       cadeias vivas nunca são removidas; quando a sucessora sai, o {@code ON DELETE SET NULL} as torna
 *       elegíveis em lote posterior, sem busca recursiva.</li>
 * </ol>
 * Cada fase executa no máximo {@code maxBatchesPerRun} lotes e para antes se um lote vier incompleto.
 * Uma falha interrompe a passada: os lotes já commitados permanecem, o lote em falha sofre rollback e a
 * próxima passada continua. Não há critério de retenção configurável: a elegibilidade é estrutural.
 *
 * <p>Classe deliberadamente livre de Spring: o job agendado fino vive em {@code AuthSessionCleanupScheduler}.
 */
public class CleanupAuthSessionsUseCase {

    private final AuthSessionRepository authSessionRepository;
    private final int batchSize;
    private final int maxBatchesPerRun;

    public CleanupAuthSessionsUseCase(AuthSessionRepository authSessionRepository, int batchSize, int maxBatchesPerRun) {
        this.authSessionRepository = Objects.requireNonNull(authSessionRepository, "authSessionRepository must not be null");
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        if (maxBatchesPerRun <= 0) {
            throw new IllegalArgumentException("maxBatchesPerRun must be positive");
        }
        this.batchSize = batchSize;
        this.maxBatchesPerRun = maxBatchesPerRun;
    }

    /**
     * @param now instante de referência para "ativa" e "expirada"
     * @return quantidades processadas em cada fase
     */
    public AuthSessionCleanupResult cleanup(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        Phase metadata = runPhase(() -> authSessionRepository.clearInactiveMetadata(now, batchSize));
        Phase purge = runPhase(() -> authSessionRepository.purgeExpiredWithoutSuccessor(now, batchSize));
        return new AuthSessionCleanupResult(metadata.affected(), metadata.batches(), metadata.limitReached(),
                purge.affected(), purge.batches(), purge.limitReached());
    }

    private Phase runPhase(BatchOperation operation) {
        long affected = 0;
        for (int batch = 1; batch <= maxBatchesPerRun; batch++) {
            int processed = operation.run();
            affected += processed;
            if (processed < batchSize) {
                return new Phase(affected, batch, false);
            }
        }
        return new Phase(affected, maxBatchesPerRun, true);
    }

    @FunctionalInterface
    private interface BatchOperation {
        int run();
    }

    private record Phase(long affected, int batches, boolean limitReached) {
    }

    /**
     * Resultado de uma passada do cleanup.
     *
     * @param metadataLimitReached true se a fase de metadados parou em {@code maxBatchesPerRun} com lote cheio
     * @param purgeLimitReached    true se o purge parou em {@code maxBatchesPerRun} com lote cheio
     */
    public record AuthSessionCleanupResult(
            long metadataCleared,
            int metadataBatches,
            boolean metadataLimitReached,
            long sessionsPurged,
            int purgeBatches,
            boolean purgeLimitReached
    ) {
    }
}
