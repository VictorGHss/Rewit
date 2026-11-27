package com.rewit.migration;

import com.rewit.config.TestInfrastructureConfig;
import com.rewit.infrastructure.persistence.schema.ReadOnlyFlywaySchemaValidator;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testes reais de concorrência entre dois processos JVM independentes do
 * {@link RewitMigrationRunner} contra PostgreSQL 18 + PostGIS 3.6 descartável
 * (F2-DEPLOY-03.5.2B — Pilar A).
 */
@DisplayName("F2-DEPLOY-03.5.2B (Pilar A): Concorrência entre instâncias independentes do RewitMigrationRunner")
class RewitMigrationRunnerConcurrencyIntegrationTest {

    private static final TestInfrastructureConfig INFRA = TestInfrastructureConfig.current();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String DB_PREFIX = "rewit_migconc_";
    private static final Pattern DISPOSABLE_DB_PATTERN = Pattern.compile("^rewit_migconc_[0-9a-f]{12}$");
    private static final Pattern DISPOSABLE_ROLE_PATTERN = Pattern.compile("^rewit_mc(admin|mig)_[0-9a-f]{12}$");

    @TempDir
    Path tempDir;

    private String disposableDbName;
    private String disposableDbMarker;
    private String disposableDbUrl;
    private final List<String> disposableRoles = new ArrayList<>();
    private final List<Process> spawnedProcesses = new ArrayList<>();

