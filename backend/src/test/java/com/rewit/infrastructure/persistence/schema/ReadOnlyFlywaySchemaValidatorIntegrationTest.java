package com.rewit.infrastructure.persistence.schema;

import com.rewit.RewitApplication;
import com.rewit.config.TestInfrastructureConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.context.ConfigurableApplicationContext;

import javax.sql.DataSource;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testes de integração reais contra PostgreSQL 18 descartável do {@link ReadOnlyFlywaySchemaValidator}
 * e do startup da API sem Flyway no runtime (F2-DEPLOY-03.5.2A).
 */
@DisplayName("F2-DEPLOY-03.5.2A: validação somente leitura do schema Flyway na API (rewit_app, zero DDL)")
class ReadOnlyFlywaySchemaValidatorIntegrationTest {

    private static final TestInfrastructureConfig INFRA = TestInfrastructureConfig.current();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String DB_PREFIX = "rewit_roval_";
    private static final Pattern DISPOSABLE_DB_PATTERN = Pattern.compile("^rewit_roval_[0-9a-f]{12}$");
    private static final Pattern DISPOSABLE_ROLE_PATTERN = Pattern.compile("^rewit_rovmig_[0-9a-f]{12}$");

    private final List<DisposableDb> disposableDbs = new ArrayList<>();
    private final List<String> disposableRoles = new ArrayList<>();
    private final List<HikariDataSource> poolsToClose = new ArrayList<>();
    private final List<ConfigurableApplicationContext> contextsToClose = new ArrayList<>();

    private record DisposableDb(String name, String marker, String url, String migratorUser, String migratorPassword) {
    }

    @AfterEach
    void cleanup() throws SQLException {
        for (ConfigurableApplicationContext ctx : contextsToClose) {
            try {
                ctx.close();
            } catch (Exception ignored) {
                // Melhor esforço
            }
        }
        contextsToClose.clear();

        for (HikariDataSource pool : poolsToClose) {
            try {
                pool.close();
            } catch (Exception ignored) {
                // Melhor esforço
            }
        }
        poolsToClose.clear();

        try (Connection admin = openSuperuserConnection(INFRA.datasourceUrl())) {
            for (DisposableDb db : disposableDbs) {
                assertTrue(DISPOSABLE_DB_PATTERN.matcher(db.name()).matches());
                try (PreparedStatement ps = admin.prepareStatement(
                        "SELECT shobj_description(oid, 'pg_database'), current_database() FROM pg_database WHERE datname = ?")) {
                    ps.setString(1, db.name());
                    try (ResultSet rs = ps.executeQuery()) {
                        assertTrue(rs.next());
                        assertEquals(db.marker(), rs.getString(1));
                        assertNotEquals(db.name(), rs.getString(2));
                    }
                }
                try (Statement st = admin.createStatement()) {
                    st.execute("DROP DATABASE " + db.name() + " WITH (FORCE)");
                }
            }
            for (String role : disposableRoles) {
                assertTrue(DISPOSABLE_ROLE_PATTERN.matcher(role).matches());
                try (Statement st = admin.createStatement()) {
                    st.execute("DROP ROLE IF EXISTS " + role);
                }
            }
        }
        disposableDbs.clear();
        disposableRoles.clear();
    }

