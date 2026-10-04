package com.rewit.infrastructure.authsession;

import com.rewit.application.dto.auth.AuthDtos.RefreshCommand;
import com.rewit.application.port.AuthSessionRepository;
import com.rewit.application.port.TokenService;
import com.rewit.application.port.UserRepository;
import com.rewit.application.service.AuthService;
import com.rewit.application.usecase.CleanupAuthSessionsUseCase;
import com.rewit.common.exception.RefreshTokenReuseDetectedException;
import com.rewit.domain.enums.AuthProvider;
import com.rewit.domain.model.User;
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
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cleanup de auth_sessions contra PostgreSQL real (Step 29.3). Cada cenário usa uma janela de tempo própria e
 * antiga e passa esse instante como "agora": só as linhas criadas pelo cenário são elegíveis.
 */
@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Testes de Integração com PostgreSQL Real: cleanup de auth_sessions (Step 29.3)")
class AuthSessionCleanupIntegrationTest {

    private static final Duration DAY = Duration.ofDays(1);

    @Autowired
    private AuthSessionRepository authSessionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AuthService authService;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("Elegibilidade: só sai a sessão expirada sem sucessora; ativa, revogada no prazo e antecessora ficam")
    void onlyExpiredSessionsWithoutSuccessorArePurged() {
        Instant now = Instant.parse("1995-01-01T00:00:00Z");
        UUID user = createUser();
        UUID active = insert(user, now.plus(DAY), null, null);
        UUID expired = insert(user, now.minus(DAY), null, null);
        UUID revokedExpired = insert(user, now.minus(DAY), now.minus(DAY.multipliedBy(2)), null);
        UUID revokedValid = insert(user, now.plus(DAY), now.minusSeconds(3600), null);
        UUID expiredWithSuccessor = insert(user, now.minus(DAY), now.minus(DAY.multipliedBy(2)), active);

        useCase(100, 10).cleanup(now);

        assertTrue(exists(active));
        assertFalse(exists(expired));
        assertFalse(exists(revokedExpired));
        assertTrue(exists(revokedValid));
        assertTrue(exists(expiredWithSuccessor));
    }

    @Test
    @DisplayName("Cadeia A→B→C→D: preservada enquanto D vive; depois desmontada um elo por lote, sem recursão")
    void rotationChainIsUnwoundLinkByLink() {
        Instant now = Instant.parse("1996-01-01T00:00:00Z");
        UUID user = createUser();
        UUID d = insert(user, now.plus(DAY), null, null);
        UUID c = insert(user, now.minus(DAY), now.minus(DAY.multipliedBy(2)), d);
        UUID b = insert(user, now.minus(DAY.multipliedBy(2)), now.minus(DAY.multipliedBy(3)), c);
        UUID a = insert(user, now.minus(DAY.multipliedBy(3)), now.minus(DAY.multipliedBy(4)), b);
        CleanupAuthSessionsUseCase oneBatchPerRun = useCase(100, 1);

        oneBatchPerRun.cleanup(now);
        assertTrue(List.of(a, b, c, d).stream().allMatch(this::exists), "D viva: nenhum elo é removido");

        Instant afterD = now.plus(DAY.multipliedBy(2));
        oneBatchPerRun.cleanup(afterD);
        assertFalse(exists(d));
        assertTrue(exists(c), "C só fica elegível depois que o SET NULL da remoção de D é commitado");
        assertNull(replacedBy(c));

        oneBatchPerRun.cleanup(afterD);
        assertFalse(exists(c));
        assertNull(replacedBy(b));
        assertTrue(exists(a));

        oneBatchPerRun.cleanup(afterD);
        assertFalse(exists(b));
        oneBatchPerRun.cleanup(afterD);
        assertFalse(exists(a));
    }

