package com.rewit.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewit.application.port.OutboxRepository;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.OutboxStatus;
import com.rewit.domain.model.OutboxMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Testes de integração do Transactional Outbox contra PostgreSQL real (Step 27.1).
 *
 * <p>Nenhum método de teste usa {@code @Transactional}: cada operação roda em
 * transações próprias e comprometidas, garantindo round-trip real de JSONB e
 * rollback/commit genuínos. A tabela outbox_messages é nova e escrita apenas
 * por esta classe, por isso a limpeza via DELETE é determinística e não afeta
 * dados de outros domínios no banco compartilhado.
 */
@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Testes Reais de Persistência no PostgreSQL: Transactional Outbox (Step 27.1)")
class OutboxPersistenceIntegrationTest {

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void cleanOutboxBefore() {
        jdbcTemplate.update("DELETE FROM outbox_messages");
    }

    @AfterEach
    void cleanOutboxAfter() {
        jdbcTemplate.update("DELETE FROM outbox_messages");
    }

    // =========================================================================
    // L.1 Insert
    // =========================================================================

    @Test
    @DisplayName("L.1: enqueue persiste mensagem PENDING com attempts 0 e payload JSONB preservado")
    void shouldEnqueuePendingMessagePreservingJsonbPayload() throws Exception {
        String payload = "{\"notificationId\":\"" + UUID.randomUUID() + "\"}";

        OutboxMessage saved = outboxRepository.save(new OutboxMessage("PUSH_DELIVERY", payload));

        assertNotNull(saved.getId());
        assertEquals(OutboxStatus.PENDING, saved.getStatus());
        assertEquals(0, saved.getAttempts());

        Optional<OutboxMessage> found = outboxRepository.findById(saved.getId());
        assertTrue(found.isPresent());
        assertEquals("PUSH_DELIVERY", found.get().getMessageType());
        assertEquals(objectMapper.readTree(payload), objectMapper.readTree(found.get().getPayload()),
                "Payload JSONB deve sobreviver ao round-trip no PostgreSQL");
        assertEquals(OutboxStatus.PENDING, found.get().getStatus());
        assertEquals(0, found.get().getAttempts());
        assertNotNull(found.get().getCreatedAt());
        assertNotNull(found.get().getUpdatedAt());
        assertNull(found.get().getLockedAt());
        assertNull(found.get().getLockedBy());
    }

    @Test
    @DisplayName("Migration V12: chk_outbox_status rejeita status inválido no PostgreSQL")
    void shouldRejectInvalidStatusByCheckConstraint() {
        assertThrows(Exception.class, () -> jdbcTemplate.update(
                "INSERT INTO outbox_messages (message_type, payload, status) VALUES ('PUSH_DELIVERY', '{}', 'INVALID')"
        ));
    }

    @Test
    @DisplayName("Migration V12: chk_outbox_attempts rejeita attempts negativos no PostgreSQL")
    void shouldRejectNegativeAttemptsByCheckConstraint() {
        assertThrows(Exception.class, () -> jdbcTemplate.update(
                "INSERT INTO outbox_messages (message_type, payload, attempts) VALUES ('PUSH_DELIVERY', '{}', -1)"
        ));
    }

    // =========================================================================
    // L.2 Transaction rollback
    // =========================================================================

    @Test
    @DisplayName("L.2: rollback real da transação do produtor não persiste nenhuma mensagem")
    void shouldRollbackEnqueueAtomically() {
        long initial = countOutbox();

        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        assertThrows(RuntimeException.class, () ->
                tx.execute(status -> {
                    outboxRepository.save(new OutboxMessage("PUSH_DELIVERY", "{\"notificationId\":\"rollback\"}"));
                    throw new RuntimeException("Falha simulada: rollback do enqueue no outbox");
                })
        );

        assertEquals(initial, countOutbox(),
                "Nenhuma mensagem deve permanecer após rollback real no PostgreSQL");
    }

    // =========================================================================
    // L.3 Transaction commit
    // =========================================================================