    @Test
    @DisplayName("Schema válido (V1–V46): aprova sob rewit_app readOnly=true, comprova zero DDL e ausência de privilégio DDL em rewit_app")
    void acceptsValidSchemaUnderReadOnlyAppRoleAndProvesNoDdl() throws SQLException {
        DisposableDb db = createDisposableDatabase();
        migrateUpTo(db, null);

        HikariDataSource appPool = openAppPool(db);
        long rowsBefore = countHistoryRows(db);

        ReadOnlyFlywaySchemaValidator validator = new ReadOnlyFlywaySchemaValidator(appPool);
        assertDoesNotThrow(validator::validate,
                "Validação somente leitura deve aprovar histórico íntegro V1–V46 sob rewit_app");

        assertEquals(rowsBefore, countHistoryRows(db),
                "Validação não pode inserir nem alterar linhas em flyway_schema_history");

        // Comprova que o decorador somente leitura impõe READ ONLY real no PostgreSQL e bloqueia setReadOnly(false)
        ReadOnlyFlywaySchemaValidator.ReadOnlyEnforcingDataSource roDs =
                new ReadOnlyFlywaySchemaValidator.ReadOnlyEnforcingDataSource(appPool);
        try (Connection roConn = roDs.getConnection()) {
            assertTrue(roConn.isReadOnly());
            try (Statement st = roConn.createStatement();
                 ResultSet rs = st.executeQuery("SHOW default_transaction_read_only")) {
                assertTrue(rs.next());
                assertEquals("on", rs.getString(1), "Sessão PostgreSQL deve estar com default_transaction_read_only=on");
            }
            SQLException roSwitchEx = assertThrows(SQLException.class, () -> roConn.setReadOnly(false));
            assertEquals("25006", roSwitchEx.getSQLState());

            SQLException writeEx = assertThrows(SQLException.class, () -> {
                try (Statement st = roConn.createStatement()) {
                    // rewit_app possui UPDATE em public.users; o bloqueio com SQLState 25006 comprova
                    // que a sessão/transação está estritamente em READ ONLY no PostgreSQL.
                    st.executeUpdate("UPDATE public.users SET updated_at = updated_at WHERE false");
                }
            });
            assertEquals("25006", writeEx.getSQLState(),
                    "PostgreSQL deve rejeitar escrita em sessão READ ONLY (SQLState 25006)");
        }

        // Comprova que o pool Hikari retorna ao modo READ WRITE após encerrar a conexão decorada
        try (Connection normalConn = appPool.getConnection();
             Statement st = normalConn.createStatement();
             ResultSet rs = st.executeQuery("SHOW default_transaction_read_only")) {
            assertTrue(rs.next());
            assertEquals("off", rs.getString(1), "Pool Hikari não pode ficar preso em read-only após validação");
        }

        // Comprova que rewit_app (mesmo fora do decorador) não consegue executar DDL nem escrever em flyway_schema_history
        SQLException ddlEx = assertThrows(SQLException.class, () -> {
            try (Connection appConn = appPool.getConnection();
                 Statement st = appConn.createStatement()) {
                st.execute("CREATE TABLE public.should_never_succeed_by_app (id int)");
            }
        }, "rewit_app não pode criar tabelas em public");
        assertEquals("42501", ddlEx.getSQLState(), "DDL por rewit_app deve falhar com permission denied (42501)");

        SQLException historyWriteEx = assertThrows(SQLException.class, () -> {
            try (Connection appConn = appPool.getConnection();
                 Statement st = appConn.createStatement()) {
                st.executeUpdate("UPDATE public.flyway_schema_history SET checksum = checksum WHERE false");
            }
        }, "rewit_app não possui permissão de escrita em flyway_schema_history");
        assertEquals("42501", historyWriteEx.getSQLState(),
                "Escrita em flyway_schema_history por rewit_app deve falhar com permission denied (42501)");
    }

    @Test
    @DisplayName("Banco vazio (sem flyway_schema_history): falha fail-closed sem tentar criar tabela de histórico")
    void failsClosedOnEmptyDatabaseWithoutCreatingHistoryTable() throws SQLException {
        DisposableDb db = createDisposableDatabase();
        HikariDataSource appPool = openAppPool(db);

        SchemaValidationException ex = assertThrows(SchemaValidationException.class,
                () -> new ReadOnlyFlywaySchemaValidator(appPool).validate());
        assertTrue(ex.getMessage().contains("flyway_schema_history"));
        assertFalse(historyTableExists(db),
                "Validador somente leitura nunca pode tentar criar flyway_schema_history em banco vazio");

        FailureAnalysis analysis = new SchemaValidationFailureAnalyzer().analyze(ex);
        assertNotNull(analysis);
        assertTrue(analysis.getAction().contains("RewitMigrationRunner"));
        assertFalse(analysis.getDescription().contains(INFRA.appDbPassword()));
    }

