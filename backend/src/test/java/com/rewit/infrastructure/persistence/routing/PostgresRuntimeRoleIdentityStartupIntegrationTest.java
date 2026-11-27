package com.rewit.infrastructure.persistence.routing;

import com.rewit.RewitApplication;
import com.rewit.application.port.AppTransactionPort;
import com.rewit.application.port.MediaLifecycleTransactionPort;
import com.rewit.application.port.ModerationTransactionPort;
import com.rewit.config.TestInfrastructureConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testes adversariais e de startup real contra PostgreSQL 18 descartável (Podman/Docker) com conexões TCP
 * realmente autenticadas (F2-DEPLOY-02).
 *
 * <p>Comprova que:
 * <ul>
 *   <li>Configurações inválidas de {@code APP_DB_*}, {@code MEDIA_DB_*} e {@code MOD_DB_*} abortam o startup
 *       antes de o Flyway executar migrations e antes de o servidor HTTP atender requisições.</li>
 *   <li>Qualquer divergência entre o nome configurado e a identidade efetiva PostgreSQL ({@code session_user},
 *       {@code current_user}, atributos administrativos, {@code INHERIT} ou memberships) é rejeitada mesmo
 *       quando a autenticação e {@code SELECT 1} funcionam.</li>
 *   <li>Com as três roles provisionadas corretamente, a aplicação inicializa, migra via credencial migrator
 *       dedicada, atende requisições e preserva o roteamento e {@link DatabaseRoleContext} limpo.</li>
 * </ul>
 */
@DisplayName("F2-DEPLOY-02: validação fail-closed das identidades reais PostgreSQL no startup")
class PostgresRuntimeRoleIdentityStartupIntegrationTest {

    private static final TestInfrastructureConfig INFRA = TestInfrastructureConfig.current();
    private static final String DB_PREFIX = "rewit_idtest_";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final List<DisposableDb> disposableDbs = new ArrayList<>();
    private final List<ConfigurableApplicationContext> startedContexts = new ArrayList<>();

    private record DisposableDb(String name, String marker, String url) {
    }

    @AfterEach
    void cleanup() throws SQLException {
        DatabaseRoleContext.clear();
        for (ConfigurableApplicationContext ctx : startedContexts) {
            try {
                ctx.close();
            } catch (Exception ignored) {
                // Melhor esforço no encerramento do contexto de teste
            }
        }
        startedContexts.clear();

        for (DisposableDb db : disposableDbs) {
            dropDisposableDatabase(db);
        }
        disposableDbs.clear();
    }

    private DisposableDb createDisposableDatabase() throws SQLException {
        byte[] rnd = new byte[6];
        RANDOM.nextBytes(rnd);
        String dbName = DB_PREFIX + HexFormat.of().formatHex(rnd);
        String marker = "rewit-role-identity-test:" + UUID.randomUUID();
        String url = INFRA.datasourceUrlForDatabase(dbName);

        try (Connection admin = DriverManager.getConnection(
                INFRA.datasourceUrl(), INFRA.migrationDbUser(), INFRA.migrationDbPassword());
             Statement st = admin.createStatement()) {
            st.execute("CREATE DATABASE " + dbName);
            st.execute("COMMENT ON DATABASE " + dbName + " IS '" + marker + "'");
            st.execute("GRANT CONNECT ON DATABASE " + dbName + " TO rewit_app, rewit_media, rewit_mod");
        }
        try (Connection dbAdmin = DriverManager.getConnection(url, INFRA.migrationDbUser(), INFRA.migrationDbPassword());
             Statement st = dbAdmin.createStatement()) {
            st.execute("CREATE EXTENSION IF NOT EXISTS \"uuid-ossp\"");
            st.execute("CREATE EXTENSION IF NOT EXISTS \"postgis\"");
            st.execute("CREATE EXTENSION IF NOT EXISTS \"pg_trgm\"");
            st.execute("CREATE EXTENSION IF NOT EXISTS \"pgcrypto\"");
        }
        DisposableDb db = new DisposableDb(dbName, marker, url);
        disposableDbs.add(db);
        return db;
    }

