package com.rewit.infrastructure.outbox;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.rewit.application.outbox.OutboxErrorSanitizer;
import com.rewit.application.outbox.OutboxRetryPolicy;
import com.rewit.application.outbox.OutboxTransientException;
import com.rewit.application.port.OutboxHandler;
import com.rewit.application.port.OutboxRepository;
import com.rewit.application.usecase.ProcessOutboxBatchUseCase;
import com.rewit.application.usecase.ProcessOutboxBatchUseCase.ProcessOutboxBatchResult;
import com.rewit.application.usecase.PurgeCompletedOutboxUseCase;
import com.rewit.domain.enums.OutboxStatus;
import com.rewit.domain.model.OutboxMessage;

/**
 * Testes de integração do dispatcher do Outbox contra PostgreSQL real (Step 27.2).
 *
 * <p>O use case é montado manualmente com o repositório real do contexto e um
 * handler de teste, provando o wiring da configuração e os limites transacionais
 * reais. Nenhum método de teste usa {@code @Transactional}: toda operação roda
 * em transações próprias e comprometidas. A tabela outbox_messages é escrita
 * apenas pelo domínio do outbox, por isso o DELETE de limpeza é determinístico
 * no banco compartilhado.
 */
@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Testes Reais de Integração no PostgreSQL: Dispatcher do Outbox (Step 27.2)")
class OutboxDispatcherIntegrationTest {

    private static final Duration LEASE_DURATION = Duration.ofMinutes(2);

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private OutboxRetryPolicy outboxRetryPolicy;

    @Autowired
    private OutboxErrorSanitizer outboxErrorSanitizer;

    @Autowired
    private WorkerIdProvider workerIdProvider;

    @Autowired
    private OutboxMetrics outboxMetrics;

    @Autowired
    private PurgeCompletedOutboxUseCase purgeCompletedOutboxUseCase;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final String testWorkerId = "worker-integration-" + UUID.randomUUID().toString().substring(0, 8);