    @Test
    @DisplayName("L.3: commit da transação do produtor torna a mensagem visível como PENDING")
    void shouldMakeMessageVisibleAfterProducerCommit() throws Exception {
        long initial = countOutbox();
        String payload = "{\"notificationId\":\"" + UUID.randomUUID() + "\"}";

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        UUID savedId = tx.execute(status ->
                outboxRepository.save(new OutboxMessage("PUSH_DELIVERY", payload)).getId());

        assertNotNull(savedId);
        assertEquals(initial + 1, countOutbox());

        Optional<OutboxMessage> found = outboxRepository.findById(savedId);
        assertTrue(found.isPresent());
        assertEquals(OutboxStatus.PENDING, found.get().getStatus());
        assertEquals(objectMapper.readTree(payload), objectMapper.readTree(found.get().getPayload()));
    }

    // =========================================================================
    // L.4 Claim
    // =========================================================================

    @Test
    @DisplayName("L.4: claim move apenas as mensagens elegíveis para PROCESSING com lock e attempts incrementado")
    void shouldClaimOnlyEligibleMessages() {
        OutboxMessage m1 = seedCommitted(300);
        OutboxMessage m2 = seedCommitted(240);
        OutboxMessage m3 = seedCommitted(180);
        TransactionTemplate seedTx = new TransactionTemplate(transactionManager);
        OutboxMessage future = seedTx.execute(status -> outboxRepository.save(futureMessage()));
        assertNotNull(future);

        assertEquals(4, countOutbox());
        assertEquals(4, outboxRepository.countByStatus(OutboxStatus.PENDING));

        List<OutboxMessage> claimed = outboxRepository.claimBatch(2, "worker-A");

        assertEquals(2, claimed.size());
        Set<UUID> claimedIds = claimed.stream()
                .map(message -> message.getId())
                .collect(Collectors.toSet());
        assertTrue(claimedIds.contains(m1.getId()), "Mensagem mais antiga deve ser reivindicada primeiro");
        assertTrue(claimedIds.contains(m2.getId()), "Segunda mensagem mais antiga deve ser reivindicada");
        assertFalse(claimedIds.contains(m3.getId()), "Terceira mensagem excede o lote de 2");

        for (OutboxMessage message : claimed) {
            assertEquals(OutboxStatus.PROCESSING, message.getStatus());
            assertEquals("worker-A", message.getLockedBy());
            assertNotNull(message.getLockedAt());
            assertEquals(1, message.getAttempts());
        }

        Optional<OutboxMessage> remaining = outboxRepository.findById(m3.getId());
        assertTrue(remaining.isPresent());
        assertEquals(OutboxStatus.PENDING, remaining.get().getStatus());
        assertNull(remaining.get().getLockedAt());
        assertNull(remaining.get().getLockedBy());
        assertEquals(0, remaining.get().getAttempts());

        Optional<OutboxMessage> futureReloaded = outboxRepository.findById(future.getId());
        assertTrue(futureReloaded.isPresent());
        assertEquals(OutboxStatus.PENDING, futureReloaded.get().getStatus(),
                "Mensagem com next_attempt_at futuro não é elegível para claim");

        assertEquals(2, outboxRepository.countByStatus(OutboxStatus.PENDING));
        assertEquals(2, outboxRepository.countByStatus(OutboxStatus.PROCESSING));
        assertEquals(0, outboxRepository.countByStatus(OutboxStatus.COMPLETED));
    }

    @Test
    @DisplayName("claimBatch valida contrato: lote positivo e identificador de worker obrigatório")
    void shouldValidateClaimBatchArguments() {
        BusinessException exBatch = assertThrows(BusinessException.class, () ->
                outboxRepository.claimBatch(0, "worker-A"));
        assertEquals("INVALID_BATCH_SIZE", exBatch.getErrorCode());

        BusinessException exWorker = assertThrows(BusinessException.class, () ->
                outboxRepository.claimBatch(5, "   "));
        assertEquals("MISSING_WORKER_ID", exWorker.getErrorCode());
    }

    // =========================================================================
    // L.5 SKIP LOCKED real
    // =========================================================================