    @Test
    @DisplayName("Metadados: ativa mantém IP/User-Agent; revogada, expirada e ancestrais da cadeia viva perdem só eles")
    void metadataIsClearedOnlyFromInactiveSessions() {
        Instant now = Instant.parse("1997-01-01T00:00:00Z");
        UUID user = createUser();
        UUID active = insert(user, now.plus(DAY), null, null);
        UUID revokedValid = insert(user, now.plus(DAY), now.minusSeconds(60), null);
        UUID ancestor = insert(user, now.minus(DAY), now.minus(DAY.multipliedBy(2)), active);
        Map<String, Object> ancestorBefore = row(ancestor);

        useCase(100, 10).cleanup(now);

        assertNotNull(row(active).get("ip_address"));
        assertNotNull(row(active).get("user_agent"));
        for (UUID inactive : List.of(revokedValid, ancestor)) {
            assertNull(row(inactive).get("ip_address"));
            assertNull(row(inactive).get("user_agent"));
        }
        Map<String, Object> ancestorAfter = row(ancestor);
        for (String securityColumn : List.of("user_id", "token_hash", "expires_at", "revoked_at",
                "replaced_by_session_id", "last_used_at")) {
            assertEquals(ancestorBefore.get(securityColumn), ancestorAfter.get(securityColumn), securityColumn);
        }
    }

    @Test
    @DisplayName("Reuse detection: antecessora revogada de cadeia viva sobrevive ao cleanup e seu reúso revoga a cadeia")
    void cleanupKeepsReuseDetectionForLiveChains() {
        Instant now = Instant.parse("1998-01-01T00:00:00Z");
        UUID user = createUser();
        String rawA = "reuse-after-cleanup-" + UUID.randomUUID();
        UUID b = insert(user, now.plus(DAY), null, null);
        UUID a = insertWithHash(user, tokenService.hashRefreshToken(rawA), now.minus(DAY), now.minus(DAY.multipliedBy(2)), b);

        useCase(100, 10).cleanup(now);
        assertTrue(exists(a));

        assertThrows(RefreshTokenReuseDetectedException.class,
                () -> authService.refresh(new RefreshCommand(rawA, "agent", "127.0.0.1")));
        assertNotNull(row(b).get("revoked_at"), "a revogação em massa alcança a sessão viva da cadeia");
    }

