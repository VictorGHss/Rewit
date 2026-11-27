package com.rewit.migration;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Harness exclusivo de teste (fora de {@code src/main/java}) para executar o
 * {@link RewitMigrationRunner} em processos JVM independentes ({@link ProcessBuilder})
 * contra o PostgreSQL descartável local nos testes de concorrência e crash da F2-DEPLOY-03.5.2B.
 */
public final class LocalMigrationRunnerProcessHarness {

    static final String ENV_START_GATE_FILE = "REWIT_TEST_START_GATE_FILE";
    static final String ENV_LOCK_TIMEOUT_MS = "REWIT_TEST_LOCK_TIMEOUT_MS";
    static final String ENV_HOLD_LOCK_READY_FILE = "REWIT_TEST_HOLD_LOCK_READY_FILE";

    private LocalMigrationRunnerProcessHarness() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> rawEnv = System.getenv();

        // Modo auxiliar para teste de crash/SIGKILL: adquire o advisory lock de sessão e aguarda término abrupto
        String holdLockReadyPath = rawEnv.get(ENV_HOLD_LOCK_READY_FILE);
        if (holdLockReadyPath != null && !holdLockReadyPath.isBlank()) {
            holdAdvisoryLockUntilKilled(rawEnv, Path.of(holdLockReadyPath));
            return;
        }

        // Sincroniza o disparo simultâneo entre processos JVM independentes via arquivo-barreira
        String startGatePath = rawEnv.get(ENV_START_GATE_FILE);
        if (startGatePath != null && !startGatePath.isBlank()) {
            Path gate = Path.of(startGatePath);
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (!Files.exists(gate)) {
                if (System.nanoTime() > deadline) {
                    System.err.println("[harness] Timeout aguardando barreira de disparo simultâneo.");
                    System.exit(99);
                }
                Thread.sleep(10L);
            }
        }

        Duration lockTimeout = RewitMigrationRunner.DEFAULT_LOCK_TIMEOUT;
        String timeoutMs = rawEnv.get(ENV_LOCK_TIMEOUT_MS);
        if (timeoutMs != null && !timeoutMs.isBlank()) {
            lockTimeout = Duration.ofMillis(Long.parseLong(timeoutMs.trim()));
        }

        Map<String, String> runnerEnv = new HashMap<>();
        copyIfPresent(rawEnv, runnerEnv, RewitMigrationRunner.ENV_DATABASE_URL);
        copyIfPresent(rawEnv, runnerEnv, RewitMigrationRunner.ENV_FLYWAY_DB_USER);
        copyIfPresent(rawEnv, runnerEnv, RewitMigrationRunner.ENV_FLYWAY_DB_PASSWORD);

        int exitCode = RewitMigrationRunner.runForLocalIntegrationTest(
                new String[0],
                runnerEnv,
                RewitMigrationRunner.MIGRATION_LOCATION,
                lockTimeout,
                System.out,
                System.err
        );
        System.exit(exitCode);
    }

    private static void holdAdvisoryLockUntilKilled(Map<String, String> env, Path readyFile) throws Exception {
        String url = env.get(RewitMigrationRunner.ENV_DATABASE_URL);
        String user = env.get(RewitMigrationRunner.ENV_FLYWAY_DB_USER);
        String password = env.get(RewitMigrationRunner.ENV_FLYWAY_DB_PASSWORD);

        try (Connection conn = DriverManager.getConnection(url, user, password);
             PreparedStatement ps = conn.prepareStatement("SELECT pg_catalog.pg_try_advisory_lock(?)")) {
            ps.setLong(1, RewitMigrationRunner.MIGRATION_ADVISORY_LOCK_KEY);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next() || !rs.getBoolean(1)) {
                    System.err.println("[harness] Não foi possível adquirir o advisory lock.");
                    System.exit(98);
                }
            }
            Files.writeString(readyFile, "LOCKED", StandardCharsets.UTF_8);
            Thread.sleep(Duration.ofMinutes(5).toMillis());
        }
    }

    private static void copyIfPresent(Map<String, String> src, Map<String, String> dst, String key) {
        String val = src.get(key);
        if (val != null) {
            dst.put(key, val);
        }
    }
}