    private void dropDisposableDatabase(DisposableDb db) throws SQLException {
        try (Connection admin = DriverManager.getConnection(
                INFRA.datasourceUrl(), INFRA.migrationDbUser(), INFRA.migrationDbPassword())) {
            try (PreparedStatement ps = admin.prepareStatement(
                    "SELECT shobj_description(oid, 'pg_database') FROM pg_database WHERE datname = ?")) {
                ps.setString(1, db.name());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next() || !db.marker().equals(rs.getString(1))) {
                        throw new IllegalStateException("Recusado DROP de database sem marcador esperado: " + db.name());
                    }
                }
            }
            try (Statement st = admin.createStatement()) {
                st.execute("DROP DATABASE " + db.name() + " WITH (FORCE)");
            }
        }
    }

    private boolean flywayHistoryExists(DisposableDb db) throws SQLException {
        try (Connection conn = DriverManager.getConnection(db.url(), INFRA.migrationDbUser(), INFRA.migrationDbPassword());
             PreparedStatement ps = conn.prepareStatement("SELECT to_regclass('public.flyway_schema_history') IS NOT NULL");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            return rs.getBoolean(1);
        }
    }

    private Map<String, Object> baseStartupProperties(DisposableDb db) {
        Map<String, Object> props = new HashMap<>();
        props.put("server.port", "0");
        props.put("management.server.port", "0");
        props.put("spring.datasource.url", db.url());
        props.put("spring.datasource.username", INFRA.appDbUser());
        props.put("spring.datasource.password", INFRA.appDbPassword());
        props.put("rewit.datasource.media.username", INFRA.mediaDbUser());
        props.put("rewit.datasource.media.password", INFRA.mediaDbPassword());
        props.put("rewit.datasource.moderation.username", INFRA.modDbUser());
        props.put("rewit.datasource.moderation.password", INFRA.modDbPassword());
        props.put("spring.flyway.enabled", "true");
        props.put("spring.flyway.url", db.url());
        props.put("spring.flyway.user", INFRA.migrationDbUser());
        props.put("spring.flyway.password", INFRA.migrationDbPassword());
        props.put("spring.flyway.postgresql.transactional-lock", "false");
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

    private ConfigurableApplicationContext startApp(Map<String, Object> properties, Class<?>... extraSources) {
        List<Class<?>> sources = new ArrayList<>();
        sources.add(RewitApplication.class);
        sources.addAll(List.of(extraSources));
        SpringApplication app = new SpringApplication(sources.toArray(Class<?>[]::new));
        app.setWebApplicationType(WebApplicationType.SERVLET);
        app.setAdditionalProfiles("local");
        String[] cliArgs = properties.entrySet().stream()
                .map(e -> "--" + e.getKey() + "=" + (e.getValue() == null ? "" : e.getValue()))
                .toArray(String[]::new);
        ConfigurableApplicationContext ctx = app.run(cliArgs);
        startedContexts.add(ctx);
        return ctx;
    }

    private static HikariDataSource openTcpPool(String poolName, String jdbcUrl, String username, String password) {
        HikariConfig cfg = new HikariConfig();
        cfg.setPoolName(poolName);
        cfg.setJdbcUrl(jdbcUrl);
        cfg.setUsername(username);
        cfg.setPassword(password);
        cfg.setMaximumPoolSize(2);
        cfg.setMinimumIdle(1);
        return new HikariDataSource(cfg);
    }

    private static void assertSelectOneWorks(DataSource ds) throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT 1");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
        }
    }

    private static void execAdminSql(String sql) throws SQLException {
        try (Connection admin = DriverManager.getConnection(
                INFRA.datasourceUrl(), INFRA.migrationDbUser(), INFRA.migrationDbPassword());
             Statement st = admin.createStatement()) {
            st.execute(sql);
        }
    }

    private static void execDbAdminSql(DisposableDb db, String sql) throws SQLException {
        try (Connection admin = DriverManager.getConnection(
                db.url(), INFRA.migrationDbUser(), INFRA.migrationDbPassword());
             Statement st = admin.createStatement()) {
            st.execute(sql);
        }
    }

    private static Throwable rootCause(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur;
    }

    // --------------------------------------------------------------------------------------
    // 1. Configuração incorreta: falha antes do Flyway e antes de abrir porta HTTP
    // --------------------------------------------------------------------------------------

    @Test
    @DisplayName("Startup falha antes do Flyway para APP_DB_USER=rewit_mig, postgres, vazio, senha migrator e troca de roles")
    void rejectsMisconfiguredStartupBeforeFlywayRuns() throws SQLException {
        DisposableDb db = createDisposableDatabase();

        List<Map.Entry<String, Map<String, Object>>> invalidCases = List.of(
                Map.entry("APP_DB_USER=rewit_mig", Map.of("spring.datasource.username", "rewit_mig")),
                Map.entry("APP_DB_USER=postgres", Map.of("spring.datasource.username", "postgres")),
                Map.entry("APP_DB_USER vazio", Map.of("spring.datasource.username", "")),
                Map.entry("APP_DB_PASSWORD igual à senha migrator", Map.of("spring.datasource.password", INFRA.migrationDbPassword())),
                Map.entry("MEDIA_DB_USER=rewit_app", Map.of("rewit.datasource.media.username", "rewit_app")),
                Map.entry("MOD_DB_USER=rewit_media", Map.of("rewit.datasource.moderation.username", "rewit_media")),
                Map.entry("MOD_DB_PASSWORD igual a APP_DB_PASSWORD", Map.of("rewit.datasource.moderation.password", INFRA.appDbPassword()))
        );

        for (Map.Entry<String, Map<String, Object>> scenario : invalidCases) {
            Map<String, Object> props = baseStartupProperties(db);
            props.putAll(scenario.getValue());

            Exception ex = assertThrows(Exception.class, () -> startApp(props),
                    "Startup deveria falhar para cenário: " + scenario.getKey());
            Throwable root = rootCause(ex);
            assertTrue(root instanceof RuntimeDatabaseRoleException,
                    "Esperava RuntimeDatabaseRoleException em '" + scenario.getKey() + "', recebeu: " + root);
            assertFalse(root.getMessage().contains(INFRA.appDbPassword()), "Nunca expor APP_DB_PASSWORD");
            assertFalse(root.getMessage().contains(INFRA.migrationDbPassword()), "Nunca expor senha migrator");
            assertFalse(flywayHistoryExists(db),
                    "Flyway não pode ter executado migrations no cenário inválido: " + scenario.getKey());
        }
    }

    // --------------------------------------------------------------------------------------
    // 2. Identidade real incorreta: rejeitada mesmo quando autenticação e SELECT 1 funcionam
    // --------------------------------------------------------------------------------------

    @Test
    @DisplayName("Rejeita pool conectado realmente como outra role (owner/mod/app) mesmo quando SELECT 1 funciona")
    void rejectsMismatchedRealTcpIdentityEvenWhenSelectOneSucceeds() throws SQLException {
        DisposableDb db = createDisposableDatabase();
        try (HikariDataSource realApp = openTcpPool("t-app", db.url(), INFRA.appDbUser(), INFRA.appDbPassword());
             HikariDataSource realMedia = openTcpPool("t-media", db.url(), INFRA.mediaDbUser(), INFRA.mediaDbPassword());
             HikariDataSource realMod = openTcpPool("t-mod", db.url(), INFRA.modDbUser(), INFRA.modDbPassword());
             HikariDataSource realMigrator = openTcpPool("t-mig", db.url(), INFRA.migrationDbUser(), INFRA.migrationDbPassword())) {

            // Prova que autenticação TCP e SELECT 1 funcionam em todas as conexões
            assertSelectOneWorks(realApp);
            assertSelectOneWorks(realMedia);
            assertSelectOneWorks(realMod);
            assertSelectOneWorks(realMigrator);

            // Controle positivo
            assertDoesNotThrow(() -> new PostgresRuntimeRoleIdentityValidator(realApp, realMedia, realMod).validateAllPools());

            // 1. appDataSource conectado como owner/migrator
            RuntimeDatabaseRoleException exOwner = assertThrows(RuntimeDatabaseRoleException.class,
                    () -> new PostgresRuntimeRoleIdentityValidator(realMigrator, realMedia, realMod).validateAllPools());
            assertTrue(exOwner.getMessage().contains("appDataSource"));
            assertTrue(exOwner.getMessage().contains("session_user"));

            // 2. appDataSource conectado como rewit_mod
            RuntimeDatabaseRoleException exModInApp = assertThrows(RuntimeDatabaseRoleException.class,
                    () -> new PostgresRuntimeRoleIdentityValidator(realMod, realMedia, realMod).validateAllPools());
            assertTrue(exModInApp.getMessage().contains("appDataSource"));

            // 3. mediaLifecycleDataSource conectado como rewit_app
            RuntimeDatabaseRoleException exAppInMedia = assertThrows(RuntimeDatabaseRoleException.class,
                    () -> new PostgresRuntimeRoleIdentityValidator(realApp, realApp, realMod).validateAllPools());
            assertTrue(exAppInMedia.getMessage().contains("mediaLifecycleDataSource"));

            // 4. moderationDataSource conectado como rewit_app
            RuntimeDatabaseRoleException exAppInMod = assertThrows(RuntimeDatabaseRoleException.class,
                    () -> new PostgresRuntimeRoleIdentityValidator(realApp, realMedia, realApp).validateAllPools());
            assertTrue(exAppInMod.getMessage().contains("moderationDataSource"));
        }
    }

    @Test
    @DisplayName("Rejeita role com SUPERUSER, INHERIT, atributos privilegiados, memberships ou ALTER ROLE SET role (current_user != session_user)")
    void rejectsPrivilegedAttributesMembershipsAndSessionRoleDivergence() throws SQLException {
        DisposableDb db = createDisposableDatabase();

        try (HikariDataSource realApp = openTcpPool("t-app", db.url(), INFRA.appDbUser(), INFRA.appDbPassword());
             HikariDataSource realMedia = openTcpPool("t-media", db.url(), INFRA.mediaDbUser(), INFRA.mediaDbPassword());
             HikariDataSource realMod = openTcpPool("t-mod", db.url(), INFRA.modDbUser(), INFRA.modDbPassword())) {

            // A. SUPERUSER em rewit_app (SELECT 1 funciona, session_user=current_user=rewit_app, mas rolsuper=true)
            try {
                execAdminSql("ALTER ROLE rewit_app SUPERUSER");
                assertSelectOneWorks(realApp);
                RuntimeDatabaseRoleException ex = assertThrows(RuntimeDatabaseRoleException.class,
                        () -> new PostgresRuntimeRoleIdentityValidator(realApp, realMedia, realMod).validateAllPools());
                assertTrue(ex.getMessage().contains("SUPERUSER"));
            } finally {
                execAdminSql("ALTER ROLE rewit_app NOSUPERUSER");
            }

            // B. CREATEROLE, CREATEDB, REPLICATION, BYPASSRLS, INHERIT em rewit_media
            try {
                execAdminSql("ALTER ROLE rewit_media CREATEROLE CREATEDB REPLICATION BYPASSRLS INHERIT");
                assertSelectOneWorks(realMedia);
                RuntimeDatabaseRoleException ex = assertThrows(RuntimeDatabaseRoleException.class,
                        () -> new PostgresRuntimeRoleIdentityValidator(realApp, realMedia, realMod).validateAllPools());
                assertTrue(ex.getMessage().contains("CREATEROLE"));
                assertTrue(ex.getMessage().contains("CREATEDB"));
                assertTrue(ex.getMessage().contains("REPLICATION"));
                assertTrue(ex.getMessage().contains("BYPASSRLS"));
                assertTrue(ex.getMessage().contains("INHERIT"));
            } finally {
                execAdminSql("ALTER ROLE rewit_media NOCREATEROLE NOCREATEDB NOREPLICATION NOBYPASSRLS NOINHERIT");
            }

            // C. Membership indevida em rewit_mod (mesmo com NOINHERIT)
            try {
                execAdminSql("GRANT pg_read_all_data TO rewit_mod");
                assertSelectOneWorks(realMod);
                RuntimeDatabaseRoleException ex = assertThrows(RuntimeDatabaseRoleException.class,
                        () -> new PostgresRuntimeRoleIdentityValidator(realApp, realMedia, realMod).validateAllPools());
                assertTrue(ex.getMessage().contains("MEMBERSHIPS"));
            } finally {
                execAdminSql("REVOKE pg_read_all_data FROM rewit_mod");
            }

            // D. Divergência real no PostgreSQL: ALTER ROLE rewit_app SET role = 'rewit_mod'
            //    (nova conexão TCP autentica como rewit_app, SELECT 1 funciona, session_user=rewit_app mas current_user=rewit_mod!)
            try {
                execAdminSql("GRANT rewit_mod TO rewit_app");
                execAdminSql("ALTER ROLE rewit_app SET role = 'rewit_mod'");
                try (HikariDataSource hijackedApp = openTcpPool("t-hijack", db.url(), INFRA.appDbUser(), INFRA.appDbPassword())) {
                    assertSelectOneWorks(hijackedApp);
                    RuntimeDatabaseRoleException ex = assertThrows(RuntimeDatabaseRoleException.class,
                            () -> new PostgresRuntimeRoleIdentityValidator(hijackedApp, realMedia, realMod).validateAllPools());
                    assertTrue(ex.getMessage().contains("current_user"));
                    assertTrue(ex.getMessage().contains("rewit_mod"));
                }
            } finally {
                execAdminSql("ALTER ROLE rewit_app RESET role");
                execAdminSql("REVOKE rewit_mod FROM rewit_app");
            }
        }
    }

    @TestConfiguration
    static class ProxyHijackedAppPoolConfig {
        /**
         * Simula um proxy/pooler externo que reescreve a identidade real do pool público para o migrator/owner,
         * mesmo quando todas as propriedades de ambiente declaravam APP_DB_USER=rewit_app.
         */
        @Bean(name = "appDataSource", destroyMethod = "close")
        public HikariDataSource appDataSource(
                @org.springframework.beans.factory.annotation.Value("${spring.datasource.url}") String url
        ) {
            return openTcpPool("hijacked-app-pool", url, INFRA.migrationDbUser(), INFRA.migrationDbPassword());
        }
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

    @Test
    @DisplayName("Startup completo da API falha ANTES do Flyway e ANTES de atender HTTP quando a identidade real do pool diverge")
    void fullSpringStartupAbortsBeforeFlywayAndHttpWhenRealIdentityMismatches() throws Exception {
        DisposableDb db = createDisposableDatabase();
        int apiPort = findFreeLoopbackPort();
        Map<String, Object> props = baseStartupProperties(db);
        props.put("server.port", String.valueOf(apiPort));
        props.put("spring.main.allow-bean-definition-overriding", "true");

        Exception ex = assertThrows(Exception.class,
                () -> startApp(props, ProxyHijackedAppPoolConfig.class),
                "Aplicação deve falhar no startup quando o pool público autentica realmente como migrator");
        Throwable root = rootCause(ex);
        assertTrue(root instanceof RuntimeDatabaseRoleException,
                "Esperava RuntimeDatabaseRoleException, recebeu: " + root);
        assertTrue(root.getMessage().contains("appDataSource"));
        assertTrue(root.getMessage().contains("session_user"));
        assertFalse(isPortAcceptingConnections(apiPort),
                "A porta HTTP da API não pode estar aberta/atendendo tráfego após falha de validação");
        assertFalse(flywayHistoryExists(db),
                "Flyway não deve executar migrations quando a validação real de identidade falha");
        assertEquals(DatabaseRole.APP, DatabaseRoleContext.current(),
                "DatabaseRoleContext não pode ficar contaminado após falha de validação");
    }

    // --------------------------------------------------------------------------------------
    // 2b. Ownership do banco atual e de schemas (F2-DEPLOY-02-R1)
    // --------------------------------------------------------------------------------------

    @Test
    @DisplayName("F2-DEPLOY-02-R1: rejeita startup antes do Flyway e HTTP quando rewit_app é owner do banco atual")
    void rejectsStartupBeforeFlywayAndHttpWhenAppRoleOwnsCurrentDatabase() throws Exception {
        DisposableDb db = createDisposableDatabase();
        int apiPort = findFreeLoopbackPort();
        Map<String, Object> props = baseStartupProperties(db);
        props.put("server.port", String.valueOf(apiPort));

        try {
            execAdminSql("ALTER DATABASE " + db.name() + " OWNER TO rewit_app");
            try (HikariDataSource appPool = openTcpPool("t-dbowner-app", db.url(), INFRA.appDbUser(), INFRA.appDbPassword())) {
                assertSelectOneWorks(appPool);
            }

            Exception ex = assertThrows(Exception.class, () -> startApp(props),
                    "Aplicação deve falhar no startup quando rewit_app é owner do database atual");
            Throwable root = rootCause(ex);
            assertTrue(root instanceof RuntimeDatabaseRoleException,
                    "Esperava RuntimeDatabaseRoleException, recebeu: " + root);
            assertTrue(root.getMessage().contains("appDataSource"));
            assertTrue(root.getMessage().contains("DATABASE_OWNER"));
            assertFalse(root.getMessage().contains(INFRA.appDbPassword()), "Nunca expor APP_DB_PASSWORD");
            assertFalse(isPortAcceptingConnections(apiPort),
                    "A porta HTTP não pode estar aberta após falha por DATABASE_OWNER");
            assertFalse(flywayHistoryExists(db),
                    "Flyway não deve executar migrations quando rewit_app é owner do database");
            assertEquals(DatabaseRole.APP, DatabaseRoleContext.current());
        } finally {
            execAdminSql("ALTER DATABASE " + db.name() + " OWNER TO " + INFRA.migrationDbUser());
        }
    }

    @Test
    @DisplayName("F2-DEPLOY-02-R1: rejeita startup antes do Flyway e HTTP quando rewit_media é owner do schema public")
    void rejectsStartupBeforeFlywayAndHttpWhenMediaRoleOwnsPublicSchema() throws Exception {
        DisposableDb db = createDisposableDatabase();
        int apiPort = findFreeLoopbackPort();
        Map<String, Object> props = baseStartupProperties(db);
        props.put("server.port", String.valueOf(apiPort));

        try {
            execDbAdminSql(db, "ALTER SCHEMA public OWNER TO rewit_media");
            try (HikariDataSource mediaPool = openTcpPool("t-schowner-media", db.url(), INFRA.mediaDbUser(), INFRA.mediaDbPassword())) {
                assertSelectOneWorks(mediaPool);
            }

            Exception ex = assertThrows(Exception.class, () -> startApp(props),
                    "Aplicação deve falhar no startup quando rewit_media é owner do schema public");
            Throwable root = rootCause(ex);
            assertTrue(root instanceof RuntimeDatabaseRoleException,
                    "Esperava RuntimeDatabaseRoleException, recebeu: " + root);
            assertTrue(root.getMessage().contains("mediaLifecycleDataSource"));
            assertTrue(root.getMessage().contains("SCHEMA_OWNER"));
            assertFalse(root.getMessage().contains(INFRA.mediaDbPassword()), "Nunca expor MEDIA_DB_PASSWORD");
            assertFalse(isPortAcceptingConnections(apiPort),
                    "A porta HTTP não pode estar aberta após falha por SCHEMA_OWNER");
            assertFalse(flywayHistoryExists(db),
                    "Flyway não deve executar migrations quando rewit_media é owner de schema");
            assertEquals(DatabaseRole.APP, DatabaseRoleContext.current());
        } finally {
            execDbAdminSql(db, "ALTER SCHEMA public OWNER TO pg_database_owner");
        }
    }

    @Test
    @DisplayName("F2-DEPLOY-02-R1: rejeita startup antes do Flyway e HTTP quando rewit_mod é owner de schema (não-public)")
    void rejectsStartupBeforeFlywayAndHttpWhenModRoleOwnsCustomSchema() throws Exception {
        DisposableDb db = createDisposableDatabase();
        int apiPort = findFreeLoopbackPort();
        Map<String, Object> props = baseStartupProperties(db);
        props.put("server.port", String.valueOf(apiPort));

        try {
            execDbAdminSql(db, "CREATE SCHEMA rewit_mod_owned AUTHORIZATION rewit_mod");
            try (HikariDataSource modPool = openTcpPool("t-schowner-mod", db.url(), INFRA.modDbUser(), INFRA.modDbPassword())) {
                assertSelectOneWorks(modPool);
            }

            Exception ex = assertThrows(Exception.class, () -> startApp(props),
                    "Aplicação deve falhar no startup quando rewit_mod é owner de schema não-public");
            Throwable root = rootCause(ex);
            assertTrue(root instanceof RuntimeDatabaseRoleException,
                    "Esperava RuntimeDatabaseRoleException, recebeu: " + root);
            assertTrue(root.getMessage().contains("moderationDataSource"));
            assertTrue(root.getMessage().contains("SCHEMA_OWNER"));
            assertFalse(root.getMessage().contains(INFRA.modDbPassword()), "Nunca expor MOD_DB_PASSWORD");
            assertFalse(isPortAcceptingConnections(apiPort),
                    "A porta HTTP não pode estar aberta após falha por SCHEMA_OWNER em schema não-public");
            assertFalse(flywayHistoryExists(db),
                    "Flyway não deve executar migrations quando rewit_mod é owner de schema");
            assertEquals(DatabaseRole.APP, DatabaseRoleContext.current());
        } finally {
            execDbAdminSql(db, "DROP SCHEMA IF EXISTS rewit_mod_owned");
        }
    }

    @Test
    @DisplayName("F2-DEPLOY-02-R1: rejeita ownership indevido de schema diferente de public também para rewit_app e rewit_media")
    void rejectsNonPublicSchemaOwnershipForAppAndMediaRoles() throws SQLException {
        DisposableDb db = createDisposableDatabase();

        try (HikariDataSource realApp = openTcpPool("t-app-ns", db.url(), INFRA.appDbUser(), INFRA.appDbPassword());
             HikariDataSource realMedia = openTcpPool("t-media-ns", db.url(), INFRA.mediaDbUser(), INFRA.mediaDbPassword());
             HikariDataSource realMod = openTcpPool("t-mod-ns", db.url(), INFRA.modDbUser(), INFRA.modDbPassword())) {

            // rewit_app como owner de schema não-public
            try {
                execDbAdminSql(db, "CREATE SCHEMA app_custom_schema AUTHORIZATION rewit_app");
                assertSelectOneWorks(realApp);
                RuntimeDatabaseRoleException ex = assertThrows(RuntimeDatabaseRoleException.class,
                        () -> new PostgresRuntimeRoleIdentityValidator(realApp, realMedia, realMod).validateAllPools());
                assertTrue(ex.getMessage().contains("appDataSource"));
                assertTrue(ex.getMessage().contains("SCHEMA_OWNER"));
            } finally {
                execDbAdminSql(db, "DROP SCHEMA IF EXISTS app_custom_schema");
            }

            // rewit_media como owner de schema não-public
            try {
                execDbAdminSql(db, "CREATE SCHEMA media_custom_schema AUTHORIZATION rewit_media");
                assertSelectOneWorks(realMedia);
                RuntimeDatabaseRoleException ex = assertThrows(RuntimeDatabaseRoleException.class,
                        () -> new PostgresRuntimeRoleIdentityValidator(realApp, realMedia, realMod).validateAllPools());
                assertTrue(ex.getMessage().contains("mediaLifecycleDataSource"));
                assertTrue(ex.getMessage().contains("SCHEMA_OWNER"));
            } finally {
                execDbAdminSql(db, "DROP SCHEMA IF EXISTS media_custom_schema");
            }
        }
    }

    @Test
    @DisplayName("F2-DEPLOY-02-R1: rejeita startup fail-closed antes do Flyway e HTTP quando a consulta de ownership falha no PostgreSQL")
    void rejectsStartupFailClosedWhenOwnershipQueryFailsInPostgres() throws Exception {
        DisposableDb db = createDisposableDatabase();
        int apiPort = findFreeLoopbackPort();
        Map<String, Object> props = baseStartupProperties(db);
        props.put("server.port", String.valueOf(apiPort));

        try {
            // Revoga SELECT em pg_catalog.pg_namespace somente neste database descartável:
            // autenticação, SELECT 1 e leitura de pg_roles continuam funcionando, mas a verificação
            // de ownership de schemas falha com 42501 no PostgreSQL.
            execDbAdminSql(db, "REVOKE SELECT ON pg_catalog.pg_namespace FROM PUBLIC");

            try (Connection appConn = DriverManager.getConnection(db.url(), INFRA.appDbUser(), INFRA.appDbPassword())) {
                try (PreparedStatement ps = appConn.prepareStatement("SELECT 1");
                     ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(1, rs.getInt(1));
                }
                try (PreparedStatement ps = appConn.prepareStatement(
                        "SELECT session_user::text, current_user::text FROM pg_catalog.pg_roles WHERE rolname = current_user");
                     ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals("rewit_app", rs.getString(1));
                }
                SQLException nsErr = assertThrows(SQLException.class, () -> {
                    try (PreparedStatement ps = appConn.prepareStatement("SELECT COUNT(*) FROM pg_catalog.pg_namespace")) {
                        ps.executeQuery();
                    }
                });
                assertEquals("42501", nsErr.getSQLState());
            }

            Exception ex = assertThrows(Exception.class, () -> startApp(props),
                    "Aplicação deve falhar fail-closed quando a consulta de ownership em pg_namespace não pode ser executada");
            Throwable root = rootCause(ex);
            assertTrue(root instanceof RuntimeDatabaseRoleException,
                    "Esperava RuntimeDatabaseRoleException, recebeu: " + root);
            assertTrue(root.getMessage().contains("42501"));
            assertFalse(root.getMessage().contains(INFRA.appDbPassword()), "Nunca expor APP_DB_PASSWORD");
            assertFalse(isPortAcceptingConnections(apiPort),
                    "A porta HTTP não pode estar aberta após falha na consulta de ownership");
            assertFalse(flywayHistoryExists(db),
                    "Flyway não deve executar migrations quando a consulta de ownership falha");
            assertEquals(DatabaseRole.APP, DatabaseRoleContext.current());
        } finally {
            execDbAdminSql(db, "GRANT SELECT ON pg_catalog.pg_namespace TO PUBLIC");
        }
    }

    // --------------------------------------------------------------------------------------
    // 3. Funcionamento legítimo: startup completo, Flyway, HTTP, 3 pools e contexto limpo
    // --------------------------------------------------------------------------------------

    @Test
    @DisplayName("Startup completo legítimo valida as três identidades reais, migra via migrator, atende HTTP e preserva roteamento")
    void legitimateStartupValidatesRealIdentitiesMigratesServesHttpAndRoutesCorrectly() throws Exception {
        DisposableDb db = createDisposableDatabase();
        Map<String, Object> props = baseStartupProperties(db);

        ConfigurableApplicationContext ctx = startApp(props);
        assertTrue(ctx.isActive());
        assertTrue(flywayHistoryExists(db), "Flyway deve aplicar migrations usando a credencial migrator dedicada");
        assertEquals(DatabaseRole.APP, DatabaseRoleContext.current(),
                "DatabaseRoleContext deve permanecer limpo (APP) após o startup");

        // Confirma que a API abriu a porta HTTP e o health do Actuator responde 200 UP
        String mgmtPort = ctx.getEnvironment().getProperty("local.management.port");
        HttpResponse<String> health = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + mgmtPort + "/actuator/health")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, health.statusCode());
        assertTrue(health.body().contains("UP"));

        // Confirma identidades reais em cada fluxo transacional sem vazamento de contexto
        JdbcTemplate jdbc = ctx.getBean(JdbcTemplate.class);
        AppTransactionPort appTx = ctx.getBean(AppTransactionPort.class);
        MediaLifecycleTransactionPort mediaTx = ctx.getBean(MediaLifecycleTransactionPort.class);
        ModerationTransactionPort modTx = ctx.getBean(ModerationTransactionPort.class);

        Map<String, Object> appId = appTx.execute(() ->
                jdbc.queryForMap("SELECT session_user::text AS su, current_user::text AS cu"));
        assertEquals("rewit_app", appId.get("su"));
        assertEquals("rewit_app", appId.get("cu"));

        Map<String, Object> mediaId = mediaTx.execute(() ->
                jdbc.queryForMap("SELECT session_user::text AS su, current_user::text AS cu"));
        assertEquals("rewit_media", mediaId.get("su"));
        assertEquals("rewit_media", mediaId.get("cu"));

        Map<String, Object> modId = modTx.execute(() ->
                jdbc.queryForMap("SELECT session_user::text AS su, current_user::text AS cu"));
        assertEquals("rewit_mod", modId.get("su"));
        assertEquals("rewit_mod", modId.get("cu"));

        // Confirma que a criação de tabela temporária (que aloca pg_temp_*) não gera falso positivo
        // na verificação sem exceções de pg_namespace.nspowner
        appTx.run(() -> jdbc.execute("CREATE TEMP TABLE IF NOT EXISTS f2_deploy_02_r1_temp_check (id INT) ON COMMIT DROP"));
        assertDoesNotThrow(() -> ctx.getBean(PostgresRuntimeRoleIdentityValidator.class).validateAllPools());

        assertEquals(DatabaseRole.APP, DatabaseRoleContext.current());
    }
}