    @AfterEach
    void cleanup() throws SQLException {
        for (Process p : spawnedProcesses) {
            if (p.isAlive()) {
                p.destroyForcibly();
                try {
                    p.waitFor(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        spawnedProcesses.clear();

        if (disposableDbName != null) {
            assertTrue(DISPOSABLE_DB_PATTERN.matcher(disposableDbName).matches(),
                    "Database fora do padrão descartável: " + disposableDbName);
            try (Connection admin = openSuperuserConnection(INFRA.datasourceUrl())) {
                try (PreparedStatement ps = admin.prepareStatement(
                        "SELECT shobj_description(oid, 'pg_database'), current_database() FROM pg_database WHERE datname = ?")) {
                    ps.setString(1, disposableDbName);
                    try (ResultSet rs = ps.executeQuery()) {
                        assertTrue(rs.next());
                        assertEquals(disposableDbMarker, rs.getString(1));
                        assertNotEquals(disposableDbName, rs.getString(2));
                    }
                }
                try (Statement st = admin.createStatement()) {
                    st.execute("DROP DATABASE " + disposableDbName + " WITH (FORCE)");
                    for (String role : disposableRoles) {
                        assertTrue(DISPOSABLE_ROLE_PATTERN.matcher(role).matches(),
                                "Role fora do padrão descartável: " + role);
                        st.execute("DROP ROLE IF EXISTS " + role);
                    }
                }
            }
        }
        disposableDbName = null;
        disposableDbMarker = null;
        disposableDbUrl = null;
        disposableRoles.clear();
    }

    @Test
    @DisplayName("Dois processos JVM independentes concorrendo em banco vazio (V1–V46): serialização sem duplicação, histórico íntegro e reexecução idempotente")
    void twoIndependentRunnerProcessesRacingOnEmptyDatabaseSerializeCleanly() throws Exception {
        MigratorFixture fixture = provisionManagedDatabaseAndMigrator();

        Path startGate = tempDir.resolve("start-gate-empty.flag");
        Path readyA = tempDir.resolve("runner-a.ready");
        Path readyB = tempDir.resolve("runner-b.ready");
        ChildProcessHandle procA = startRunnerChildProcess("runner-a", fixture, startGate, readyA, null);
        ChildProcessHandle procB = startRunnerChildProcess("runner-b", fixture, startGate, readyB, null);

        // Aguarda confirmação explícita de prontidão das duas JVMs no ponto da barreira (sem sleep fixo)
        awaitAllChildrenReadyOrFailFast(List.of(procA, procB), Duration.ofSeconds(30));
        releaseStartGateAtomically(startGate);

        ChildRunResult resA = awaitProcess(procA, Duration.ofSeconds(120));
        ChildRunResult resB = awaitProcess(procB, Duration.ofSeconds(120));

        assertEquals(RewitMigrationRunner.EXIT_SUCCESS, resA.exitCode,
                "Runner A deveria concluir com exit=0; stdout=" + resA.stdout + "; stderr=" + resA.stderr);
        assertEquals(RewitMigrationRunner.EXIT_SUCCESS, resB.exitCode,
                "Runner B deveria concluir com exit=0; stdout=" + resB.stdout + "; stderr=" + resB.stderr);

        int executedA = parseMigrationsExecuted(resA.stdout);
        int executedB = parseMigrationsExecuted(resB.stdout);
        assertEquals(46, executedA + executedB,
                "A soma de migrations executadas pelos dois processos concorrentes deve ser exatamente 46 (A="
                        + executedA + ", B=" + executedB + ")");
        assertTrue((executedA == 46 && executedB == 0) || (executedA == 0 && executedB == 46),
                "Um processo deve aplicar as 46 migrations e o outro aguardar o lock e aplicar 0 (A="
                        + executedA + ", B=" + executedB + ")");

        for (ChildRunResult res : List.of(resA, resB)) {
            assertTrue(res.stdout.contains("versaoAtual=46"), "versaoAtual=46 esperada: " + res.stdout);
            assertTrue(res.stdout.contains("totalAplicadas=46"), "totalAplicadas=46 esperada: " + res.stdout);
            assertTrue(res.stdout.contains("rewit_assert_runtime_privileges=OK"),
                    "rewit_assert_runtime_privileges=OK esperada: " + res.stdout);
            assertFalse(res.stdout.contains(fixture.password), "stdout nunca pode vazar senha do migrator");
            assertFalse(res.stderr.contains(fixture.password), "stderr nunca pode vazar senha do migrator");
        }

        assertFullV1ToV46HistoryWithoutDuplicates(fixture);
        assertReadOnlyApiValidatorApproves();

        // Terceira execução sequencial comprova idempotência pós-concorrência
        ChildProcessHandle procC = startRunnerChildProcess("runner-c-idempotent", fixture, null, null, null);
        ChildRunResult resC = awaitProcess(procC, Duration.ofSeconds(60));
        assertEquals(RewitMigrationRunner.EXIT_SUCCESS, resC.exitCode,
                "Execução idempotente deveria concluir com exit=0; stderr=" + resC.stderr);
        assertEquals(0, parseMigrationsExecuted(resC.stdout));
        assertTrue(resC.stdout.contains("versaoAtual=46"));
        assertFalse(resC.stdout.contains(fixture.password));
        assertFalse(resC.stderr.contains(fixture.password));
    }

    @Test
    @DisplayName("Dois processos JVM independentes concorrendo em banco parcialmente migrado (V1–V15 → V16–V46, incluindo V16 CONCURRENTLY)")
    void twoIndependentRunnerProcessesRacingAcrossNonTransactionalV16SerializeCleanly() throws Exception {
        MigratorFixture fixture = provisionManagedDatabaseAndMigrator();

        // Pré-migra V1..V15 com Flyway sob o mesmo migrator para concentrar a disputa exatamente na V16 não transacional
        Flyway.configure()
                .dataSource(disposableDbUrl, fixture.role, fixture.password)
                .locations(RewitMigrationRunner.MIGRATION_LOCATION)
                .defaultSchema(RewitMigrationRunner.SCHEMA_PUBLIC)
                .schemas(RewitMigrationRunner.SCHEMA_PUBLIC)
                .table(RewitMigrationRunner.HISTORY_TABLE)
                .target("15")
                .baselineOnMigrate(false)
                .cleanDisabled(true)
                .configuration(Map.of("flyway.postgresql.transactional.lock", "false"))
                .load()
                .migrate();

        Path startGate = tempDir.resolve("start-gate-v16.flag");
        Path readyA = tempDir.resolve("runner-v16-a.ready");
        Path readyB = tempDir.resolve("runner-v16-b.ready");
        ChildProcessHandle procA = startRunnerChildProcess("runner-v16-a", fixture, startGate, readyA, null);
        ChildProcessHandle procB = startRunnerChildProcess("runner-v16-b", fixture, startGate, readyB, null);

        awaitAllChildrenReadyOrFailFast(List.of(procA, procB), Duration.ofSeconds(30));
        releaseStartGateAtomically(startGate);

        ChildRunResult resA = awaitProcess(procA, Duration.ofSeconds(120));
        ChildRunResult resB = awaitProcess(procB, Duration.ofSeconds(120));

        assertEquals(RewitMigrationRunner.EXIT_SUCCESS, resA.exitCode, "stderrA=" + resA.stderr);
        assertEquals(RewitMigrationRunner.EXIT_SUCCESS, resB.exitCode, "stderrB=" + resB.stderr);

        int executedA = parseMigrationsExecuted(resA.stdout);
        int executedB = parseMigrationsExecuted(resB.stdout);
        assertEquals(31, executedA + executedB,
                "Deve aplicar exatamente V16..V46 (31 migrations) sem duplicação (A=" + executedA + ", B=" + executedB + ")");
        assertTrue((executedA == 31 && executedB == 0) || (executedA == 0 && executedB == 31));

        assertFullV1ToV46HistoryWithoutDuplicates(fixture);
        assertV16IndexIsValidAndReady(fixture);
        assertReadOnlyApiValidatorApproves();
    }

    @Test
    @DisplayName("Contenção prolongada do advisory lock encerra de forma determinística (exit=5) sem corrupção e recupera após liberação")
    void lockContentionTimesOutDeterministicallyAndSucceedsOnceReleased() throws Exception {
        MigratorFixture fixture = provisionManagedDatabaseAndMigrator();

        // Outra sessão segura explicitamente a chave do advisory lock de migrations
        try (Connection holder = DriverManager.getConnection(disposableDbUrl, fixture.role, fixture.password)) {
            try (PreparedStatement ps = holder.prepareStatement("SELECT pg_catalog.pg_try_advisory_lock(?)")) {
                ps.setLong(1, RewitMigrationRunner.MIGRATION_ADVISORY_LOCK_KEY);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next() && rs.getBoolean(1), "Sessão externa deveria adquirir o advisory lock");
                }
            }

            ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
            ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
            int exitCode = RewitMigrationRunner.runForLocalIntegrationTest(
                    new String[0],
                    Map.of(
                            RewitMigrationRunner.ENV_DATABASE_URL, disposableDbUrl,
                            RewitMigrationRunner.ENV_FLYWAY_DB_USER, fixture.role,
                            RewitMigrationRunner.ENV_FLYWAY_DB_PASSWORD, fixture.password
                    ),
                    RewitMigrationRunner.MIGRATION_LOCATION,
                    Duration.ofMillis(1200),
                    new PrintStream(outBuf, true, StandardCharsets.UTF_8),
                    new PrintStream(errBuf, true, StandardCharsets.UTF_8)
            );
            String stdout = outBuf.toString(StandardCharsets.UTF_8);
            String stderr = errBuf.toString(StandardCharsets.UTF_8);

            assertEquals(RewitMigrationRunner.EXIT_LOCK_TIMEOUT, exitCode,
                    "Contenção acima do timeout deve retornar EXIT_LOCK_TIMEOUT (5); stderr=" + stderr);
            assertTrue(stderr.contains("advisory lock"), "stderr deve explicar timeout do advisory lock: " + stderr);
            assertFalse(stdout.contains(fixture.password));
            assertFalse(stderr.contains(fixture.password));
            assertFalse(historyTableExists(), "Runner barrado por lock não pode criar flyway_schema_history");
        }

        // Após fechamento da sessão detentora, PostgreSQL libera o lock e o próximo runner conclui normalmente
        ChildProcessHandle afterRelease = startRunnerChildProcess("runner-after-release", fixture, null, null, 30_000L);
        ChildRunResult res = awaitProcess(afterRelease, Duration.ofSeconds(120));
        assertEquals(RewitMigrationRunner.EXIT_SUCCESS, res.exitCode, "stderr=" + res.stderr);
        assertEquals(46, parseMigrationsExecuted(res.stdout));
        assertFullV1ToV46HistoryWithoutDuplicates(fixture);
    }

    @Test
    @DisplayName("Crash abrupto (SIGKILL) de processo detentor do advisory lock libera sessão no PostgreSQL e permite retomada limpa")
    void killedHolderReleasesAdvisoryLockAutomaticallyInPostgres() throws Exception {
        MigratorFixture fixture = provisionManagedDatabaseAndMigrator();

        Path lockReadyFile = tempDir.resolve("holder-locked.flag");
        ChildProcessHandle holder = startLockHolderChildProcess("lock-holder-crash", fixture, lockReadyFile);

        awaitAllChildrenReadyOrFailFast(List.of(holder), Duration.ofSeconds(20));

        // Mata o processo detentor abruptamente (SIGKILL), sem chance de executar finally/pg_advisory_unlock
        holder.process.destroyForcibly();
        assertTrue(holder.process.waitFor(10, TimeUnit.SECONDS), "Processo morto via SIGKILL deve encerrar");

        // Novo runner independente deve adquirir o lock liberado automaticamente pelo PostgreSQL e concluir V1–V46
        ChildProcessHandle recoveryRunner = startRunnerChildProcess("runner-post-sigkill", fixture, null, null, 30_000L);
        ChildRunResult res = awaitProcess(recoveryRunner, Duration.ofSeconds(120));
        assertEquals(RewitMigrationRunner.EXIT_SUCCESS, res.exitCode,
                "Runner após SIGKILL do detentor deveria concluir com exit=0; stderr=" + res.stderr);
        assertEquals(46, parseMigrationsExecuted(res.stdout));
        assertFullV1ToV46HistoryWithoutDuplicates(fixture);
        assertReadOnlyApiValidatorApproves();
    }

    private record MigratorFixture(String role, String password) {
    }

    private record ChildProcessHandle(String label, Process process, Path stdoutFile, Path stderrFile, Path readyFile) {
    }

    private record ChildRunResult(int exitCode, String stdout, String stderr) {
    }

    private MigratorFixture provisionManagedDatabaseAndMigrator() throws SQLException {
        String suffix = randomHex(6);
        disposableDbName = DB_PREFIX + suffix;
        disposableDbMarker = "rewit-migration-concurrency-test:" + UUID.randomUUID();
        disposableDbUrl = INFRA.datasourceUrlForDatabase(disposableDbName);

        String managedAdminRole = "rewit_mcadmin_" + suffix;
        String managedAdminCredential = generateEphemeralCredential();
        String migratorRole = "rewit_mcmig_" + suffix;
        String migratorCredential = generateEphemeralCredential();

        try (Connection admin = openSuperuserConnection(INFRA.datasourceUrl());
             Statement st = admin.createStatement()) {
            st.execute("CREATE DATABASE " + disposableDbName);
            st.execute("COMMENT ON DATABASE " + disposableDbName + " IS '" + disposableDbMarker + "'");

            st.execute("CREATE ROLE " + managedAdminRole
                    + " WITH LOGIN NOSUPERUSER NOCREATEDB CREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS PASSWORD '"
                    + managedAdminCredential + "'");
            disposableRoles.add(managedAdminRole);
            st.execute("GRANT CONNECT ON DATABASE " + disposableDbName + " TO " + managedAdminRole);
        }
        try (Connection dbAdmin = openSuperuserConnection(disposableDbUrl);
             Statement st = dbAdmin.createStatement()) {
            for (String ext : List.of("uuid-ossp", "postgis", "pg_trgm", "pgcrypto", "unaccent")) {
                st.execute("CREATE EXTENSION IF NOT EXISTS \"" + ext + "\" WITH SCHEMA public");
            }
            st.execute("GRANT CONNECT ON DATABASE " + disposableDbName + " TO rewit_app, rewit_media, rewit_mod");
            st.execute("GRANT USAGE, CREATE ON SCHEMA public TO " + managedAdminRole + " WITH GRANT OPTION");
        }
        try (Connection mgdAdmin = DriverManager.getConnection(disposableDbUrl, managedAdminRole, managedAdminCredential);
             Statement st = mgdAdmin.createStatement()) {
            st.execute("CREATE ROLE " + migratorRole
                    + " WITH LOGIN NOCREATEDB NOCREATEROLE NOINHERIT PASSWORD '" + migratorCredential + "'");
            disposableRoles.add(migratorRole);
            st.execute("GRANT USAGE, CREATE ON SCHEMA public TO " + migratorRole);
        }
        try (Connection admin = openSuperuserConnection(INFRA.datasourceUrl());
             Statement st = admin.createStatement()) {
            st.execute("GRANT CONNECT ON DATABASE " + disposableDbName + " TO " + migratorRole);
        }
        return new MigratorFixture(migratorRole, migratorCredential);
    }

    private ChildProcessHandle startRunnerChildProcess(
            String label,
            MigratorFixture fixture,
            Path startGate,
            Path readyFile,
            Long lockTimeoutMs
    ) throws IOException {
        Path stdoutFile = tempDir.resolve(label + ".out");
        Path stderrFile = tempDir.resolve(label + ".err");

        ProcessBuilder pb = new ProcessBuilder(
                javaExecutable(),
                "-cp",
                System.getProperty("java.class.path"),
                LocalMigrationRunnerProcessHarness.class.getName()
        );
        Map<String, String> env = pb.environment();
        env.clear();
        env.put("LANG", "C.UTF-8");
        env.put(RewitMigrationRunner.ENV_DATABASE_URL, disposableDbUrl);
        env.put(RewitMigrationRunner.ENV_FLYWAY_DB_USER, fixture.role);
        env.put(RewitMigrationRunner.ENV_FLYWAY_DB_PASSWORD, fixture.password);
        if (startGate != null) {
            env.put(LocalMigrationRunnerProcessHarness.ENV_START_GATE_FILE, startGate.toAbsolutePath().toString());
        }
        if (readyFile != null) {
            env.put(LocalMigrationRunnerProcessHarness.ENV_READY_SIGNAL_FILE, readyFile.toAbsolutePath().toString());
        }
        if (lockTimeoutMs != null) {
            env.put(LocalMigrationRunnerProcessHarness.ENV_LOCK_TIMEOUT_MS, String.valueOf(lockTimeoutMs));
        }

        pb.redirectOutput(stdoutFile.toFile());
        pb.redirectError(stderrFile.toFile());
        Process proc = pb.start();
        spawnedProcesses.add(proc);
        return new ChildProcessHandle(label, proc, stdoutFile, stderrFile, readyFile);
    }

    private ChildProcessHandle startLockHolderChildProcess(
            String label,
            MigratorFixture fixture,
            Path readyFile
    ) throws IOException {
        Path stdoutFile = tempDir.resolve(label + ".out");
        Path stderrFile = tempDir.resolve(label + ".err");

        ProcessBuilder pb = new ProcessBuilder(
                javaExecutable(),
                "-cp",
                System.getProperty("java.class.path"),
                LocalMigrationRunnerProcessHarness.class.getName()
        );
        Map<String, String> env = pb.environment();
        env.clear();
        env.put("LANG", "C.UTF-8");
        env.put(RewitMigrationRunner.ENV_DATABASE_URL, disposableDbUrl);
        env.put(RewitMigrationRunner.ENV_FLYWAY_DB_USER, fixture.role);
        env.put(RewitMigrationRunner.ENV_FLYWAY_DB_PASSWORD, fixture.password);
        env.put(LocalMigrationRunnerProcessHarness.ENV_HOLD_LOCK_READY_FILE, readyFile.toAbsolutePath().toString());

        pb.redirectOutput(stdoutFile.toFile());
        pb.redirectError(stderrFile.toFile());
        Process proc = pb.start();
        spawnedProcesses.add(proc);
        return new ChildProcessHandle(label, proc, stdoutFile, stderrFile, readyFile);
    }

    private static void awaitAllChildrenReadyOrFailFast(
            List<ChildProcessHandle> handles,
            Duration timeout
    ) throws Exception {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        while (true) {
            boolean allReady = true;
            for (ChildProcessHandle h : handles) {
                boolean signaled = h.readyFile != null && Files.exists(h.readyFile);
                if (!signaled) {
                    allReady = false;
                    if (!h.process.isAlive()) {
                        String out = readFileSafe(h.stdoutFile);
                        String err = readFileSafe(h.stderrFile);
                        throw new AssertionError("Processo filho '" + h.label
                                + "' encerrou antes de sinalizar prontidão na barreira (exit="
                                + h.process.exitValue() + "). stdout=" + out + " | stderr=" + err);
                    }
                }
            }
            if (allReady) {
                return;
            }
            if (System.nanoTime() >= deadlineNanos) {
                StringBuilder diag = new StringBuilder("Timeout de ")
                        .append(timeout.toSeconds())
                        .append("s aguardando prontidão dos processos filhos na barreira:");
                for (ChildProcessHandle h : handles) {
                    boolean signaled = h.readyFile != null && Files.exists(h.readyFile);
                    diag.append("\n - ").append(h.label)
                            .append(": signaled=").append(signaled)
                            .append(", alive=").append(h.process.isAlive())
                            .append(", stdout=").append(readFileSafe(h.stdoutFile))
                            .append(", stderr=").append(readFileSafe(h.stderrFile));
                }
                throw new AssertionError(diag.toString());
            }
            Thread.sleep(20L);
        }
    }

    private static void releaseStartGateAtomically(Path startGate) throws IOException {
        Path parent = startGate.toAbsolutePath().getParent();
        Path tmp = Files.createTempFile(parent, startGate.getFileName().toString() + ".", ".tmp");
        Files.writeString(tmp, "GO", StandardCharsets.UTF_8);
        Files.move(tmp, startGate, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private static String readFileSafe(Path file) {
        if (file == null || !Files.exists(file)) {
            return "";
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "<erro ao ler log: " + e.getMessage() + ">";
        }
    }

    private static ChildRunResult awaitProcess(ChildProcessHandle handle, Duration timeout) throws Exception {
        boolean finished = handle.process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        assertTrue(finished, "Processo filho excedeu timeout de " + timeout.toSeconds() + "s");
        String stdout = Files.exists(handle.stdoutFile)
                ? Files.readString(handle.stdoutFile, StandardCharsets.UTF_8) : "";
        String stderr = Files.exists(handle.stderrFile)
                ? Files.readString(handle.stderrFile, StandardCharsets.UTF_8) : "";
        return new ChildRunResult(handle.process.exitValue(), stdout, stderr);
    }

    private void assertFullV1ToV46HistoryWithoutDuplicates(MigratorFixture fixture) throws SQLException {
        List<Integer> rawVersions = new ArrayList<>();
        Set<Integer> uniqueVersions = new TreeSet<>();
        try (Connection conn = DriverManager.getConnection(disposableDbUrl, fixture.role, fixture.password);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT version, success FROM public.flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank")) {
            while (rs.next()) {
                assertTrue(rs.getBoolean("success"), "Toda linha em flyway_schema_history deve estar com success=true");
                int v = Integer.parseInt(rs.getString("version"));
                rawVersions.add(v);
                uniqueVersions.add(v);
            }
        }
        assertEquals(46, rawVersions.size(), "Deve haver exatamente 46 registros versionados sem duplicação");
        assertEquals(46, uniqueVersions.size(), "Deve haver exatamente 46 versões únicas V1..V46");
        for (int v = 1; v <= 46; v++) {
            assertTrue(uniqueVersions.contains(v), "Versão ausente no histórico: V" + v);
        }
    }

    private void assertV16IndexIsValidAndReady(MigratorFixture fixture) throws SQLException {
        try (Connection conn = DriverManager.getConnection(disposableDbUrl, fixture.role, fixture.password);
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT i.indisvalid, i.indisready "
                             + "FROM pg_index i "
                             + "JOIN pg_class c ON c.oid = i.indexrelid "
                             + "JOIN pg_namespace n ON n.oid = c.relnamespace "
                             + "WHERE n.nspname = 'public' AND c.relname = 'idx_auth_sessions_replaced_by_session_id'");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next(), "Índice da V16 deve existir");
            assertTrue(rs.getBoolean("indisvalid"), "Índice da V16 deve estar válido (indisvalid=true)");
            assertTrue(rs.getBoolean("indisready"), "Índice da V16 deve estar pronto (indisready=true)");
        }
    }

    private void assertReadOnlyApiValidatorApproves() {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(disposableDbUrl);
        cfg.setUsername(INFRA.appDbUser());
        cfg.setPassword(INFRA.appDbPassword());
        cfg.setMaximumPoolSize(2);
        try (HikariDataSource ds = new HikariDataSource(cfg)) {
            ReadOnlyFlywaySchemaValidator validator = new ReadOnlyFlywaySchemaValidator(ds);
            assertDoesNotThrow(validator::validate,
                    "ReadOnlyFlywaySchemaValidator (rewit_app) deve aprovar o schema resultante da execução concorrente");
        }
    }

    private boolean historyTableExists() throws SQLException {
        try (Connection admin = openSuperuserConnection(disposableDbUrl);
             PreparedStatement ps = admin.prepareStatement("SELECT to_regclass('public.flyway_schema_history') IS NOT NULL");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            return rs.getBoolean(1);
        }
    }

    private static int parseMigrationsExecuted(String stdout) {
        var matcher = Pattern.compile("executadas=(\\d+)").matcher(stdout);
        assertTrue(matcher.find(), "Saída do runner não contém 'executadas=<N>': " + stdout);
        return Integer.parseInt(matcher.group(1));
    }

    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toAbsolutePath().toString();
    }

    private static Connection openSuperuserConnection(String url) throws SQLException {
        return DriverManager.getConnection(url, INFRA.migrationDbUser(), INFRA.migrationDbPassword());
    }

    private static String generateEphemeralCredential() {
        return randomHex(16);
    }

    private static String randomHex(int bytes) {
        byte[] buf = new byte[bytes];
        RANDOM.nextBytes(buf);
        return HexFormat.of().formatHex(buf);
    }
}
