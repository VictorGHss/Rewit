package com.rewit.infrastructure.storagegc;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Testes de Integração com PostgreSQL Real: lock global do storage GC (Step 28.5)")
class StorageGcExecutionLockIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("Duas instâncias: enquanto uma executa o ciclo, a outra é ignorada (inclusive de outra thread)")
    void onlyOneInstanceRunsAtATime() throws Exception {
        PostgresAdvisoryStorageGcExecutionLock instanceA = new PostgresAdvisoryStorageGcExecutionLock(dataSource);
        PostgresAdvisoryStorageGcExecutionLock instanceB = new PostgresAdvisoryStorageGcExecutionLock(dataSource);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Optional<String> outer = instanceA.runExclusively(() -> {
                assertEquals(1, heldGcLocks());
                assertTrue(instanceB.runExclusively(() -> "B").isEmpty());
                try {
                    assertTrue(executor.submit(() -> instanceB.runExclusively(() -> "B")).get(10, TimeUnit.SECONDS).isEmpty());
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                return "A";
            });
            assertEquals(Optional.of("A"), outer);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("Lock liberado após sucesso e após falha; nenhuma conexão fica presa no pool")
    void lockIsReleasedAfterSuccessAndFailure() {
        PostgresAdvisoryStorageGcExecutionLock instanceA = new PostgresAdvisoryStorageGcExecutionLock(dataSource);
        PostgresAdvisoryStorageGcExecutionLock instanceB = new PostgresAdvisoryStorageGcExecutionLock(dataSource);
        int activeBefore = activeConnections();

        assertEquals(Optional.of(1), instanceA.runExclusively(() -> 1));
        assertEquals(0, heldGcLocks());
        assertEquals(Optional.of(2), instanceB.runExclusively(() -> 2));

        assertThrows(IllegalStateException.class, () -> instanceA.runExclusively(() -> {
            throw new IllegalStateException("falha no ciclo");
        }));
        assertEquals(0, heldGcLocks());
        assertEquals(Optional.of(3), instanceB.runExclusively(() -> 3));
        assertEquals(activeBefore, activeConnections());
    }

    @Test
    @DisplayName("O lock do GC não bloqueia operações de usuário sobre reviews")
    void gcLockDoesNotBlockUserOperations() {
        PostgresAdvisoryStorageGcExecutionLock lock = new PostgresAdvisoryStorageGcExecutionLock(dataSource);

        Optional<Integer> locked = lock.runExclusively(() -> new TransactionTemplate(transactionManager).execute(status -> {
            jdbcTemplate.execute("SET LOCAL lock_timeout = '2s'");
            return jdbcTemplate.queryForList("SELECT id FROM reviews LIMIT 1 FOR UPDATE").size();
        }));

        assertTrue(locked.isPresent());
    }

    private int heldGcLocks() {
        Integer held = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM pg_locks
                WHERE locktype = 'advisory' AND granted
                  AND ((classid::bigint << 32) | objid::bigint) = ?
                """, Integer.class, PostgresAdvisoryStorageGcExecutionLock.LOCK_KEY);
        return held == null ? 0 : held;
    }

    private int activeConnections() {
        return ((HikariDataSource) dataSource).getHikariPoolMXBean().getActiveConnections();
    }
}