    @Test
    @DisplayName("Migration pendente (banco em V45 com V46 no classpath): falha fail-closed sem aplicar V46")
    void failsClosedOnPendingMigration() throws SQLException {
        DisposableDb db = createDisposableDatabase();
        migrateUpTo(db, "45");

        HikariDataSource appPool = openAppPool(db);
        long rowsBefore = countHistoryRows(db);

        SchemaValidationException ex = assertThrows(SchemaValidationException.class,
                () -> new ReadOnlyFlywaySchemaValidator(appPool).validate());
        assertTrue(ex.getMessage().contains("pendentes") || ex.getMessage().contains("V46"),
                "Mensagem deve indicar migration pendente V46: " + ex.getMessage());
        assertEquals(rowsBefore, countHistoryRows(db),
                "Validador somente leitura nunca pode aplicar migrations pendentes");
    }

    @Test
    @DisplayName("Migration com falha (success=false no histórico): falha fail-closed")
    void failsClosedOnFailedMigrationRecord() throws SQLException {
        DisposableDb db = createDisposableDatabase();
        migrateUpTo(db, null);

        try (Connection mig = DriverManager.getConnection(db.url(), db.migratorUser(), db.migratorPassword());
             Statement st = mig.createStatement()) {
            st.executeUpdate("UPDATE public.flyway_schema_history SET success = false WHERE version = '46'");
        }

        HikariDataSource appPool = openAppPool(db);
        SchemaValidationException ex = assertThrows(SchemaValidationException.class,
                () -> new ReadOnlyFlywaySchemaValidator(appPool).validate());
        assertTrue(ex.getMessage().contains("46") || ex.getMessage().contains("FAILED"),
                "Mensagem deve reportar falha na migration V46: " + ex.getMessage());
    }

    @Test
    @DisplayName("Checksum divergente no histórico: falha fail-closed")
    void failsClosedOnChecksumMismatch() throws SQLException {
        DisposableDb db = createDisposableDatabase();
        migrateUpTo(db, null);

        try (Connection mig = DriverManager.getConnection(db.url(), db.migratorUser(), db.migratorPassword());
             Statement st = mig.createStatement()) {
            st.executeUpdate("UPDATE public.flyway_schema_history SET checksum = checksum + 1 WHERE version = '46'");
        }

        HikariDataSource appPool = openAppPool(db);
        SchemaValidationException ex = assertThrows(SchemaValidationException.class,
                () -> new ReadOnlyFlywaySchemaValidator(appPool).validate());
        assertTrue(ex.getMessage().contains("46") && ex.getMessage().toLowerCase().contains("checksum"),
                "Mensagem deve reportar divergência de checksum em V46: " + ex.getMessage());
    }

    @Test
    @DisplayName("Migration futura/inesperada (V999 aplicada no banco): falha fail-closed")
    void failsClosedOnUnexpectedFutureMigration() throws SQLException {
        DisposableDb db = createDisposableDatabase();
        migrateUpTo(db, null);

        try (Connection mig = DriverManager.getConnection(db.url(), db.migratorUser(), db.migratorPassword());
             Statement st = mig.createStatement()) {
            st.executeUpdate("""
                    INSERT INTO public.flyway_schema_history
                        (installed_rank, version, description, type, script, checksum, installed_by, execution_time, success)
                    VALUES
                        (999, '999', 'future_unexpected_migration', 'SQL', 'V999__future_unexpected_migration.sql', 123456, current_user, 1, true)
                    """);
        }

        HikariDataSource appPool = openAppPool(db);
        SchemaValidationException ex = assertThrows(SchemaValidationException.class,
                () -> new ReadOnlyFlywaySchemaValidator(appPool).validate());
        assertTrue(ex.getMessage().contains("999"),
                "Mensagem deve reportar migration futura/inesperada V999: " + ex.getMessage());
    }