    @Test
    @DisplayName("SKIP LOCKED: duas execuções simultâneas pegam lotes disjuntos sem esperar uma pela outra")
    void concurrentPurgesTakeDisjointBatches() throws Exception {
        Instant now = Instant.parse("1985-01-01T00:00:00Z");
        drainWindow(now);
        UUID user = createUser();
        for (int i = 0; i < 30; i++) {
            insert(user, now.minus(DAY).minusSeconds(i), null, null);
        }
        int activeBefore = activeConnections();
        CountDownLatch firstHoldsBatch = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                int purged = authSessionRepository.purgeExpiredWithoutSuccessor(now, 10);
                firstHoldsBatch.countDown();
                await(releaseFirst);
                return purged;
            }));
            assertTrue(firstHoldsBatch.await(10, TimeUnit.SECONDS));

            Future<Integer> second = executor.submit(() -> authSessionRepository.purgeExpiredWithoutSuccessor(now, 10));
            assertEquals(10, second.get(5, TimeUnit.SECONDS), "a segunda execução não espera as linhas travadas");
            releaseFirst.countDown();
            assertEquals(10, first.get(10, TimeUnit.SECONDS));
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }

        assertEquals(10, countExpiringBefore(now), "20 linhas distintas removidas, nenhuma duas vezes");
        assertEquals(activeBefore, activeConnections(), "nenhuma conexão presa");
    }

    @Test
    @DisplayName("Lote abortado: rollback completo, nada daquele lote é removido")
    void abortedBatchLeavesNothingBehind() {
        Instant now = Instant.parse("1987-01-01T00:00:00Z");
        UUID user = createUser();
        UUID expired = insert(user, now.minus(DAY), null, null);

        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            authSessionRepository.purgeExpiredWithoutSuccessor(now, 10);
            throw new IllegalStateException("falha simulada antes do commit");
        }));

        assertTrue(exists(expired));
    }

    @Test
    @DisplayName("Concorrência com revokeAllByUserId: a revogação espera o lote do purge e conclui sem corromper sessões")
    void revokeAllWaitsForPurgeBatch() throws Exception {
        Instant now = Instant.parse("1989-01-01T00:00:00Z");
        UUID user = createUser();
        UUID expired = insert(user, now.minus(DAY), null, null);
        UUID active = insert(user, Instant.now().plus(DAY), null, null);
        CountDownLatch purgeHoldsRows = new CountDownLatch(1);
        CountDownLatch releasePurge = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> purge = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                authSessionRepository.purgeExpiredWithoutSuccessor(now, 10);
                purgeHoldsRows.countDown();
                await(releasePurge);
            }));
            assertTrue(purgeHoldsRows.await(10, TimeUnit.SECONDS));

            Future<?> revokeAll = executor.submit(() -> authSessionRepository.revokeAllByUserId(user));
            assertThrows(TimeoutException.class, () -> revokeAll.get(700, TimeUnit.MILLISECONDS));
            releasePurge.countDown();
            purge.get(10, TimeUnit.SECONDS);
            revokeAll.get(10, TimeUnit.SECONDS);
        } finally {
            releasePurge.countDown();
            executor.shutdownNow();
        }

        assertFalse(exists(expired));
        assertNotNull(row(active).get("revoked_at"));
    }

    @Test
    @DisplayName("V16: índice parcial em replaced_by_session_id existe e é válido")
    void replacedByIndexExistsAndIsPartial() {
        String definition = jdbcTemplate.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_auth_sessions_replaced_by_session_id'", String.class);
        Boolean valid = jdbcTemplate.queryForObject("""
                SELECT i.indisvalid FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid
                WHERE c.relname = 'idx_auth_sessions_replaced_by_session_id'
                """, Boolean.class);

        assertNotNull(definition);
        assertTrue(definition.contains("(replaced_by_session_id)"));
        assertTrue(definition.contains("WHERE (replaced_by_session_id IS NOT NULL)"));
        assertEquals(Boolean.TRUE, valid);
    }

    private CleanupAuthSessionsUseCase useCase(int batchSize, int maxBatchesPerRun) {
        return new CleanupAuthSessionsUseCase(authSessionRepository, batchSize, maxBatchesPerRun);
    }

    // Remove sobras elegíveis de execuções anteriores na janela do cenário
    private void drainWindow(Instant now) {
        while (authSessionRepository.purgeExpiredWithoutSuccessor(now, 1000) > 0) {
            // repete até a janela ficar vazia
        }
    }

    private UUID createUser() {
        String unique = "cleanup_" + UUID.randomUUID().toString().substring(0, 8);
        return userRepository.save(new User(null, unique + "@rewit.test", "Password123!", AuthProvider.LOCAL, null)).getId();
    }

    private UUID insert(UUID user, Instant expiresAt, Instant revokedAt, UUID replacedBy) {
        String hash = (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "");
        return insertWithHash(user, hash, expiresAt, revokedAt, replacedBy);
    }

    private UUID insertWithHash(UUID user, String hash, Instant expiresAt, Instant revokedAt, UUID replacedBy) {
        UUID id = UUID.randomUUID();
        Instant issued = expiresAt.minus(Duration.ofDays(30));
        jdbcTemplate.update("""
                INSERT INTO auth_sessions (id, user_id, token_hash, issued_at, expires_at, revoked_at,
                                           replaced_by_session_id, created_at, last_used_at, user_agent, ip_address)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::inet)
                """, id, user, hash, Timestamp.from(issued), Timestamp.from(expiresAt),
                revokedAt != null ? Timestamp.from(revokedAt) : null, replacedBy,
                Timestamp.from(issued), Timestamp.from(issued), "Test-Agent/1.0", "203.0.113.10");
        return id;
    }

    private boolean exists(UUID id) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM auth_sessions WHERE id = ?", Integer.class, id);
        return count != null && count == 1;
    }

    private UUID replacedBy(UUID id) {
        return jdbcTemplate.queryForObject("SELECT replaced_by_session_id FROM auth_sessions WHERE id = ?", UUID.class, id);
    }

    private Map<String, Object> row(UUID id) {
        return jdbcTemplate.queryForMap("""
                SELECT user_id, token_hash, expires_at, revoked_at, replaced_by_session_id, last_used_at,
                       user_agent, host(ip_address) AS ip_address
                FROM auth_sessions WHERE id = ?
                """, id);
    }

    private int countExpiringBefore(Instant now) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM auth_sessions WHERE expires_at < ? AND expires_at > ?", Integer.class,
                Timestamp.from(now), Timestamp.from(now.minus(Duration.ofDays(30))));
        return count == null ? 0 : count;
    }

    private int activeConnections() {
        return ((HikariDataSource) dataSource).getHikariPoolMXBean().getActiveConnections();
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