    @Test
    @DisplayName("L.5: dois workers concorrentes reivindicam conjuntos disjuntos via FOR UPDATE SKIP LOCKED")
    void shouldSkipLockedRowsUnderConcurrentWorkers() throws Exception {
        OutboxMessage m1 = seedCommitted(120);
        OutboxMessage m2 = seedCommitted(90);
        OutboxMessage m3 = seedCommitted(60);
        OutboxMessage m4 = seedCommitted(30);
        TransactionTemplate seedTx = new TransactionTemplate(transactionManager);
        OutboxMessage future = seedTx.execute(status -> outboxRepository.save(futureMessage()));
        assertNotNull(future);

        Set<UUID> eligibleIds = Set.of(m1.getId(), m2.getId(), m3.getId(), m4.getId());

        CountDownLatch workerALocked = new CountDownLatch(1);
        CountDownLatch workerBDone = new CountDownLatch(1);
        AtomicReference<List<OutboxMessage>> claimedByA = new AtomicReference<>();
        AtomicReference<List<OutboxMessage>> claimedByB = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread workerA = new Thread(() -> {
            TransactionStatus status = transactionManager.getTransaction(new DefaultTransactionDefinition());
            try {
                claimedByA.set(outboxRepository.claimBatch(2, "worker-A"));
                workerALocked.countDown();
                if (!workerBDone.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("worker-B não concluiu o claim a tempo");
                }
                transactionManager.commit(status);
            } catch (Throwable e) {
                failure.set(e);
                transactionManager.rollback(status);
                workerALocked.countDown();
            }
        }, "outbox-worker-A");

        Thread workerB = new Thread(() -> {
            try {
                if (!workerALocked.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("worker-A não travou as linhas a tempo");
                }
                TransactionTemplate tx = new TransactionTemplate(transactionManager);
                claimedByB.set(tx.execute(s -> outboxRepository.claimBatch(2, "worker-B")));
            } catch (Throwable e) {
                failure.set(e);
            } finally {
                workerBDone.countDown();
            }
        }, "outbox-worker-B");

        workerA.start();
        workerB.start();
        workerA.join(15000);
        workerB.join(15000);

        assertNull(failure.get(), "Nenhum worker deve falhar: " + failure.get());
        List<OutboxMessage> batchA = claimedByA.get();
        List<OutboxMessage> batchB = claimedByB.get();
        assertNotNull(batchA);
        assertNotNull(batchB);
        assertEquals(2, batchA.size());
        assertEquals(2, batchB.size());

        Set<UUID> idsA = batchA.stream().map(message -> message.getId()).collect(Collectors.toSet());
        Set<UUID> idsB = batchB.stream().map(message -> message.getId()).collect(Collectors.toSet());
        assertTrue(Collections.disjoint(idsA, idsB),
                "SKIP LOCKED deve impedir que a mesma linha seja reivindicada por dois workers");

        Set<UUID> union = new HashSet<>(idsA);
        union.addAll(idsB);
        assertEquals(eligibleIds, union, "Os dois lotes devem cobrir todas as mensagens elegíveis");

        for (UUID id : eligibleIds) {
            Optional<OutboxMessage> reloaded = outboxRepository.findById(id);
            assertTrue(reloaded.isPresent());
            assertEquals(OutboxStatus.PROCESSING, reloaded.get().getStatus());
            assertEquals(1, reloaded.get().getAttempts());
            assertNotNull(reloaded.get().getLockedAt());
            if (idsA.contains(id)) {
                assertEquals("worker-A", reloaded.get().getLockedBy());
            } else {
                assertEquals("worker-B", reloaded.get().getLockedBy());
            }
        }

        Optional<OutboxMessage> futureReloaded = outboxRepository.findById(future.getId());
        assertTrue(futureReloaded.isPresent());
        assertEquals(OutboxStatus.PENDING, futureReloaded.get().getStatus());
        assertEquals(0, futureReloaded.get().getAttempts());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private long countOutbox() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox_messages", Long.class);
    }

    private OutboxMessage eligibleMessage(long secondsAgo) {
        Instant eligibleAt = Instant.now().minusSeconds(secondsAgo);
        return new OutboxMessage(
                UUID.randomUUID(), "PUSH_DELIVERY", "{\"notificationId\":\"" + UUID.randomUUID() + "\"}",
                OutboxStatus.PENDING, 0, eligibleAt, null, null, null, eligibleAt, eligibleAt);
    }

    private OutboxMessage futureMessage() {
        Instant futureAt = Instant.now().plusSeconds(3600);
        Instant now = Instant.now();
        return new OutboxMessage(
                UUID.randomUUID(), "PUSH_DELIVERY", "{\"notificationId\":\"" + UUID.randomUUID() + "\"}",
                OutboxStatus.PENDING, 0, futureAt, null, null, null, now, now);
    }

    private OutboxMessage seedCommitted(long secondsAgo) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        OutboxMessage saved = tx.execute(status -> outboxRepository.save(eligibleMessage(secondsAgo)));
        assertNotNull(saved);
        return saved;
    }
}