    private RecordingHandler recordingHandler;
    private ProcessOutboxBatchUseCase useCase;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM outbox_messages");
        recordingHandler = new RecordingHandler();
        useCase = new ProcessOutboxBatchUseCase(
                outboxRepository,
                List.of(recordingHandler),
                outboxRetryPolicy,
                outboxErrorSanitizer,
                testWorkerId,
                10,
                LEASE_DURATION);
    }

    @AfterEach
    void cleanOutboxAfter() {
        jdbcTemplate.update("DELETE FROM outbox_messages");
    }

    // =========================================================================
    // V.1 / V.2 — Reclaim de leases
    // =========================================================================

    @Test
    @DisplayName("V.1: lease expirada é recuperada atomicamente para PENDING com locks limpos e attempts preservados")
    void shouldReclaimExpiredLease() {
        OutboxMessage message = seedProcessing("worker-A", 300, 3);

        int reclaimed = outboxRepository.reclaimExpiredLeases(Instant.now(), LEASE_DURATION);

        assertEquals(1, reclaimed);
        Optional<OutboxMessage> reloaded = outboxRepository.findById(message.getId());
        assertTrue(reloaded.isPresent());
        assertEquals(OutboxStatus.PENDING, reloaded.get().getStatus());
        assertNull(reloaded.get().getLockedAt(), "Reclaim deve limpar locked_at");
        assertNull(reloaded.get().getLockedBy(), "Reclaim deve limpar locked_by");
        assertEquals(3, reloaded.get().getAttempts(), "Reclaim preserva attempts");
        assertTrue(reloaded.get().getNextAttemptAt().isAfter(Instant.now().minusSeconds(5)),
                "Mensagem recuperada deve ficar imediatamente elegível para o próximo claim");
    }

    @Test
    @DisplayName("V.2: lease ainda válida NÃO é recuperada — estado PROCESSING permanece intacto")
    void shouldNotReclaimValidLease() {
        OutboxMessage message = seedProcessing("worker-A", 30, 1);

        int reclaimed = outboxRepository.reclaimExpiredLeases(Instant.now(), LEASE_DURATION);

        assertEquals(0, reclaimed);
        Optional<OutboxMessage> reloaded = outboxRepository.findById(message.getId());
        assertTrue(reloaded.isPresent());
        assertEquals(OutboxStatus.PROCESSING, reloaded.get().getStatus());
        assertEquals("worker-A", reloaded.get().getLockedBy());
        assertEquals(1, reloaded.get().getAttempts());
    }

    // =========================================================================
    // V.3 — Claim concorrente sem duplicados
    // =========================================================================

    @Test
    @DisplayName("V.3: dois workers reivindicando concorrentemente não geram duplicados nem sobrescrevem o lock alheio")
    void shouldClaimConcurrentlyWithoutDuplicates() throws Exception {
        OutboxMessage m1 = seedEligibleCommitted(120, 0);
        OutboxMessage m2 = seedEligibleCommitted(90, 0);
        OutboxMessage m3 = seedEligibleCommitted(60, 0);
        OutboxMessage m4 = seedEligibleCommitted(30, 0);
        List<UUID> eligibleIds = List.of(m1.getId(), m2.getId(), m3.getId(), m4.getId());

        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch bothDone = new CountDownLatch(2);
        AtomicReference<List<OutboxMessage>> batchA = new AtomicReference<>();
        AtomicReference<List<OutboxMessage>> batchB = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Runnable workerTaskA = () -> {
            try {
                startGate.await(10, TimeUnit.SECONDS);
                TransactionTemplate tx = new TransactionTemplate(transactionManager);
                batchA.set(tx.execute(s -> outboxRepository.claimBatch(2, "worker-A")));
            } catch (Throwable e) {
                failure.set(e);
            } finally {
                bothDone.countDown();
            }
        };
        Runnable workerTaskB = () -> {
            try {
                startGate.await(10, TimeUnit.SECONDS);
                TransactionTemplate tx = new TransactionTemplate(transactionManager);
                batchB.set(tx.execute(s -> outboxRepository.claimBatch(2, "worker-B")));
            } catch (Throwable e) {
                failure.set(e);
            } finally {
                bothDone.countDown();
            }
        };

        Thread threadA = new Thread(workerTaskA, "dispatcher-A");
        Thread threadB = new Thread(workerTaskB, "dispatcher-B");
        threadA.start();
        threadB.start();
        startGate.countDown();
        assertTrue(bothDone.await(15, TimeUnit.SECONDS), "Os dois workers devem concluir o claim");
        threadA.join(5000);
        threadB.join(5000);

        assertNull(failure.get(), "Nenhum worker deve falhar: " + failure.get());
        List<OutboxMessage> claimedByA = batchA.get();
        List<OutboxMessage> claimedByB = batchB.get();
        assertNotNull(claimedByA);
        assertNotNull(claimedByB);

        List<UUID> allClaimedIds = new ArrayList<>();
        claimedByA.forEach(message -> allClaimedIds.add(message.getId()));
        claimedByB.forEach(message -> allClaimedIds.add(message.getId()));
        assertEquals(allClaimedIds.size(), allClaimedIds.stream().distinct().count(),
                "Nenhuma mensagem pode ser reivindicada duas vezes");

        for (UUID id : eligibleIds) {
            Optional<OutboxMessage> reloaded = outboxRepository.findById(id);
            assertTrue(reloaded.isPresent());
            if (allClaimedIds.contains(id)) {
                assertEquals(OutboxStatus.PROCESSING, reloaded.get().getStatus());
                assertEquals(1, reloaded.get().getAttempts(), "Claim único incrementa attempts para 1");
                assertTrue("worker-A".equals(reloaded.get().getLockedBy())
                                || "worker-B".equals(reloaded.get().getLockedBy()),
                        "locked_by deve indicar exatamente um dos dois workers");
            } else {
                assertEquals(OutboxStatus.PENDING, reloaded.get().getStatus());
            }
        }
    }

    // =========================================================================
    // V.4 / V.5 — Finalização owner-checked
    // =========================================================================

    @Test
    @DisplayName("V.4: o dono da lease finaliza com sucesso e a mensagem encerra como COMPLETED sem lock")
    void shouldFinalizeByLeaseOwner() {
        OutboxMessage message = seedProcessing("worker-A", 10, 1);

        boolean applied = outboxRepository.markCompleted(message.getId(), "worker-A", Instant.now());

        assertTrue(applied);
        Optional<OutboxMessage> reloaded = outboxRepository.findById(message.getId());
        assertTrue(reloaded.isPresent());
        assertEquals(OutboxStatus.COMPLETED, reloaded.get().getStatus());
        assertNull(reloaded.get().getLockedAt());
        assertNull(reloaded.get().getLockedBy());
    }

    @Test
    @DisplayName("V.5: worker sem posse é rejeitado nas três finalizações sem alterar nenhum estado da mensagem")
    void shouldRejectFinalizationByNonOwnerWithoutAlteringState() {
        OutboxMessage message = seedProcessing("worker-A", 10, 2);

        boolean completedRejected = outboxRepository.markCompleted(message.getId(), "worker-B", Instant.now());
        boolean failedRejected = outboxRepository.markFailed(message.getId(), "worker-B", "erro de worker alheio", Instant.now());
        boolean retryRejected = outboxRepository.scheduleRetry(
                message.getId(), "worker-B", "erro de worker alheio", Instant.now().plusSeconds(60), Instant.now());

        assertFalse(completedRejected);
        assertFalse(failedRejected);
        assertFalse(retryRejected);
        Optional<OutboxMessage> reloaded = outboxRepository.findById(message.getId());
        assertTrue(reloaded.isPresent());
        assertEquals(OutboxStatus.PROCESSING, reloaded.get().getStatus(), "Estado não pode ser alterado por não-dono");
        assertEquals("worker-A", reloaded.get().getLockedBy());
        assertEquals(2, reloaded.get().getAttempts());
        assertNull(reloaded.get().getLastError());
    }

    // =========================================================================
    // V.6 — Corrida de lease expirada (A claim → expira → B herda → A finalize NÃO sobrescreve)
    // =========================================================================

    @Test
    @DisplayName("V.6: após a lease de A expirar e B herdar a mensagem, a finalização atrasada de A não sobrescreve o estado de B")
    void shouldNotLetDelayedOwnerOverwriteNewOwnerState() {
        OutboxMessage message = seedProcessing("worker-A", 300, 1);

        int reclaimed = outboxRepository.reclaimExpiredLeases(Instant.now(), LEASE_DURATION);
        assertEquals(1, reclaimed);

        List<OutboxMessage> claimedByB = outboxRepository.claimBatch(1, "worker-B");
        assertEquals(1, claimedByB.size());
        assertEquals("worker-B", claimedByB.get(0).getLockedBy());
        assertEquals(2, claimedByB.get(0).getAttempts(), "Cada claim incrementa attempts (1 de A + 1 de B)");

        boolean delayedOwnerApplied = outboxRepository.markCompleted(message.getId(), "worker-A", Instant.now());
        assertFalse(delayedOwnerApplied, "Finalização do worker que perdeu a posse não pode ser aplicada");

        Optional<OutboxMessage> afterDelayedAttempt = outboxRepository.findById(message.getId());
        assertTrue(afterDelayedAttempt.isPresent());
        assertEquals(OutboxStatus.PROCESSING, afterDelayedAttempt.get().getStatus());
        assertEquals("worker-B", afterDelayedAttempt.get().getLockedBy(), "Estado herdado por B permanece intacto");
        assertEquals(2, afterDelayedAttempt.get().getAttempts());

        boolean currentOwnerApplied = outboxRepository.markCompleted(message.getId(), "worker-B", Instant.now());
        assertTrue(currentOwnerApplied);
        Optional<OutboxMessage> reloaded = outboxRepository.findById(message.getId());
        assertTrue(reloaded.isPresent());
        assertEquals(OutboxStatus.COMPLETED, reloaded.get().getStatus());
    }

    // =========================================================================
    // U.11 — Processamento FORA da transação (mecanismo transacional real)
    // =========================================================================

    @Test
    @DisplayName("U.11: handler executa FORA de qualquer transação — controle positivo dentro de TransactionTemplate prova o mecanismo")
    void shouldProcessHandlerOutsideAnyTransaction() {
        OutboxMessage message = seedEligibleCommitted(60, 0);

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        Boolean insideTx = tx.execute(status -> TransactionSynchronizationManager.isActualTransactionActive());
        assertEquals(Boolean.TRUE, insideTx, "Controle positivo: dentro de transação real a flag deve estar ativa");

        ProcessOutboxBatchResult result = useCase.processPendingBatch();

        assertEquals(1, result.completedCount());
        assertFalse(recordingHandler.transactionActiveDuringHandle.get(),
                "Handler deve executar com nenhuma transação PostgreSQL aberta");
        assertTrue(recordingHandler.handledIds.contains(message.getId()));
    }

    // =========================================================================
    // Ciclos completos ponta a ponta (retry, esgotamento, poller)
    // =========================================================================

    @Test
    @DisplayName("Ciclo completo: falha TRANSIENTE devolve a mensagem a PENDING com next_attempt_at futuro, lock limpo e attempts preservado")
    void shouldScheduleRetryThroughFullCycle() {
        OutboxMessage message = seedEligibleCommitted(60, 0);
        recordingHandler.failureToThrow = new OutboxTransientException("provider indisponível");

        ProcessOutboxBatchResult result = useCase.processPendingBatch();

        assertEquals(1, result.retriedCount());
        Optional<OutboxMessage> reloaded = outboxRepository.findById(message.getId());
        assertTrue(reloaded.isPresent());
        assertEquals(OutboxStatus.PENDING, reloaded.get().getStatus());
        assertNull(reloaded.get().getLockedAt());
        assertNull(reloaded.get().getLockedBy());
        assertEquals(1, reloaded.get().getAttempts(), "Retry preserva o attempts do claim");
        assertTrue(reloaded.get().getNextAttemptAt().isAfter(Instant.now()),
                "next_attempt_at deve ficar no futuro (backoff)");
        assertNotNull(reloaded.get().getLastError());
        assertFalse(reloaded.get().getLastError().contains("\n"),
                "last_error sanitizado deve ser linha única");
    }

    @Test
    @DisplayName("Ciclo completo: tentativas esgotadas encerram em FAILED terminal com last_error sanitizado e sem lock")
    void shouldFailAfterAttemptsExhaustedThroughFullCycle() {
        OutboxMessage message = seedEligibleCommitted(60, 5);
        recordingHandler.failureToThrow = new OutboxTransientException("provider definitivamente fora");

        ProcessOutboxBatchResult result = useCase.processPendingBatch();

        assertEquals(1, result.failedCount(), "Claim leva attempts a 6, acima do maxAttempts=5 do default");
        Optional<OutboxMessage> reloaded = outboxRepository.findById(message.getId());
        assertTrue(reloaded.isPresent());
        assertEquals(OutboxStatus.FAILED, reloaded.get().getStatus());
        assertEquals(6, reloaded.get().getAttempts());
        assertNotNull(reloaded.get().getLastError());
        assertTrue(reloaded.get().getLastError().contains("provider definitivamente fora"));
        assertNull(reloaded.get().getLockedAt());
        assertNull(reloaded.get().getLockedBy());
    }

    @Test
    @DisplayName("W-integração: poller instanciado com o use case real processa a mensagem até COMPLETED (sem relógio real)")
    void shouldProcessThroughPollerWithRealUseCase() {
        OutboxMessage message = seedEligibleCommitted(60, 0);

        OutboxDispatcherPoller poller = new OutboxDispatcherPoller(useCase, outboxMetrics);
        poller.dispatchPendingMessages();

        Optional<OutboxMessage> reloaded = outboxRepository.findById(message.getId());
        assertTrue(reloaded.isPresent());
        assertEquals(OutboxStatus.COMPLETED, reloaded.get().getStatus());
    }

    @Test
    @DisplayName("Retenção: purge PostgreSQL remove apenas COMPLETED antigos e preserva estados ativos/FAILED")
    void shouldPurgeOnlyOldCompletedMessages() {
        Instant now = Instant.now();
        UUID oldCompleted = seedRawOutbox("COMPLETED", now.minus(Duration.ofDays(31)), null);
        UUID recentCompleted = seedRawOutbox("COMPLETED", now.minus(Duration.ofDays(1)), null);
        UUID oldFailed = seedRawOutbox("FAILED", now.minus(Duration.ofDays(31)), null);
        UUID oldPending = seedRawOutbox("PENDING", now.minus(Duration.ofDays(31)), null);
        UUID activeProcessing = seedRawOutbox("PROCESSING", now.minus(Duration.ofDays(31)), now.minusSeconds(10));

        assertEquals(1, purgeCompletedOutboxUseCase.purgeExpired(now));
        assertEquals(0, countById(oldCompleted));
        assertEquals(1, countById(recentCompleted));
        assertEquals(1, countById(oldFailed));
        assertEquals(1, countById(oldPending));
        assertEquals(1, countById(activeProcessing));
    }

    @Test
    @DisplayName("Retenção: purge respeita limite de lote")
    void shouldRespectPurgeBatchLimit() {
        Instant now = Instant.now();
        seedRawOutbox("COMPLETED", now.minus(Duration.ofDays(31)), null);
        seedRawOutbox("COMPLETED", now.minus(Duration.ofDays(32)), null);

        PurgeCompletedOutboxUseCase singleRowPurge = new PurgeCompletedOutboxUseCase(
                outboxRepository, Duration.ofDays(30), 1);

        assertEquals(1, singleRowPurge.purgeExpired(now));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_messages WHERE status = 'COMPLETED'", Integer.class));
    }

    @Test
    @DisplayName("WorkerIdProvider: identificador estável no formato hostname-pid-uuid e dentro do limite da coluna")
    void shouldProvideStableWorkerIdWithinColumnLimit() {
        String workerId = workerIdProvider.getWorkerId();

        assertNotNull(workerId);
        assertFalse(workerId.isBlank());
        assertTrue(workerId.length() <= 128, "locked_by é VARCHAR(128)");
        assertEquals(workerId, workerIdProvider.getWorkerId(), "Identificador deve ser estável por instância");
        assertTrue(workerId.contains("-"), "Formato hostname-pid-uuid");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private OutboxMessage seedProcessing(String workerId, long lockedAtSecondsAgo, int attempts) {
        Instant now = Instant.now();
        Instant lockedAt = now.minusSeconds(lockedAtSecondsAgo);
        OutboxMessage message = new OutboxMessage(
                UUID.randomUUID(), "PUSH_DELIVERY", "{\"notificationId\":\"" + UUID.randomUUID() + "\"}",
                OutboxStatus.PROCESSING, attempts, now, lockedAt, workerId, null, now, now);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        OutboxMessage saved = tx.execute(status -> outboxRepository.save(message));
        assertNotNull(saved);
        return saved;
    }

    private OutboxMessage seedEligibleCommitted(long secondsAgo, int attempts) {
        Instant eligibleAt = Instant.now().minusSeconds(secondsAgo);
        OutboxMessage message = new OutboxMessage(
                UUID.randomUUID(), "PUSH_DELIVERY", "{\"notificationId\":\"" + UUID.randomUUID() + "\"}",
                OutboxStatus.PENDING, attempts, eligibleAt, null, null, null, eligibleAt, eligibleAt);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        OutboxMessage saved = tx.execute(status -> outboxRepository.save(message));
        assertNotNull(saved);
        return saved;
    }

    private UUID seedRawOutbox(String status, Instant updatedAt, Instant lockedAt) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO outbox_messages
                    (id, message_type, payload, status, attempts, next_attempt_at,
                     locked_at, locked_by, last_error, created_at, updated_at)
                VALUES (?, 'PUSH_DELIVERY', '{}', ?, 0, ?::timestamptz, ?::timestamptz, ?::varchar, NULL, ?::timestamptz, ?::timestamptz)
                """,
                id,
                status,
                Timestamp.from(updatedAt),
                lockedAt == null ? null : Timestamp.from(lockedAt),
                lockedAt == null ? null : "test-worker",
                Timestamp.from(updatedAt),
                Timestamp.from(updatedAt));
        return id;
    }

    private int countById(UUID id) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_messages WHERE id = ?", Integer.class, id);
    }

    private static final class RecordingHandler implements OutboxHandler {

        private final List<UUID> handledIds = new ArrayList<>();
        private final AtomicBoolean transactionActiveDuringHandle = new AtomicBoolean(false);
        private volatile RuntimeException failureToThrow;

        @Override
        public boolean supports(String messageType) {
            return true;
        }

        @Override
        public void handle(OutboxMessage message) {
            transactionActiveDuringHandle.set(TransactionSynchronizationManager.isActualTransactionActive());
            handledIds.add(message.getId());
            if (failureToThrow != null) {
                throw failureToThrow;
            }
        }
    }
}
