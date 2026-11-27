package com.rewit.migration;

import com.rewit.config.TestInfrastructureConfig;
import com.rewit.infrastructure.persistence.schema.ReadOnlyFlywaySchemaValidator;
import com.rewit.infrastructure.persistence.schema.ReadOnlySchemaValidatorTestBridge;
import com.rewit.infrastructure.persistence.schema.SchemaValidationException;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
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
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testes reais de falhas parciais e recuperação controlada do {@link RewitMigrationRunner}
 * contra PostgreSQL 18 + PostGIS 3.6 descartável, sem alterar nenhuma migration histórica V1–V46
 * (F2-DEPLOY-03.5.2B — Pilar B).
 *
 * <p>Cobre explicitamente:
 * <ol>
 *   <li>Interrupção após progressão parcial (V1–V20 gravadas, V21–V46 pendentes) e retomada limpa.</li>
 *   <li>Rollback completo de migration transacional ({@code executeInTransaction=true}) seguido de
 *       retomada automática limpa após sanar a causa transitória.</li>
 *   <li>Falha parcial em migration não transacional ({@code executeInTransaction=false}, análoga à V16)
 *       que deixa artefato parcial + linha {@code success=false} no histórico: runner mantém comportamento
 *       fail-closed (jamais executa {@code repair}, {@code baseline} ou {@code clean} automaticamente),
 *       API recusa inicialização e recuperação exige intervenção operacional explícita.</li>
 *   <li>Linha real {@code version='16', success=false} no histórico V1–V46 (induzida sem alterar V16):
 *       runner e validador somente leitura recusam seguir até reparo administrativo explícito, seguido de
 *       retomada V16–V46 e reexecução idempotente.</li>
 * </ol>
 */
@DisplayName("F2-DEPLOY-03.5.2B (Pilar B): Falhas parciais, semântica transacional/não transacional e recuperação controlada")
class RewitMigrationRunnerRecoveryIntegrationTest {

    private static final TestInfrastructureConfig INFRA = TestInfrastructureConfig.current();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String DB_PREFIX = "rewit_migrec_";
    private static final Pattern DISPOSABLE_DB_PATTERN = Pattern.compile("^rewit_migrec_[0-9a-f]{12}$");
    private static final Pattern DISPOSABLE_ROLE_PATTERN = Pattern.compile("^rewit_mr(admin|mig)_[0-9a-f]{12}$");

    private static final String TX_RECOVERY_LOCATION = "classpath:db/migration-recovery-tx";
    private static final String NONTX_RECOVERY_LOCATION = "classpath:db/migration-recovery-nontx";

    private String disposableDbName;
    private String disposableDbMarker;
    private String disposableDbUrl;
    private final List<String> disposableRoles = new ArrayList<>();