    @Test
    @DisplayName("Startup completo da API sem Flyway aborta antes do HTTP em banco vazio/pendente e sobe sem credencial migrator em banco migrado")
    void fullApiStartupWithoutFlywayAbortsBeforeHttpWhenUnmigratedAndSucceedsWhenMigrated() throws Exception {
        // 1. Banco vazio -> aborta ANTES de abrir porta HTTP e sem criar flyway_schema_history
        DisposableDb emptyDb = createDisposableDatabase();
        int failedPort = findFreeLoopbackPort();
        Map<String, Object> emptyProps = baseApiPropertiesWithoutFlyway(emptyDb, failedPort);

        Exception emptyEx = assertThrows(Exception.class, () -> startApi(emptyProps));
        Throwable emptyRoot = rootCause(emptyEx);
        assertTrue(emptyRoot instanceof SchemaValidationException,
                "Esperava SchemaValidationException, recebeu: " + emptyRoot);
        assertFalse(isPortAcceptingConnections(failedPort),
                "Porta HTTP não pode abrir quando a validação somente leitura do schema falha");
        assertFalse(historyTableExists(emptyDb),
                "API sem Flyway nunca pode criar flyway_schema_history");

        // 2. Banco migrado previamente -> API sobe SEM nenhuma credencial migrator, SEM flywayInitializer e responde /actuator/health 200 UP
        DisposableDb migratedDb = createDisposableDatabase();
        migrateUpTo(migratedDb, null);

        Map<String, Object> validProps = baseApiPropertiesWithoutFlyway(migratedDb, 0);
        ConfigurableApplicationContext ctx = startApi(validProps);
        assertTrue(ctx.isActive());
        assertFalse(ctx.containsBean("flywayInitializer"),
                "Bean flywayInitializer não deve existir quando spring.flyway.enabled=false");
        assertTrue(ctx.containsBean(ReadOnlySchemaValidationConfig.SCHEMA_VALIDATOR_BEAN_NAME),
                "Validador somente leitura do schema deve estar ativo");

        String mgmtPort = ctx.getEnvironment().getProperty("local.management.port");
        HttpResponse<String> health = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + mgmtPort + "/actuator/health")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, health.statusCode());
        assertTrue(health.body().contains("UP"));
    }

    @Test
    @DisplayName("R1-C: bloqueia unwrap(), isWrapperFor() e escape via DatabaseMetaData.getConnection() / Statement.getConnection()")
    void blocksUnwrapIsWrapperForAndIndirectConnectionEscape() throws SQLException {
        DisposableDb db = createDisposableDatabase();
        migrateUpTo(db, null);

        HikariDataSource appPool = openAppPool(db, 1);
        ReadOnlyFlywaySchemaValidator.ReadOnlyEnforcingDataSource roDs =
                new ReadOnlyFlywaySchemaValidator.ReadOnlyEnforcingDataSource(appPool);

        // DataSource wrapper bloqueia unwrap/isWrapperFor
        assertFalse(roDs.isWrapperFor(HikariDataSource.class));
        assertFalse(roDs.isWrapperFor(DataSource.class));
        SQLException dsUnwrapEx = assertThrows(SQLException.class, () -> roDs.unwrap(HikariDataSource.class));
        assertEquals("25006", dsUnwrapEx.getSQLState());

        try (Connection roConn = roDs.getConnection()) {
            // Connection proxy bloqueia unwrap/isWrapperFor para Connection e PGConnection
            assertFalse(roConn.isWrapperFor(Connection.class));
            assertFalse(roConn.isWrapperFor(PGConnection.class));
            assertEquals("25006",
                    assertThrows(SQLException.class, () -> roConn.unwrap(Connection.class)).getSQLState());
            assertEquals("25006",
                    assertThrows(SQLException.class, () -> roConn.unwrap(PGConnection.class)).getSQLState());

            // DatabaseMetaData.getConnection() devolve o próprio proxy e bloqueia unwrap/isWrapperFor
            DatabaseMetaData meta = roConn.getMetaData();
            assertSame(roConn, meta.getConnection(), "DatabaseMetaData.getConnection() deve retornar o proxy somente leitura");
            assertFalse(meta.isWrapperFor(DatabaseMetaData.class));
            assertEquals("25006",
                    assertThrows(SQLException.class, () -> meta.unwrap(DatabaseMetaData.class)).getSQLState());
            assertEquals("25006",
                    assertThrows(SQLException.class, () -> meta.getConnection().setReadOnly(false)).getSQLState());

            // Statement e PreparedStatement devolvem o próprio proxy em getConnection() e bloqueiam unwrap/isWrapperFor
            try (Statement st = roConn.createStatement()) {
                assertSame(roConn, st.getConnection());
                assertFalse(st.isWrapperFor(Statement.class));
                assertEquals("25006",
                        assertThrows(SQLException.class, () -> st.unwrap(Statement.class)).getSQLState());
                assertEquals("25006",
                        assertThrows(SQLException.class, () -> st.getConnection().setReadOnly(false)).getSQLState());
            }
            try (PreparedStatement ps = roConn.prepareStatement("SELECT 1")) {
                assertSame(roConn, ps.getConnection());
                assertFalse(ps.isWrapperFor(PreparedStatement.class));
                assertEquals("25006",
                        assertThrows(SQLException.class, () -> ps.unwrap(PreparedStatement.class)).getSQLState());
                assertEquals("25006",
                        assertThrows(SQLException.class, () -> ps.getConnection().setReadOnly(false)).getSQLState());
            }
        }

        // Pool saudável e sem leaks após uso
        assertEquals(0, appPool.getHikariPoolMXBean().getActiveConnections());
    }

    @Test
    @DisplayName("R1-B: HikariCP real comprova retorno limpo no caminho normal, eviction física na falha de restauração, fallback abort() e isolamento de outras conexões")
    void hikariRealEvictsContaminatedConnectionsRecoversHealthyPoolAndPreservesOtherConnections() throws SQLException {
        DisposableDb db = createDisposableDatabase();
        migrateUpTo(db, null);

        // 1. Caminho normal com pool de 1 conexão: mesma conexão física (pid1) retorna saudável (off) sem leak
        HikariDataSource singlePool = openAppPool(db, 1);
        ReadOnlyFlywaySchemaValidator.ReadOnlyEnforcingDataSource roSingle =
                new ReadOnlyFlywaySchemaValidator.ReadOnlyEnforcingDataSource(singlePool);
        int normalPid;
        try (Connection c = roSingle.getConnection()) {
            normalPid = queryBackendPid(c);
            assertEquals("on", queryReadOnlySetting(c));
        }
        assertEquals(0, singlePool.getHikariPoolMXBean().getActiveConnections());
        try (Connection reused = singlePool.getConnection()) {
            assertEquals(normalPid, queryBackendPid(reused), "No caminho saudável a conexão limpa pode ser reutilizada");
            assertEquals("off", queryReadOnlySetting(reused));
        }
        assertEquals(0, singlePool.getHikariPoolMXBean().getActiveConnections());

        // 2. Falha durante validação (banco vazio) não vaza nem deixa pool em read-only
        DisposableDb emptyDb = createDisposableDatabase();
        HikariDataSource emptyPool = openAppPool(emptyDb, 1);
        assertThrows(SchemaValidationException.class, () -> new ReadOnlyFlywaySchemaValidator(emptyPool).validate());
        assertEquals(0, emptyPool.getHikariPoolMXBean().getActiveConnections());
        try (Connection c = emptyPool.getConnection()) {
            assertEquals("off", queryReadOnlySetting(c));
        }

        // 3. Pool com 2 conexões: falha na restauração de pid1 (sessão derrubada) evicta pid1 sem fechar o pool nem afetar pid2
        HikariDataSource dualPool = openAppPool(db, 2);
        ReadOnlyFlywaySchemaValidator.ReadOnlyEnforcingDataSource roDual =
                new ReadOnlyFlywaySchemaValidator.ReadOnlyEnforcingDataSource(dualPool);

        try (Connection healthyConn = dualPool.getConnection()) {
            int healthyPid = queryBackendPid(healthyConn);
            assertEquals("off", queryReadOnlySetting(healthyConn));

            Connection roConn = roDual.getConnection();
            int brokenPid = queryBackendPid(roConn);
            assertNotEquals(healthyPid, brokenPid);
            assertEquals("on", queryReadOnlySetting(roConn));

            // Encerra a sessão física de brokenPid no servidor PostgreSQL para forçar falha real na restauração
            terminateBackend(db, brokenPid);

            // close() falha na restauração e evicta brokenPid (nunca devolve ao pool)
            assertThrows(SQLException.class, roConn::close);

            // Pool continua aberto e a outra conexão saudável (healthyPid) segue 100% operacional
            assertFalse(dualPool.isClosed(), "Eviction de uma conexão não pode fechar o pool inteiro");
            assertEquals(healthyPid, queryBackendPid(healthyConn));
            assertEquals("off", queryReadOnlySetting(healthyConn));
        }

        assertEquals(0, dualPool.getHikariPoolMXBean().getActiveConnections(), "Zero leaks após eviction");
        // Próximas aquisições recebem somente conexões saudáveis com default_transaction_read_only=off
        try (Connection fresh1 = dualPool.getConnection();
             Connection fresh2 = dualPool.getConnection()) {
            assertEquals("off", queryReadOnlySetting(fresh1));
            assertEquals("off", queryReadOnlySetting(fresh2));
        }
        assertEquals(0, dualPool.getHikariPoolMXBean().getActiveConnections());

        // 4. Fallback abort() quando HikariDataSource.evictConnection lança RuntimeException
        AtomicInteger evictAttempts = new AtomicInteger(0);
        HikariConfig failingEvictCfg = new HikariConfig();
        failingEvictCfg.setPoolName("roval-failevict-" + db.name());
        failingEvictCfg.setJdbcUrl(db.url());
        failingEvictCfg.setUsername(INFRA.appDbUser());
        failingEvictCfg.setPassword(INFRA.appDbPassword());
        failingEvictCfg.setMaximumPoolSize(1);
        failingEvictCfg.setMinimumIdle(1);
        HikariDataSource failingEvictPool = new HikariDataSource(failingEvictCfg) {
            @Override
            public void evictConnection(Connection connection) {
                evictAttempts.incrementAndGet();
                throw new IllegalStateException("Falha simulada em evictConnection");
            }
        };
        poolsToClose.add(failingEvictPool);

        ReadOnlyFlywaySchemaValidator.ReadOnlyEnforcingDataSource roFallback =
                new ReadOnlyFlywaySchemaValidator.ReadOnlyEnforcingDataSource(failingEvictPool);
        Connection fallbackConn = roFallback.getConnection();
        int evictedPid = queryBackendPid(fallbackConn);
        terminateBackend(db, evictedPid);

        SQLException closeEx = assertThrows(SQLException.class, fallbackConn::close);
        assertEquals(1, evictAttempts.get(), "Deve tentar evictConnection primeiro no HikariDataSource");
        assertTrue(closeEx.getSuppressed().length >= 1,
                "Exceção da eviction deve ser preservada como suppressed antes do fallback abort()");

        // Mesmo com falha no evictConnection, o fallback abort() encerra a conexão física e permite nova conexão saudável
        try (Connection recovered = failingEvictPool.getConnection()) {
            int newPid = queryBackendPid(recovered);
            assertNotEquals(evictedPid, newPid, "Conexão contaminada jamais pode ser reutilizada");
            assertEquals("off", queryReadOnlySetting(recovered));
        }
    }

    @Test
    @DisplayName("R1-E: spring.main.lazy-initialization=true não desativa a validação eager (@Lazy(false)) no startup da API")
    void lazyInitializationDoesNotBypassReadOnlySchemaValidationOnStartup() throws Exception {
        DisposableDb emptyDb = createDisposableDatabase();
        int failedPort = findFreeLoopbackPort();
        Map<String, Object> props = baseApiPropertiesWithoutFlyway(emptyDb, failedPort);
        props.put("spring.main.lazy-initialization", "true");

        Exception ex = assertThrows(Exception.class, () -> startApi(props),
                "Mesmo com spring.main.lazy-initialization=true, a validação do schema deve abortar o startup");
        Throwable root = rootCause(ex);
        assertTrue(root instanceof SchemaValidationException,
                "Esperava SchemaValidationException com lazy-initialization=true, recebeu: " + root);
        assertFalse(isPortAcceptingConnections(failedPort),
                "Porta HTTP não pode abrir com lazy-initialization=true diante de banco incompatível");
        assertFalse(historyTableExists(emptyDb));
    }

    private DisposableDb createDisposableDatabase() throws SQLException {
        String suffix = randomHex(6);
        String dbName = DB_PREFIX + suffix;
        String marker = "rewit-ro-schema-validator-test:" + UUID.randomUUID();
        String url = INFRA.datasourceUrlForDatabase(dbName);
        String migratorRole = "rewit_rovmig_" + suffix;
        String migratorPassword = "RovMig-" + randomHex(12);

        try (Connection admin = openSuperuserConnection(INFRA.datasourceUrl());
             Statement st = admin.createStatement()) {
            st.execute("CREATE DATABASE " + dbName);
            st.execute("COMMENT ON DATABASE " + dbName + " IS '" + marker + "'");
            st.execute("CREATE ROLE " + migratorRole
                    + " WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS PASSWORD '"
                    + migratorPassword + "'");
            disposableRoles.add(migratorRole);
            st.execute("GRANT CONNECT ON DATABASE " + dbName + " TO " + migratorRole + ", rewit_app, rewit_media, rewit_mod");
        }
        try (Connection dbAdmin = openSuperuserConnection(url);
             Statement st = dbAdmin.createStatement()) {
            for (String ext : List.of("uuid-ossp", "postgis", "pg_trgm", "pgcrypto", "unaccent")) {
                st.execute("CREATE EXTENSION IF NOT EXISTS \"" + ext + "\" WITH SCHEMA public");
            }
            st.execute("GRANT USAGE, CREATE ON SCHEMA public TO " + migratorRole);
        }

        DisposableDb db = new DisposableDb(dbName, marker, url, migratorRole, migratorPassword);
        disposableDbs.add(db);
        return db;
    }

    private static void migrateUpTo(DisposableDb db, String targetVersion) {
        var cfg = Flyway.configure()
                .dataSource(db.url(), db.migratorUser(), db.migratorPassword())
                .locations("classpath:db/migration")
                .defaultSchema("public")
                .schemas("public")
                .baselineOnMigrate(false)
                .validateOnMigrate(true)
                .cleanDisabled(true)
                .createSchemas(false)
                .configuration(Map.of("flyway.postgresql.transactional.lock", "false"));
        if (targetVersion != null) {
            cfg.target(targetVersion);
        }
        assertTrue(cfg.load().migrate().success);
    }

    private HikariDataSource openAppPool(DisposableDb db) {
        return openAppPool(db, 2);
    }

    private HikariDataSource openAppPool(DisposableDb db, int maxPoolSize) {
        HikariConfig cfg = new HikariConfig();
        cfg.setPoolName("roval-app-" + db.name() + "-" + maxPoolSize);
        cfg.setJdbcUrl(db.url());
        cfg.setUsername(INFRA.appDbUser());
        cfg.setPassword(INFRA.appDbPassword());
        cfg.setMaximumPoolSize(maxPoolSize);
        cfg.setMinimumIdle(1);
        HikariDataSource ds = new HikariDataSource(cfg);
        poolsToClose.add(ds);
        return ds;
    }

    private static int queryBackendPid(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT pg_backend_pid()")) {
            assertTrue(rs.next());
            return rs.getInt(1);
        }
    }

    private static String queryReadOnlySetting(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SHOW default_transaction_read_only")) {
            assertTrue(rs.next());
            return rs.getString(1);
        }
    }

    private static void terminateBackend(DisposableDb db, int pid) throws SQLException {
        try (Connection admin = openSuperuserConnection(db.url());
             PreparedStatement ps = admin.prepareStatement("SELECT pg_terminate_backend(?)")) {
            ps.setInt(1, pid);
            ps.execute();
        }
    }

    private boolean historyTableExists(DisposableDb db) throws SQLException {
        try (Connection admin = openSuperuserConnection(db.url());
             PreparedStatement ps = admin.prepareStatement("SELECT to_regclass('public.flyway_schema_history') IS NOT NULL");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            return rs.getBoolean(1);
        }
    }

    private long countHistoryRows(DisposableDb db) throws SQLException {
        try (Connection admin = openSuperuserConnection(db.url());
             PreparedStatement ps = admin.prepareStatement("SELECT COUNT(*) FROM public.flyway_schema_history");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            return rs.getLong(1);
        }
    }

    private Map<String, Object> baseApiPropertiesWithoutFlyway(DisposableDb db, int serverPort) {
        Map<String, Object> props = new HashMap<>();
        props.put("server.port", String.valueOf(serverPort));
        props.put("management.server.port", "0");
        props.put("spring.datasource.url", db.url());
        props.put("spring.datasource.username", INFRA.appDbUser());
        props.put("spring.datasource.password", INFRA.appDbPassword());
        props.put("rewit.datasource.media.username", INFRA.mediaDbUser());
        props.put("rewit.datasource.media.password", INFRA.mediaDbPassword());
        props.put("rewit.datasource.moderation.username", INFRA.modDbUser());
        props.put("rewit.datasource.moderation.password", INFRA.modDbPassword());
        props.put("spring.flyway.enabled", "false");
        props.put("spring.flyway.user", "");
        props.put("spring.flyway.password", "");
        props.put("spring.flyway.url", "");
        props.put(ReadOnlySchemaValidationConfig.ENABLED_PROPERTY, "true");
        props.put("spring.jpa.hibernate.ddl-auto", "validate");
        props.put("spring.jpa.database-platform", "org.hibernate.spatial.dialect.postgis.PostgisPG95Dialect");
        props.put("spring.data.redis.host", INFRA.redisHost());
        props.put("spring.data.redis.port", String.valueOf(INFRA.redisPort()));
        props.put("rewit.minio.endpoint", INFRA.storageEndpoint());
        props.put("rewit.minio.access-key", INFRA.storageAccessKey());
        props.put("rewit.minio.secret-key", INFRA.storageSecretKey());
        props.put("rewit.minio.bucket-name", INFRA.storageBucket());
        return props;
    }

    private ConfigurableApplicationContext startApi(Map<String, Object> properties) {
        SpringApplication app = new SpringApplication(RewitApplication.class);
        app.setWebApplicationType(WebApplicationType.SERVLET);
        app.setAdditionalProfiles("local");
        String[] cliArgs = properties.entrySet().stream()
                .map(e -> "--" + e.getKey() + "=" + (e.getValue() == null ? "" : e.getValue()))
                .toArray(String[]::new);
        ConfigurableApplicationContext ctx = app.run(cliArgs);
        contextsToClose.add(ctx);
        return ctx;
    }

    private static Connection openSuperuserConnection(String url) throws SQLException {
        return DriverManager.getConnection(url, INFRA.migrationDbUser(), INFRA.migrationDbPassword());
    }

    private static Throwable rootCause(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur;
    }

    private static int findFreeLoopbackPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    private static boolean isPortAcceptingConnections(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 300);
            return true;
        } catch (IOException expected) {
            return false;
        }
    }

    private static String randomHex(int bytes) {
        byte[] buf = new byte[bytes];
        RANDOM.nextBytes(buf);
        return HexFormat.of().formatHex(buf);
    }
}
