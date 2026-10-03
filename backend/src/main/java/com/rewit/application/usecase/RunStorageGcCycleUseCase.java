package com.rewit.application.usecase;

import com.rewit.application.dto.storage.ObjectDeletionResult;
import com.rewit.application.dto.storage.StorageGcDtos.StorageGcCycleReport;
import com.rewit.application.dto.storage.StorageGcDtos.StorageGcLimits;
import com.rewit.application.dto.storage.StorageGcDtos.StorageGcRunStatus;
import com.rewit.application.dto.storage.StorageQuarantineDtos.QuarantineRecheckResult;
import com.rewit.application.dto.storage.StorageQuarantineDtos.QuarantinedStorageObject;
import com.rewit.application.dto.storage.StorageQuarantineDtos.StoragePurgeResult;
import com.rewit.application.dto.storage.StorageReconciliationDtos.StorageReconciliationReport;
import com.rewit.application.port.StorageGcCycleObserver;
import com.rewit.application.port.StorageGcExecutionLock;
import com.rewit.application.port.StorageQuarantineRepository;
import com.rewit.domain.enums.StorageQuarantineStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Ciclo operacional do GC de storage (Step 28.5):
 * listagem → reconciliação → quarentena → grace period → rechecagem → exclusão.
 *
 * <p>Apenas coordena os use cases existentes; locks, segunda consulta, grace period e ordem
 * storage → quarentena continuam neles. O ciclo inteiro roda sob {@link StorageGcExecutionLock}:
 * entre instâncias, só uma executa por vez.
 *
 * <p><b>Dry-run é estrutural:</b> a instância criada por {@link #dryRun} não possui o use case de exclusão,
 * então não há caminho para o storage delete. A fase de exclusão apenas conta o que seria tentado.
 *
 * <p>Retomada: o marcador {@code nextStartAfter} do Step 28.1 fica em memória entre ciclos desta
 * instância; ele não é persistido, e um reinício recomeça do início do prefixo. Listagem parcial nunca
 * é tratada como prova de ausência de objetos.
 *
 * <p>Classe livre de Spring, como os demais use cases operacionais.
 */
public class RunStorageGcCycleUseCase {

    private static final Logger log = LoggerFactory.getLogger(RunStorageGcCycleUseCase.class);

    private final StorageGcExecutionLock executionLock;
    private final ReconcileReviewMediaStorageUseCase reconcileUseCase;
    private final StorageQuarantineRepository quarantineRepository;
    private final RecheckQuarantinedStorageObjectUseCase recheckUseCase;
    private final PurgeConfirmedOrphanStorageObjectUseCase purgeUseCase;
    private final StorageGcCycleObserver observer;
    private final Clock clock;
    private final StorageGcLimits limits;
    private final AtomicReference<String> resumeMarker = new AtomicReference<>();

    private RunStorageGcCycleUseCase(StorageGcExecutionLock executionLock,
                                     ReconcileReviewMediaStorageUseCase reconcileUseCase,
                                     StorageQuarantineRepository quarantineRepository,
                                     RecheckQuarantinedStorageObjectUseCase recheckUseCase,
                                     PurgeConfirmedOrphanStorageObjectUseCase purgeUseCase,
                                     StorageGcCycleObserver observer,
                                     Clock clock,
                                     StorageGcLimits limits) {
        this.executionLock = Objects.requireNonNull(executionLock, "executionLock must not be null");
        this.reconcileUseCase = Objects.requireNonNull(reconcileUseCase, "reconcileUseCase must not be null");
        this.quarantineRepository = Objects.requireNonNull(quarantineRepository, "quarantineRepository must not be null");
        this.recheckUseCase = Objects.requireNonNull(recheckUseCase, "recheckUseCase must not be null");
        this.purgeUseCase = purgeUseCase;
        this.observer = Objects.requireNonNull(observer, "observer must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.limits = Objects.requireNonNull(limits, "limits must not be null");
    }

    /**
     * Ciclo sem exclusão física: lista, registra observações e recheca, mas não recebe o use case de exclusão.
     * O reconciliador deve listar uma página por chamada: o limite de páginas e o timeout são aplicados aqui.
     */
    public static RunStorageGcCycleUseCase dryRun(StorageGcExecutionLock executionLock,
                                                  ReconcileReviewMediaStorageUseCase reconcileUseCase,
                                                  StorageQuarantineRepository quarantineRepository,
                                                  RecheckQuarantinedStorageObjectUseCase recheckUseCase,
                                                  StorageGcCycleObserver observer,
                                                  Clock clock,
                                                  StorageGcLimits limits) {
        return new RunStorageGcCycleUseCase(executionLock, reconcileUseCase, quarantineRepository, recheckUseCase,
                null, observer, clock, limits);
    }

    /** Ciclo com exclusão física de CONFIRMED_ORPHAN, sempre via {@link PurgeConfirmedOrphanStorageObjectUseCase}. */
    public static RunStorageGcCycleUseCase destructive(StorageGcExecutionLock executionLock,
                                                       ReconcileReviewMediaStorageUseCase reconcileUseCase,
                                                       StorageQuarantineRepository quarantineRepository,
                                                       RecheckQuarantinedStorageObjectUseCase recheckUseCase,
                                                       PurgeConfirmedOrphanStorageObjectUseCase purgeUseCase,
                                                       StorageGcCycleObserver observer,
                                                       Clock clock,
                                                       StorageGcLimits limits) {
        return new RunStorageGcCycleUseCase(executionLock, reconcileUseCase, quarantineRepository, recheckUseCase,
                Objects.requireNonNull(purgeUseCase, "purgeUseCase must not be null"), observer, clock, limits);
    }

    public boolean isDryRun() {
        return purgeUseCase == null;
    }

    /** Executa um ciclo completo; usado pelo scheduler e por disparos internos de teste. */
    public StorageGcCycleReport runCycle() {
        Instant startedAt = clock.instant();
        Cycle cycle = new Cycle(startedAt);
        StorageGcCycleReport report;
        try {
            Optional<StorageGcCycleReport> executed = executionLock.runExclusively(() -> execute(cycle));
            report = executed.orElseGet(() -> cycle.finish(StorageGcRunStatus.SKIPPED_LOCKED));
        } catch (RuntimeException e) {
            // Lock ou banco indisponível fora das fases já tratadas: nenhuma exclusão adicional é tentada
            log.error("Storage GC: ciclo falhou por erro de infraestrutura (erro={})", e.getClass().getSimpleName());
            report = cycle.finish(StorageGcRunStatus.FAILED);
        }
        observer.recordCycle(report);
        logSummary(report);
        return report;
    }

    private StorageGcCycleReport execute(Cycle cycle) {
        log.info("Storage GC: ciclo iniciado (modo={})", isDryRun() ? "DRY_RUN, nenhuma exclusão será executada" : "DESTRUTIVO");

        StorageGcRunStatus listingStatus = listPages(cycle);
        if (listingStatus != null) {
            return cycle.finish(listingStatus);
        }
        StorageGcRunStatus recheckStatus = recheckObserved(cycle);
        if (recheckStatus != null) {
            return cycle.finish(recheckStatus);
        }
        StorageGcRunStatus deleteStatus = processConfirmed(cycle);
        if (deleteStatus != null) {
            return cycle.finish(deleteStatus);
        }
        return cycle.finish(cycle.limitReached || !cycle.listingComplete
                ? StorageGcRunStatus.PARTIAL : StorageGcRunStatus.COMPLETED);
    }

    // Fase 1: uma página por chamada ao reconciliador, com o marcador do Step 28.1
    private StorageGcRunStatus listPages(Cycle cycle) {
        String marker = resumeMarker.get();
        for (int page = 0; page < limits.maxPages(); page++) {
            if (deadlineReached(cycle)) {
                resumeMarker.set(marker);
                return StorageGcRunStatus.TIMED_OUT;
            }
            StorageReconciliationReport report;
            try {
                report = reconcileUseCase.reconcile(marker, clock.instant());
            } catch (IllegalArgumentException | IllegalStateException e) {
                // Marcador recusado ou listagem que não avança: recomeça do início no próximo ciclo, sem inferir nada
                resumeMarker.set(null);
                log.error("Storage GC: paginação inconsistente; a próxima listagem recomeça do início (erro={})",
                        e.getClass().getSimpleName());
                return StorageGcRunStatus.FAILED;
            } catch (RuntimeException e) {
                // Storage ou banco indisponível: sem rechecagem nem exclusão neste ciclo
                resumeMarker.set(marker);
                log.error("Storage GC: listagem/reconciliação falhou; ciclo interrompido (erro={})", e.getClass().getSimpleName());
                return StorageGcRunStatus.FAILED;
            }
            cycle.pagesScanned += report.pagesScanned();
            cycle.objectsListed += report.objectsExamined();
            cycle.objectsIgnored += report.outOfNamespaceIgnored() + report.unrecognizedKeys() + report.duplicatesIgnored();
            cycle.candidatesObserved += report.orphanCandidates().size();
            if (report.complete()) {
                cycle.listingComplete = true;
                resumeMarker.set(null);
                return null;
            }
            marker = report.nextStartAfter();
        }
        resumeMarker.set(marker);
        return null;
    }

    // Fase 2: rechecagem das observações mais antigas; o grace period é decidido pela política do use case
    private StorageGcRunStatus recheckObserved(Cycle cycle) {
        List<QuarantinedStorageObject> observed = quarantineRepository.findByStatus(
                StorageQuarantineStatus.OBSERVED, limits.maxCandidates());
        cycle.limitReached |= observed.size() >= limits.maxCandidates();
        for (QuarantinedStorageObject entry : observed) {
            if (deadlineReached(cycle)) {
                return StorageGcRunStatus.TIMED_OUT;
            }
            try {
                QuarantineRecheckResult result = recheckUseCase.recheck(entry.objectKey(), clock.instant());
                cycle.rechecks++;
                switch (result.outcome()) {
                    case GRACE_PERIOD_NOT_ELAPSED -> cycle.gracePending++;
                    case WITH_REFERENCE -> cycle.protectedByReference++;
                    case CONFIRMED_ORPHAN -> cycle.confirmedOrphans++;
                    case NOT_QUARANTINED -> { }
                }
                cycle.consecutiveFailures = 0;
            } catch (IllegalArgumentException e) {
                // Chave fora do formato gerenciado na quarentena: nunca segue adiante
                cycle.candidateErrors++;
            } catch (RuntimeException e) {
                cycle.candidateErrors++;
                if (++cycle.consecutiveFailures >= limits.maxConsecutiveFailures()) {
                    log.error("Storage GC: {} falhas consecutivas na rechecagem; ciclo interrompido (erro={})",
                            cycle.consecutiveFailures, e.getClass().getSimpleName());
                    return StorageGcRunStatus.ABORTED;
                }
            }
        }
        return null;
    }

    // Fase 3: exclusão (ou contagem, em dry-run) dos CONFIRMED_ORPHAN mais antigos
    private StorageGcRunStatus processConfirmed(Cycle cycle) {
        List<QuarantinedStorageObject> confirmed = quarantineRepository.findByStatus(
                StorageQuarantineStatus.CONFIRMED_ORPHAN, limits.maxDeletes());
        cycle.deleteCandidates = confirmed.size();
        cycle.limitReached |= confirmed.size() >= limits.maxDeletes();
        if (purgeUseCase == null) {
            cycle.wouldDelete = confirmed.size();
            return null;
        }
        cycle.consecutiveFailures = 0;
        for (QuarantinedStorageObject entry : confirmed) {
            if (deadlineReached(cycle)) {
                return StorageGcRunStatus.TIMED_OUT;
            }
            StoragePurgeResult result;
            try {
                result = purgeUseCase.purge(entry.objectKey());
            } catch (RuntimeException e) {
                cycle.candidateErrors++;
                if (++cycle.consecutiveFailures >= limits.maxConsecutiveFailures()) {
                    log.error("Storage GC: {} falhas consecutivas na exclusão; ciclo interrompido (erro={})",
                            cycle.consecutiveFailures, e.getClass().getSimpleName());
                    return StorageGcRunStatus.ABORTED;
                }
                continue;
            }
            if (result.deletionResult() != null) {
                cycle.deletesAttempted++;
            }
            switch (result.outcome()) {
                case PURGED -> {
                    if (result.deletionResult() == ObjectDeletionResult.NOT_FOUND) {
                        cycle.alreadyAbsent++;
                    } else {
                        cycle.deleted++;
                    }
                    cycle.consecutiveFailures = 0;
                }
                case WITH_REFERENCE -> {
                    cycle.protectedByReference++;
                    cycle.consecutiveFailures = 0;
                }
                case RETRYABLE_FAILURE -> {
                    cycle.retryableFailures++;
                    if (++cycle.consecutiveFailures >= limits.maxConsecutiveFailures()) {
                        log.error("Storage GC: {} falhas transitórias consecutivas do storage; ciclo interrompido",
                                cycle.consecutiveFailures);
                        return StorageGcRunStatus.ABORTED;
                    }
                }
                case PERMANENT_FAILURE -> {
                    // Credencial/configuração: as demais exclusões falhariam igual; candidato mantido em quarentena
                    cycle.permanentFailures++;
                    log.error("Storage GC: falha permanente do storage; exclusões interrompidas neste ciclo");
                    return StorageGcRunStatus.ABORTED;
                }
                case INVALID_OBJECT_KEY -> cycle.candidateErrors++;
                case NOT_QUARANTINED, NOT_CONFIRMED -> { }
            }
        }
        return null;
    }

    private boolean deadlineReached(Cycle cycle) {
        return !clock.instant().isBefore(cycle.startedAt.plus(limits.maxDuration()));
    }

    // Resumo agregado, sem chaves; o modo dry-run nunca é descrito como remoção
    private static void logSummary(StorageGcCycleReport r) {
        String deletions = r.dryRun()
                ? "exclusões que seriam tentadas=" + r.wouldDelete() + " (dry-run: nada foi removido)"
                : "exclusões tentadas=" + r.deletesAttempted() + ", removidos=" + r.deleted()
                        + ", já ausentes=" + r.alreadyAbsent() + ", falhas transitórias=" + r.retryableFailures()
                        + ", falhas permanentes=" + r.permanentFailures();
        log.info("Storage GC: ciclo encerrado status={} modo={} duracaoMs={} páginas={} examinados={} ignorados={} "
                        + "listagemCompleta={} observados={} rechecagens={} emGracePeriod={} protegidosPorReferencia={} "
                        + "confirmados={} candidatosExclusao={} {} errosDeCandidato={}",
                r.status(), r.dryRun() ? "DRY_RUN" : "DESTRUTIVO", r.duration().toMillis(), r.pagesScanned(),
                r.objectsListed(), r.objectsIgnored(), r.listingComplete(), r.candidatesObserved(), r.rechecks(),
                r.gracePending(), r.protectedByReference(), r.confirmedOrphans(), r.deleteCandidates(), deletions,
                r.candidateErrors());
    }

    /** Acumulador mutável de um único ciclo. */
    private final class Cycle {
        private final Instant startedAt;
        private int pagesScanned;
        private long objectsListed;
        private long objectsIgnored;
        private long candidatesObserved;
        private boolean listingComplete;
        private boolean limitReached;
        private long rechecks;
        private long gracePending;
        private long protectedByReference;
        private long confirmedOrphans;
        private long deleteCandidates;
        private long wouldDelete;
        private long deletesAttempted;
        private long deleted;
        private long alreadyAbsent;
        private long retryableFailures;
        private long permanentFailures;
        private long candidateErrors;
        private int consecutiveFailures;

        private Cycle(Instant startedAt) {
            this.startedAt = startedAt;
        }

        private StorageGcCycleReport finish(StorageGcRunStatus status) {
            Duration duration = Duration.between(startedAt, clock.instant());
            return new StorageGcCycleReport(status, isDryRun(), startedAt, duration.isNegative() ? Duration.ZERO : duration,
                    pagesScanned, objectsListed, objectsIgnored, candidatesObserved, listingComplete,
                    rechecks, gracePending, protectedByReference, confirmedOrphans, deleteCandidates, wouldDelete,
                    deletesAttempted, deleted, alreadyAbsent, retryableFailures, permanentFailures, candidateErrors);
        }
    }
}