    @AfterEach
    void cleanup() throws SQLException {
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
    @DisplayName("Cenário 1 (V1–V46 real): interrupção entre migrations (V1–V20 aplicadas) bloqueia API e retoma V21–V46 com idempotência")
    void partialProgressionV1ToV20BlocksApiAndResumesCleanlyToV46() throws SQLException {
        MigratorFixture fixture = provisionManagedDatabaseAndMigrator();

        // Simula processo interrompido de forma limpa após gravar V1..V20 em flyway_schema_history
        Flyway.configure()
                .dataSource(disposableDbUrl, fixture.role, fixture.password)
                .locations(RewitMigrationRunner.MIGRATION_LOCATION)
                .defaultSchema(RewitMigrationRunner.SCHEMA_PUBLIC)
                .schemas(RewitMigrationRunner.SCHEMA_PUBLIC)
                .table(RewitMigrationRunner.HISTORY_TABLE)
                .target("20")
                .baselineOnMigrate(false)
                .cleanDisabled(true)
                .configuration(Map.of("flyway.postgresql.transactional.lock", "false"))
                .load()
                .migrate();

        assertEquals(20, countHistoryRows(fixture, true));
        assertEquals(0, countHistoryRows(fixture, false));

        // Mesmo concedendo leitura da tabela de histórico para rewit_app, a API DEVE recusar startup por pendências
        grantReadOnlyHistorySelectToApp(fixture);
        try (HikariDataSource appDs = openAppDataSource()) {
            SchemaValidationException ex = assertThrows(SchemaValidationException.class,
                    () -> new ReadOnlyFlywaySchemaValidator(appDs).validate());
            assertTrue(ex.getMessage().contains("26 migrations pendentes"),
                    "Validador somente leitura deve apontar 26 pendências: " + ex.getMessage());
        }

        // Nova execução do runner retoma exatamente de V21..V46
        RunOutput resumed = runRunner(fixture, RewitMigrationRunner.MIGRATION_LOCATION);
        assertEquals(RewitMigrationRunner.EXIT_SUCCESS, resumed.exitCode, "stderr=" + resumed.stderr);
        assertTrue(resumed.stdout.contains("executadas=26"), "stdout=" + resumed.stdout);
        assertTrue(resumed.stdout.contains("versaoInicial=20"), "stdout=" + resumed.stdout);
        assertTrue(resumed.stdout.contains("versaoAtual=46"), "stdout=" + resumed.stdout);
        assertTrue(resumed.stdout.contains("totalAplicadas=46"), "stdout=" + resumed.stdout);
        assertTrue(resumed.stdout.contains("rewit_assert_runtime_privileges=OK"));
        assertFalse(resumed.stdout.contains(fixture.password));
        assertFalse(resumed.stderr.contains(fixture.password));

        assertFullV1ToV46History(fixture);
        try (HikariDataSource appDs = openAppDataSource()) {
            assertDoesNotThrow(() -> new ReadOnlyFlywaySchemaValidator(appDs).validate());
        }

        // Reexecução subsequente é 100% idempotente
        RunOutput idempotent = runRunner(fixture, RewitMigrationRunner.MIGRATION_LOCATION);
        assertEquals(RewitMigrationRunner.EXIT_SUCCESS, idempotent.exitCode, "stderr=" + idempotent.stderr);
        assertTrue(idempotent.stdout.contains("executadas=0"));
        assertTrue(idempotent.stdout.contains("versaoAtual=46"));
    }

    @Test
    @DisplayName("Cenário 2 (migration transacional): rollback integral de DDL/DML sem linha failed e recuperação automática limpa após sanar causa")
    void transactionalMigrationFailureRollsBackCleanlyAndRecoversOnRetry() throws SQLException {
        MigratorFixture fixture = provisionManagedDatabaseAndMigrator();

        // 1. Primeira execução: V1 aplica e commita; V2 falha dentro da transação DDL+DML
        RunOutput failedRun = runRunner(fixture, TX_RECOVERY_LOCATION);
        assertEquals(RewitMigrationRunner.EXIT_MIGRATION_ERROR, failedRun.exitCode,
                "Falha na migration transacional V2 deve encerrar com exit=4");
        assertFalse(failedRun.stdout.contains(fixture.password));
        assertFalse(failedRun.stderr.contains(fixture.password));

        // 2. Comprova rollback integral da DDL da V2 e ausência de linha failed no histórico (PostgreSQL transactional DDL)
        assertFalse(relationExists("public.recovery_tx_v2_artifact"),
                "Tabela criada na V2 transacional deve ter sido integralmente revertida no rollback");
        assertEquals(1, countHistoryRows(fixture, true), "Apenas V1 deve constar aplicada com sucesso");
        assertEquals(0, countHistoryRows(fixture, false),
                "PostgreSQL com DDL transacional não deve registrar linha failed para V2 revertida");

        // Validador somente leitura da API recusa o estado incompleto (V2 pendente)
        grantReadOnlyHistorySelectToApp(fixture);
        try (HikariDataSource appDs = openAppDataSource()) {
            SchemaValidationException ex = assertThrows(SchemaValidationException.class,
                    () -> ReadOnlySchemaValidatorTestBridge.validateWithLocations(appDs, TX_RECOVERY_LOCATION));
            assertTrue(ex.getMessage().contains("pendentes"),
                    "Validador somente leitura deve recusar V2 pendente: " + ex.getMessage());
        }

        // 3. Sana a causa transitória e reexecuta o runner -> recuperação automática sem repair manual
        try (Connection conn = DriverManager.getConnection(disposableDbUrl, fixture.role, fixture.password);
             Statement st = conn.createStatement()) {
            st.execute("UPDATE public.recovery_tx_gate SET allow_v2 = TRUE WHERE id = 1");
        }

        RunOutput recoveredRun = runRunner(fixture, TX_RECOVERY_LOCATION);
        assertEquals(RewitMigrationRunner.EXIT_SUCCESS, recoveredRun.exitCode,
                "Retomada após rollback transacional deveria concluir com exit=0; stderr=" + recoveredRun.stderr);
        assertTrue(recoveredRun.stdout.contains("executadas=1"), "stdout=" + recoveredRun.stdout);
        assertTrue(recoveredRun.stdout.contains("versaoInicial=1"), "stdout=" + recoveredRun.stdout);
        assertTrue(recoveredRun.stdout.contains("versaoAtual=2"), "stdout=" + recoveredRun.stdout);

        assertTrue(relationExists("public.recovery_tx_v2_artifact"),
                "Tabela da V2 deve existir após retomada bem-sucedida");
        assertEquals(2, countHistoryRows(fixture, true));
        assertEquals(0, countHistoryRows(fixture, false));

        try (HikariDataSource appDs = openAppDataSource()) {
            assertDoesNotThrow(() -> ReadOnlySchemaValidatorTestBridge.validateWithLocations(appDs, TX_RECOVERY_LOCATION));
        }

        // 4. Reexecução subsequente idempotente
        RunOutput idempotent = runRunner(fixture, TX_RECOVERY_LOCATION);
        assertEquals(RewitMigrationRunner.EXIT_SUCCESS, idempotent.exitCode);
        assertTrue(idempotent.stdout.contains("executadas=0"));
        assertTrue(idempotent.stdout.contains("versaoAtual=2"));
    }

    @Test
    @DisplayName("Cenário 3 (migration não transacional executeInTransaction=false): preserva estado parcial + linha failed, runner NUNCA faz auto-repair e exige reparo explícito")
    void nonTransactionalPartialFailureStaysFailClosedWithoutAutoRepairUntilExplicitOperatorRecovery() throws SQLException {
        MigratorFixture fixture = provisionManagedDatabaseAndMigrator();

        // 1. Primeira execução: V1 commita; V2 (executeInTransaction=false) cria índice CONCURRENTLY e falha depois
        RunOutput failedRun = runRunner(fixture, NONTX_RECOVERY_LOCATION);
        assertEquals(RewitMigrationRunner.EXIT_MIGRATION_ERROR, failedRun.exitCode);
        assertFalse(failedRun.stdout.contains(fixture.password));
        assertFalse(failedRun.stderr.contains(fixture.password));

        // 2. Comprova estado parcial real (índice criado fora de transação) + registro failed (success=false) em flyway_schema_history
        assertTrue(relationExists("public.idx_recovery_nontx_sessions_ref"),
                "Índice criado antes da falha em migration não transacional permanece como estado parcial");
        assertEquals(1, countHistoryRows(fixture, true), "V1 deve constar com success=true");
        assertEquals(1, countHistoryRows(fixture, false), "V2 não transacional deve registrar success=false");

        // 3. Mesmo após sanar a causa externa, o runner DEVE continuar fail-closed (jamais executa repair/baseline/clean automático!)
        try (Connection conn = DriverManager.getConnection(disposableDbUrl, fixture.role, fixture.password);
             Statement st = conn.createStatement()) {
            st.execute("UPDATE public.recovery_nontx_gate SET allow_v2 = TRUE WHERE id = 1");
        }

        RunOutput blockedRetry = runRunner(fixture, NONTX_RECOVERY_LOCATION);
        assertEquals(RewitMigrationRunner.EXIT_MIGRATION_ERROR, blockedRetry.exitCode,
                "Runner não pode fazer auto-repair nem mascarar migration failed no histórico");
        assertEquals(1, countHistoryRows(fixture, false),
                "Registro failed em flyway_schema_history deve permanecer intacto (não apagado pelo runner)");
        assertFalse(blockedRetry.stderr.contains(fixture.password));

        // API também recusa inicialização diante de histórico com migration failed
        grantReadOnlyHistorySelectToApp(fixture);
        try (HikariDataSource appDs = openAppDataSource()) {
            SchemaValidationException ex = assertThrows(SchemaValidationException.class,
                    () -> ReadOnlySchemaValidatorTestBridge.validateWithLocations(appDs, NONTX_RECOVERY_LOCATION));
            assertTrue(ex.getMessage().contains("V2") || ex.getMessage().contains("FAILED"),
                    "Validador somente leitura deve denunciar falha em V2: " + ex.getMessage());
        }

        // 4. Procedimento operacional explícito e auditado (fora do runner): operador inspeciona estado parcial e executa repair
        Flyway.configure()
                .dataSource(disposableDbUrl, fixture.role, fixture.password)
                .locations(NONTX_RECOVERY_LOCATION)
                .defaultSchema(RewitMigrationRunner.SCHEMA_PUBLIC)
                .schemas(RewitMigrationRunner.SCHEMA_PUBLIC)
                .table(RewitMigrationRunner.HISTORY_TABLE)
                .cleanDisabled(true)
                .load()
                .repair();

        // 5. Após o repair explícito, o runner executa V2 (DROP INDEX CONCURRENTLY IF EXISTS + CREATE INDEX CONCURRENTLY) com sucesso
        RunOutput recoveredRun = runRunner(fixture, NONTX_RECOVERY_LOCATION);
        assertEquals(RewitMigrationRunner.EXIT_SUCCESS, recoveredRun.exitCode, "stderr=" + recoveredRun.stderr);
        assertTrue(recoveredRun.stdout.contains("executadas=1"), "stdout=" + recoveredRun.stdout);
        assertTrue(recoveredRun.stdout.contains("versaoAtual=2"), "stdout=" + recoveredRun.stdout);
        assertEquals(2, countHistoryRows(fixture, true));
        assertEquals(0, countHistoryRows(fixture, false));
        assertIndexValidAndReady(fixture, "idx_recovery_nontx_sessions_ref");

        try (HikariDataSource appDs = openAppDataSource()) {
            assertDoesNotThrow(() -> ReadOnlySchemaValidatorTestBridge.validateWithLocations(appDs, NONTX_RECOVERY_LOCATION));
        }

        RunOutput idempotent = runRunner(fixture, NONTX_RECOVERY_LOCATION);
        assertEquals(RewitMigrationRunner.EXIT_SUCCESS, idempotent.exitCode);
        assertTrue(idempotent.stdout.contains("executadas=0"));
    }

    @Test
    @DisplayName("Cenário 4 (V1–V46 real): falha real na V16 grava success=false sem alterar V1–V46, mantém runner/API fail-closed e retoma após reparo explícito")
    void realV16FailedHistoryRowBlocksRunnerAndApiUntilExplicitRepair() throws SQLException {
        MigratorFixture fixture = provisionManagedDatabaseAndMigrator();

        // 1. Aplica V1..V15
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

        // 2. Induz falha REAL na V16 sem alterar V16: adiciona uma constraint UNIQUE respaldada por um índice
        //    homônimo em public.auth_sessions, fazendo o DROP INDEX CONCURRENTLY da V16 falhar genuinamente no
        //    PostgreSQL ("cannot drop index ... because constraint ... requires it").
        try (Connection dbAdmin = openSuperuserConnection(disposableDbUrl);
             Statement st = dbAdmin.createStatement()) {
            st.execute("ALTER TABLE public.auth_sessions "
                    + "ADD CONSTRAINT idx_auth_sessions_replaced_by_session_id UNIQUE (id, replaced_by_session_id)");
        }

        // 3. Runner executa V16 real (executeInTransaction=false) -> falha e Flyway registra version='16', success=false
        RunOutput failedV16 = runRunner(fixture, RewitMigrationRunner.MIGRATION_LOCATION);
        assertEquals(RewitMigrationRunner.EXIT_MIGRATION_ERROR, failedV16.exitCode);
        assertEquals(15, countHistoryRows(fixture, true));
        assertEquals(1, countHistoryRows(fixture, false),
                "Falha real da V16 deve registrar exatamente 1 linha success=false em flyway_schema_history");
        assertTrue(failedVersionExists(fixture, "16"), "Versão 16 deve constar com success=false");
        assertFalse(failedV16.stderr.contains(fixture.password));

        // 4. Remove a constraint conflitante externa (causa sanada), mas NÃO executa repair ainda
        try (Connection dbAdmin = openSuperuserConnection(disposableDbUrl);
             Statement st = dbAdmin.createStatement()) {
            st.execute("ALTER TABLE public.auth_sessions "
                    + "DROP CONSTRAINT idx_auth_sessions_replaced_by_session_id");
        }

        // 5. Runner DEVE permanecer fail-closed (jamais auto-repair/baseline/clean)
        RunOutput stillBlocked = runRunner(fixture, RewitMigrationRunner.MIGRATION_LOCATION);
        assertEquals(RewitMigrationRunner.EXIT_MIGRATION_ERROR, stillBlocked.exitCode,
                "Runner não pode avançar automaticamente com V16 marcada como failed no histórico");
        assertTrue(failedVersionExists(fixture, "16"), "Linha failed de V16 não pode ter sido apagada pelo runner");

        // API (rewit_app) também recusa startup
        grantReadOnlyHistorySelectToApp(fixture);
        try (HikariDataSource appDs = openAppDataSource()) {
            SchemaValidationException ex = assertThrows(SchemaValidationException.class,
                    () -> new ReadOnlyFlywaySchemaValidator(appDs).validate());
            assertTrue(ex.getMessage().contains("V16") || ex.getMessage().contains("FAILED"),
                    "Validador somente leitura deve denunciar V16 failed: " + ex.getMessage());
        }

        // 6. Reparo administrativo explícito (fora do runner) + retomada limpa de V16..V46
        Flyway.configure()
                .dataSource(disposableDbUrl, fixture.role, fixture.password)
                .locations(RewitMigrationRunner.MIGRATION_LOCATION)
                .defaultSchema(RewitMigrationRunner.SCHEMA_PUBLIC)
                .schemas(RewitMigrationRunner.SCHEMA_PUBLIC)
                .table(RewitMigrationRunner.HISTORY_TABLE)
                .cleanDisabled(true)
                .load()
                .repair();

        RunOutput recovered = runRunner(fixture, RewitMigrationRunner.MIGRATION_LOCATION);
        assertEquals(RewitMigrationRunner.EXIT_SUCCESS, recovered.exitCode, "stderr=" + recovered.stderr);
        assertTrue(recovered.stdout.contains("executadas=31"), "stdout=" + recovered.stdout);
        assertTrue(recovered.stdout.contains("versaoAtual=46"), "stdout=" + recovered.stdout);
        assertTrue(recovered.stdout.contains("rewit_assert_runtime_privileges=OK"));

        assertFullV1ToV46History(fixture);
        assertIndexValidAndReady(fixture, "idx_auth_sessions_replaced_by_session_id");

        try (HikariDataSource appDs = openAppDataSource()) {
            assertDoesNotThrow(() -> new ReadOnlyFlywaySchemaValidator(appDs).validate());
        }

        // 7. Reexecução subsequente idempotente
        RunOutput idempotent = runRunner(fixture, RewitMigrationRunner.MIGRATION_LOCATION);
        assertEquals(RewitMigrationRunner.EXIT_SUCCESS, idempotent.exitCode);
        assertTrue(idempotent.stdout.contains("executadas=0"));
        assertTrue(idempotent.stdout.contains("versaoAtual=46"));
    }

    private record MigratorFixture(String role, String password) {
    }

    private record RunOutput(int exitCode, String stdout, String stderr) {
    }

    private MigratorFixture provisionManagedDatabaseAndMigrator() throws SQLException {
        String suffix = randomHex(6);
        disposableDbName = DB_PREFIX + suffix;
        disposableDbMarker = "rewit-migration-recovery-test:" + UUID.randomUUID();
        disposableDbUrl = INFRA.datasourceUrlForDatabase(disposableDbName);

        String managedAdminRole = "rewit_mradmin_" + suffix;
        String managedAdminCredential = generateEphemeralCredential();
        String migratorRole = "rewit_mrmig_" + suffix;
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

    private RunOutput runRunner(MigratorFixture fixture, String location) {
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        int code = RewitMigrationRunner.runForLocalIntegrationTest(
                new String[0],
                Map.of(
                        RewitMigrationRunner.ENV_DATABASE_URL, disposableDbUrl,
                        RewitMigrationRunner.ENV_FLYWAY_DB_USER, fixture.role,
                        RewitMigrationRunner.ENV_FLYWAY_DB_PASSWORD, fixture.password
                ),
                location,
                Duration.ofSeconds(30),
                new PrintStream(outBuf, true, StandardCharsets.UTF_8),
                new PrintStream(errBuf, true, StandardCharsets.UTF_8)
        );
        return new RunOutput(code, outBuf.toString(StandardCharsets.UTF_8), errBuf.toString(StandardCharsets.UTF_8));
    }

    private void grantReadOnlyHistorySelectToApp(MigratorFixture fixture) throws SQLException {
        try (Connection dbAdmin = openSuperuserConnection(disposableDbUrl);
             Statement st = dbAdmin.createStatement()) {
            st.execute("GRANT USAGE ON SCHEMA public TO rewit_app");
        }
        try (Connection migConn = DriverManager.getConnection(disposableDbUrl, fixture.role, fixture.password);
             Statement st = migConn.createStatement()) {
            st.execute("GRANT SELECT ON TABLE public.flyway_schema_history TO rewit_app");
        }
    }

    private HikariDataSource openAppDataSource() {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(disposableDbUrl);
        cfg.setUsername(INFRA.appDbUser());
        cfg.setPassword(INFRA.appDbPassword());
        cfg.setMaximumPoolSize(2);
        return new HikariDataSource(cfg);
    }

    private int countHistoryRows(MigratorFixture fixture, boolean successValue) throws SQLException {
        try (Connection conn = DriverManager.getConnection(disposableDbUrl, fixture.role, fixture.password);
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM public.flyway_schema_history WHERE version IS NOT NULL AND success = ?")) {
            ps.setBoolean(1, successValue);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getInt(1);
            }
        }
    }

    private boolean failedVersionExists(MigratorFixture fixture, String version) throws SQLException {
        try (Connection conn = DriverManager.getConnection(disposableDbUrl, fixture.role, fixture.password);
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM public.flyway_schema_history WHERE version = ? AND success = FALSE")) {
            ps.setString(1, version);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getInt(1) > 0;
            }
        }
    }

    private boolean relationExists(String qualifiedName) throws SQLException {
        try (Connection admin = openSuperuserConnection(disposableDbUrl);
             PreparedStatement ps = admin.prepareStatement("SELECT to_regclass(?) IS NOT NULL")) {
            ps.setString(1, qualifiedName);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getBoolean(1);
            }
        }
    }

    private void assertIndexValidAndReady(MigratorFixture fixture, String indexName) throws SQLException {
        try (Connection conn = DriverManager.getConnection(disposableDbUrl, fixture.role, fixture.password);
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT i.indisvalid, i.indisready "
                             + "FROM pg_index i "
                             + "JOIN pg_class c ON c.oid = i.indexrelid "
                             + "JOIN pg_namespace n ON n.oid = c.relnamespace "
                             + "WHERE n.nspname = 'public' AND c.relname = ?")) {
            ps.setString(1, indexName);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "Índice esperado não encontrado: " + indexName);
                assertTrue(rs.getBoolean("indisvalid"), "Índice deve estar válido: " + indexName);
                assertTrue(rs.getBoolean("indisready"), "Índice deve estar pronto: " + indexName);
            }
        }
    }

    private void assertFullV1ToV46History(MigratorFixture fixture) throws SQLException {
        Set<Integer> versions = new TreeSet<>();
        try (Connection conn = DriverManager.getConnection(disposableDbUrl, fixture.role, fixture.password);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT version, success FROM public.flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank")) {
            while (rs.next()) {
                assertTrue(rs.getBoolean("success"));
                versions.add(Integer.parseInt(rs.getString("version")));
            }
        }
        assertEquals(46, versions.size());
        for (int v = 1; v <= 46; v++) {
            assertTrue(versions.contains(v), "Versão ausente: V" + v);
        }
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
