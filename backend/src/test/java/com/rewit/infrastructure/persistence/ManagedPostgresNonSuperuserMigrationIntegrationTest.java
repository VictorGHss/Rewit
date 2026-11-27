package com.rewit.infrastructure.persistence;

import com.rewit.config.TestInfrastructureConfig;
import com.rewit.infrastructure.persistence.routing.PostgresRuntimeRoleIdentityValidator;
import com.rewit.infrastructure.persistence.routing.RuntimeDatabaseRolePolicy;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Teste automatizado ponta a ponta de F2-DEPLOY-03.5.1: execução completa das migrations
 * Flyway V1–V46 a partir de um database vazio usando exclusivamente um Migrator não-superuser
 * criado por uma conta administrativa não-superuser com {@code CREATEROLE}, sem ownership do
 * database nem do schema {@code public}.
 *
 * <p>Reproduz a topologia real de PostgreSQL gerenciado:
 * <ol>
 *   <li>Bootstrap administrativo (simula o provedor): cria database descartável, pré-instala as
 *       cinco extensões obrigatórias ({@code uuid-ossp}, {@code postgis}, {@code pg_trgm},
 *       {@code pgcrypto}, {@code unaccent}) em {@code public} e cria {@code managed_admin}
 *       ({@code NOSUPERUSER CREATEROLE}).</li>
 *   <li>Conta {@code managed_admin} cria o Migrator não-superuser ({@code NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT}).</li>
 *   <li>O Migrator recebe apenas {@code CONNECT} no database e {@code USAGE, CREATE} em {@code public},
 *       jamais ownership do database nem do schema {@code public}.</li>
 *   <li>Flyway executa V1–V46 conectado exclusivamente como Migrator com {@code transactional-lock=false}.</li>
 *   <li>Valida todas as invariantes pós-migration: histórico V1–V46, {@code rewit_assert_runtime_privileges()},
 *       ownership dos objetos e das 5 funções {@code SECURITY DEFINER}, {@code search_path} fixo,
 *       extensões em {@code public}, {@code public.spatial_ref_sys} somente leitura e
 *       {@link PostgresRuntimeRoleIdentityValidator} real contra os três pools runtime.</li>
 * </ol>
 */
@DisplayName("F2-DEPLOY-03.5.1: Flyway V1–V46 ponta a ponta com Migrator não-superuser (PostgreSQL gerenciado)")
class ManagedPostgresNonSuperuserMigrationIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ManagedPostgresNonSuperuserMigrationIntegrationTest.class);
    private static final TestInfrastructureConfig INFRA = TestInfrastructureConfig.current();
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final String DB_PREFIX = "rewit_mgdmig_";
    private static final Pattern DISPOSABLE_DB_PATTERN = Pattern.compile("^rewit_mgdmig_[0-9a-f]{12}$");
    private static final Pattern DISPOSABLE_ROLE_PATTERN = Pattern.compile("^rewit_mgd(admin|mig)_[0-9a-f]{12}$");

    private static final Set<String> REQUIRED_EXTENSIONS = Set.of(
            "uuid-ossp", "postgis", "pg_trgm", "pgcrypto", "unaccent"
    );

    private static final Set<String> EXPECTED_SECDEF_FUNCTIONS = Set.of(
            "rewit_purge_expired_auth_sessions",
            "rewit_purge_deleted_user_auth_sessions",
            "rewit_auto_resolve_removed_place_media_reports",
            "rewit_release_place_quarantine_entry",
            "rewit_confirm_storage_quarantine_entry"
    );

    private String disposableDbName;
    private String disposableDbMarker;
    private String disposableDbUrl;
    private final List<String> disposableRoles = new ArrayList<>();
    private final List<HikariDataSource> poolsToClose = new ArrayList<>();

    @AfterEach
    void cleanup() throws SQLException {
        for (HikariDataSource pool : poolsToClose) {
            try {
                pool.close();
            } catch (Exception ignored) {
                // Melhor esforço no encerramento de pools de teste
            }
        }
        poolsToClose.clear();

        if (disposableDbName != null) {
            assertTrue(DISPOSABLE_DB_PATTERN.matcher(disposableDbName).matches(),
                    "Database fora do padrão descartável: " + disposableDbName);
            try (Connection admin = openSuperuserConnection(INFRA.datasourceUrl())) {
                try (PreparedStatement ps = admin.prepareStatement(
                        "SELECT shobj_description(oid, 'pg_database'), current_database() FROM pg_database WHERE datname = ?")) {
                    ps.setString(1, disposableDbName);
                    try (ResultSet rs = ps.executeQuery()) {
                        assertTrue(rs.next(), "Database descartável não encontrado: " + disposableDbName);
                        assertEquals(disposableDbMarker, rs.getString(1),
                                "Marcador divergente: DROP recusado para " + disposableDbName);
                        assertNotEquals(disposableDbName, rs.getString(2),
                                "Nunca remover o database administrativo");
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
    @DisplayName("Executa V1–V46 desde database vazio como Migrator não-superuser sem ownership de DB/schema e valida todas as invariantes")
    void migratesV1ToV46FromEmptyDatabaseAsNonSuperuserMigratorAndPreservesAllInvariants() throws SQLException {
        String suffix = randomHex(6);
        disposableDbName = DB_PREFIX + suffix;
        disposableDbMarker = "rewit-managed-pg-nonsuperuser-test:" + UUID.randomUUID();
        disposableDbUrl = INFRA.datasourceUrlForDatabase(disposableDbName);

        String managedAdminRole = "rewit_mgdadmin_" + suffix;
        String managedAdminPassword = "MgdAdmin-" + randomHex(12);
        String migratorRole = "rewit_mgdmig_" + suffix;
        String migratorPassword = "MgdMig-" + randomHex(12);

        // 1. Bootstrap administrativo (simula camada do provedor gerenciado):
        //    cria database, instala as 5 extensões em public, cria managed_admin não-superuser com CREATEROLE
        try (Connection admin = openSuperuserConnection(INFRA.datasourceUrl());
             Statement st = admin.createStatement()) {
            st.execute("CREATE DATABASE " + disposableDbName);
            st.execute("COMMENT ON DATABASE " + disposableDbName + " IS '" + disposableDbMarker + "'");

            st.execute("CREATE ROLE " + managedAdminRole
                    + " WITH LOGIN NOSUPERUSER NOCREATEDB CREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS PASSWORD '"
                    + managedAdminPassword + "'");
            disposableRoles.add(managedAdminRole);
            st.execute("GRANT CONNECT ON DATABASE " + disposableDbName + " TO " + managedAdminRole);
        }

        try (Connection dbAdmin = openSuperuserConnection(disposableDbUrl);
             Statement st = dbAdmin.createStatement()) {
            // As 5 extensões obrigatórias pré-instaladas em public pela conta do provedor
            for (String ext : List.of("uuid-ossp", "postgis", "pg_trgm", "pgcrypto", "unaccent")) {
                st.execute("CREATE EXTENSION IF NOT EXISTS \"" + ext + "\" WITH SCHEMA public");
            }
            st.execute("GRANT USAGE, CREATE ON SCHEMA public TO " + managedAdminRole + " WITH GRANT OPTION");
            // Garante CONNECT para as 3 roles runtime pré-criadas no cluster descartável
            st.execute("GRANT CONNECT ON DATABASE " + disposableDbName + " TO rewit_app, rewit_media, rewit_mod");
        }

        // 2. Conta managed_admin (não-superuser, CREATEROLE) cria o Migrator não-superuser
        try (Connection mgdAdmin = DriverManager.getConnection(disposableDbUrl, managedAdminRole, managedAdminPassword);
             Statement st = mgdAdmin.createStatement()) {
            try (ResultSet rs = st.executeQuery(
                    "SELECT current_user, rolsuper, rolcreaterole, rolcreatedb, rolreplication, rolbypassrls "
                            + "FROM pg_catalog.pg_roles WHERE rolname = current_user")) {
                assertTrue(rs.next());
                assertEquals(managedAdminRole, rs.getString(1));
                assertFalse(rs.getBoolean("rolsuper"), "managed_admin não pode ser SUPERUSER");
                assertTrue(rs.getBoolean("rolcreaterole"), "managed_admin deve possuir CREATEROLE");
                assertFalse(rs.getBoolean("rolcreatedb"));
                assertFalse(rs.getBoolean("rolreplication"));
                assertFalse(rs.getBoolean("rolbypassrls"));
            }

            // Sem cláusulas exclusivas de SUPERUSER (semântica real de PostgreSQL gerenciado)
            st.execute("CREATE ROLE " + migratorRole
                    + " WITH LOGIN NOCREATEDB NOCREATEROLE NOINHERIT PASSWORD '" + migratorPassword + "'");
            disposableRoles.add(migratorRole);
            st.execute("GRANT USAGE, CREATE ON SCHEMA public TO " + migratorRole);
        }

        // Concede CONNECT no database ao Migrator (sem transferir ownership do database nem do schema public)
        try (Connection admin = openSuperuserConnection(INFRA.datasourceUrl());
             Statement st = admin.createStatement()) {
            st.execute("GRANT CONNECT ON DATABASE " + disposableDbName + " TO " + migratorRole);
        }

        // 3. Comprova pré-condições de isolamento do Migrator antes de rodar o Flyway
        try (Connection migConn = DriverManager.getConnection(disposableDbUrl, migratorRole, migratorPassword);
             Statement st = migConn.createStatement();
             ResultSet rs = st.executeQuery("""
                     SELECT
                         session_user::text AS session_user_name,
                         current_user::text AS current_user_name,
                         r.rolsuper,
                         r.rolcreaterole,
                         r.rolcreatedb,
                         r.rolreplication,
                         r.rolbypassrls,
                         r.rolinherit,
                         (d.datdba = r.oid) AS owns_database,
                         (n.nspowner = r.oid) AS owns_public_schema
                     FROM pg_catalog.pg_roles r
                     JOIN pg_catalog.pg_database d ON d.datname = current_database()
                     JOIN pg_catalog.pg_namespace n ON n.nspname = 'public'
                     WHERE r.rolname = current_user
                     """)) {
            assertTrue(rs.next());
            assertEquals(migratorRole, rs.getString("session_user_name"));
            assertEquals(migratorRole, rs.getString("current_user_name"));
            assertFalse(rs.getBoolean("rolsuper"), "Migrator não pode ser SUPERUSER");
            assertFalse(rs.getBoolean("rolcreaterole"), "Migrator não precisa de CREATEROLE");
            assertFalse(rs.getBoolean("rolcreatedb"), "Migrator não precisa de CREATEDB");
            assertFalse(rs.getBoolean("rolreplication"));
            assertFalse(rs.getBoolean("rolbypassrls"));
            assertFalse(rs.getBoolean("rolinherit"));
            assertFalse(rs.getBoolean("owns_database"), "Migrator não deve possuir ownership do database");
            assertFalse(rs.getBoolean("owns_public_schema"), "Migrator não deve possuir ownership do schema public");
        }

        // 4. Executa Flyway V1–V46 conectado exclusivamente como Migrator não-superuser
        //    com transactional-lock=false e instrumentação mínima de pico de conexões simultâneas
        HikariConfig flywayPoolCfg = new HikariConfig();
        flywayPoolCfg.setPoolName("rewit-test-nonsuper-flyway");
        flywayPoolCfg.setJdbcUrl(disposableDbUrl);
        flywayPoolCfg.setUsername(migratorRole);
        flywayPoolCfg.setPassword(migratorPassword);
        flywayPoolCfg.setMaximumPoolSize(4);
        flywayPoolCfg.setMinimumIdle(1);
        HikariDataSource flywayHikari = new HikariDataSource(flywayPoolCfg);
        poolsToClose.add(flywayHikari);

        PeakConnectionTrackingDataSource trackedFlywayDs = new PeakConnectionTrackingDataSource(flywayHikari);

        Flyway flyway = Flyway.configure()
                .dataSource(trackedFlywayDs)
                .locations("classpath:db/migration")
                .baselineOnMigrate(false)
                .validateOnMigrate(true)
                .configuration(Map.of("flyway.postgresql.transactional.lock", "false"))
                .load();

        MigrateResult result = assertDoesNotThrow(flyway::migrate,
                "Flyway V1–V46 deve concluir integralmente sob Migrator não-superuser");

        // 5. Verificações obrigatórias pós-Flyway (Seção 9.2)
        // 5.1 e 5.3: Sucesso e targetSchemaVersion = 46
        assertTrue(result.success, "Flyway MigrateResult deve indicar sucesso");
        assertEquals(46, result.migrationsExecuted, "Todas as 46 migrations (V1–V46) devem ser executadas do zero");
        assertEquals("46", result.targetSchemaVersion, "Versão final do schema deve ser exatamente 46");
        assertEquals(0, flyway.info().pending().length, "Não deve haver migrations pendentes após V46");

        // Diagnóstico estável de conexões simultâneas do Flyway (Seção 9.3)
        int peakFlywayConnections = trackedFlywayDs.peakActiveConnections();
        log.info("F2-DEPLOY-03.5.1 diagnóstico Flyway não-superuser: migrations={}, picoConexoesSimultaneas={}",
                result.migrationsExecuted, peakFlywayConnections);
        assertTrue(peakFlywayConnections >= 1, "Instrumentação do Flyway deve observar ao menos 1 conexão ativa");

        try (Connection migConn = DriverManager.getConnection(disposableDbUrl, migratorRole, migratorPassword);
             Statement st = migConn.createStatement()) {

            // 5.2: flyway_schema_history contém versões 1..46 com sucesso e zero falhas
            Set<Integer> appliedVersions = new TreeSet<>();
            try (ResultSet rs = st.executeQuery(
                    "SELECT version, success FROM public.flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank")) {
                while (rs.next()) {
                    assertTrue(rs.getBoolean("success"), "Migration falhou no histórico: V" + rs.getString("version"));
                    appliedVersions.add(Integer.parseInt(rs.getString("version")));
                }
            }
            assertEquals(46, appliedVersions.size(), "flyway_schema_history deve conter 46 versões distintas");
            for (int v = 1; v <= 46; v++) {
                assertTrue(appliedVersions.contains(v), "Versão ausente em flyway_schema_history: V" + v);
            }

            // 5.4: public.rewit_assert_runtime_privileges() passa integralmente sob o Migrator não-superuser
            assertDoesNotThrow(() -> st.execute("SELECT public.rewit_assert_runtime_privileges()"),
                    "rewit_assert_runtime_privileges() deve passar sem violações");

            // 5.4b (M351-03): Migrator não possui memberships próprias (am.member = migrator_role_oid);
            //                 ADMIN OPTION inverso de managed_admin (am.roleid = migrator_role_oid) é separado e esperado no PG 16+
            try (ResultSet rs = st.executeQuery("""
                    SELECT COUNT(*) AS own_memberships
                    FROM pg_catalog.pg_auth_members am
                    JOIN pg_catalog.pg_roles m ON m.oid = am.member
                    WHERE m.rolname = current_user
                    """)) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt("own_memberships"),
                        "Migrator não deve possuir memberships próprias em pg_auth_members");
            }
            try (PreparedStatement ps = migConn.prepareStatement("""
                    SELECT COUNT(*) AS inverse_admin_option
                    FROM pg_catalog.pg_auth_members am
                    JOIN pg_catalog.pg_roles g ON g.oid = am.roleid
                    JOIN pg_catalog.pg_roles m ON m.oid = am.member
                    WHERE g.rolname = current_user
                      AND m.rolname = ?
                      AND am.admin_option
                    """)) {
                ps.setString(1, managedAdminRole);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(1, rs.getInt("inverse_admin_option"),
                            "Membership inversa (ADMIN OPTION de managed_admin sobre Migrator) é esperada sem indicar privilégio herdado");
                }
            }

            // 5.5 e 5.6: Todas as 5 funções SECURITY DEFINER pertencem ao Migrator e possuem search_path fixo
            Map<String, String> secdefOwners = new LinkedHashMap<>();
            try (ResultSet rs = st.executeQuery("""
                    SELECT p.proname,
                           pg_catalog.pg_get_userbyid(p.proowner) AS owner_name,
                           p.prosecdef,
                           COALESCE(p.proconfig, ARRAY[]::text[]) @> ARRAY['search_path=pg_catalog, public, pg_temp'] AS has_fixed_search_path
                    FROM pg_catalog.pg_proc p
                    JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
                    WHERE n.nspname = 'public'
                      AND p.prosecdef
                      AND NOT EXISTS (
                          SELECT 1 FROM pg_catalog.pg_depend d
                          WHERE d.classid = 'pg_proc'::regclass AND d.objid = p.oid AND d.deptype = 'e'
                      )
                    """)) {
                while (rs.next()) {
                    String fn = rs.getString("proname");
                    secdefOwners.put(fn, rs.getString("owner_name"));
                    assertTrue(rs.getBoolean("prosecdef"), "Função deve ser SECURITY DEFINER: " + fn);
                    assertTrue(rs.getBoolean("has_fixed_search_path"), "Função SECURITY DEFINER sem search_path fixo: " + fn);
                }
            }
            assertEquals(EXPECTED_SECDEF_FUNCTIONS, secdefOwners.keySet(),
                    "Conjunto exato das 5 funções SECURITY DEFINER autorizadas");
            for (Map.Entry<String, String> entry : secdefOwners.entrySet()) {
                assertEquals(migratorRole, entry.getValue(),
                        "Função SECURITY DEFINER " + entry.getKey() + " deve pertencer ao Migrator");
            }

            // 5.6 (complemento): todas as funções de trigger de domínio possuem search_path fixo e pertencem ao Migrator
            try (ResultSet rs = st.executeQuery("""
                    SELECT COUNT(*) AS invalid_trigger_fns
                    FROM pg_catalog.pg_proc p
                    JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
                    WHERE n.nspname = 'public'
                      AND p.prorettype = 'trigger'::regtype
                      AND NOT EXISTS (
                          SELECT 1 FROM pg_catalog.pg_depend d
                          WHERE d.classid = 'pg_proc'::regclass AND d.objid = p.oid AND d.deptype = 'e'
                      )
                      AND (
                          pg_catalog.pg_get_userbyid(p.proowner) <> current_user
                          OR NOT (COALESCE(p.proconfig, ARRAY[]::text[]) @> ARRAY['search_path=pg_catalog, public, pg_temp'])
                      )
                    """)) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt("invalid_trigger_fns"),
                        "Todas as funções de trigger de domínio devem pertencer ao Migrator e fixar search_path");
            }

            // 5.7: Todas as tabelas, sequences, views, materialized views e funções de domínio em public
            //      pertencem ao Migrator (excluindo objetos de extensões PostgreSQL/PostGIS), mantendo DB e schema public sob outro owner
            try (ResultSet rs = st.executeQuery("""
                    SELECT
                        COUNT(*) FILTER (WHERE c.relkind IN ('r', 'p') AND pg_catalog.pg_get_userbyid(c.relowner) = current_user) AS tables_migrator,
                        COUNT(*) FILTER (WHERE c.relkind IN ('r', 'p') AND pg_catalog.pg_get_userbyid(c.relowner) <> current_user) AS tables_other,
                        COUNT(*) FILTER (WHERE c.relkind = 'S' AND pg_catalog.pg_get_userbyid(c.relowner) <> current_user) AS sequences_other,
                        COUNT(*) FILTER (WHERE c.relkind = 'v' AND pg_catalog.pg_get_userbyid(c.relowner) <> current_user) AS views_other,
                        COUNT(*) FILTER (WHERE c.relkind = 'm' AND pg_catalog.pg_get_userbyid(c.relowner) <> current_user) AS matviews_other
                    FROM pg_catalog.pg_class c
                    JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname = 'public'
                      AND c.relkind IN ('r', 'p', 'S', 'v', 'm')
                      AND NOT EXISTS (
                          SELECT 1 FROM pg_catalog.pg_depend d
                          WHERE d.classid = 'pg_class'::regclass AND d.objid = c.oid AND d.deptype = 'e'
                      )
                    """)) {
                assertTrue(rs.next());
                assertTrue(rs.getInt("tables_migrator") >= 25, "Tabelas de domínio devem existir sob ownership do Migrator");
                assertEquals(0, rs.getInt("tables_other"), "Nenhuma tabela de domínio pode ficar sob outro owner");
                assertEquals(0, rs.getInt("sequences_other"), "Nenhuma sequence de domínio pode ficar sob outro owner");
                assertEquals(0, rs.getInt("views_other"), "Nenhuma view de domínio pode ficar sob outro owner");
                assertEquals(0, rs.getInt("matviews_other"), "Nenhuma materialized view de domínio pode ficar sob outro owner");
            }

            try (ResultSet rs = st.executeQuery("""
                    SELECT
                        COUNT(*) FILTER (WHERE pg_catalog.pg_get_userbyid(p.proowner) = current_user) AS domain_fns_migrator,
                        COUNT(*) FILTER (WHERE pg_catalog.pg_get_userbyid(p.proowner) <> current_user) AS domain_fns_other
                    FROM pg_catalog.pg_proc p
                    JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
                    WHERE n.nspname = 'public'
                      AND NOT EXISTS (
                          SELECT 1 FROM pg_catalog.pg_depend d
                          WHERE d.classid = 'pg_proc'::regclass AND d.objid = p.oid AND d.deptype = 'e'
                      )
                    """)) {
                assertTrue(rs.next());
                assertTrue(rs.getInt("domain_fns_migrator") >= 7,
                        "Funções de domínio (rewit_search_normalize, rewit_assert_runtime_privileges, SECURITY DEFINER e triggers) devem pertencer ao Migrator");
                assertEquals(0, rs.getInt("domain_fns_other"),
                        "Nenhuma função de domínio em public pode ficar sob outro owner");
            }

            try (ResultSet rs = st.executeQuery("""
                    SELECT (d.datdba = r.oid) AS owns_db, (n.nspowner = r.oid) AS owns_schema
                    FROM pg_catalog.pg_roles r
                    JOIN pg_catalog.pg_database d ON d.datname = current_database()
                    JOIN pg_catalog.pg_namespace n ON n.nspname = 'public'
                    WHERE r.rolname = current_user
                    """)) {
                assertTrue(rs.next());
                assertFalse(rs.getBoolean("owns_db"), "Database continua sem ownership do Migrator");
                assertFalse(rs.getBoolean("owns_schema"), "Schema public continua sem ownership do Migrator");
            }

            // 5.8: As 5 extensões permanecem instaladas em public e operacionais
            Set<String> installedInPublic = new TreeSet<>();
            try (ResultSet rs = st.executeQuery("""
                    SELECT e.extname
                    FROM pg_catalog.pg_extension e
                    JOIN pg_catalog.pg_namespace n ON n.oid = e.extnamespace
                    WHERE n.nspname = 'public'
                      AND e.extname IN ('uuid-ossp', 'postgis', 'pg_trgm', 'pgcrypto', 'unaccent')
                    """)) {
                while (rs.next()) {
                    installedInPublic.add(rs.getString(1));
                }
            }
            assertEquals(REQUIRED_EXTENSIONS, installedInPublic, "Todas as 5 extensões devem permanecer em public");

            // 5.9: public.spatial_ref_sys com SELECT e sem escrita/DDL para rewit_app, rewit_media e rewit_mod
            for (String runtimeRole : List.of(
                    RuntimeDatabaseRolePolicy.EXPECTED_APP_ROLE,
                    RuntimeDatabaseRolePolicy.EXPECTED_MEDIA_ROLE,
                    RuntimeDatabaseRolePolicy.EXPECTED_MOD_ROLE)) {
                try (PreparedStatement ps = migConn.prepareStatement("""
                        SELECT
                            pg_catalog.has_table_privilege(?, 'public.spatial_ref_sys', 'SELECT') AS can_select,
                            pg_catalog.has_table_privilege(?, 'public.spatial_ref_sys', 'INSERT') AS can_insert,
                            pg_catalog.has_table_privilege(?, 'public.spatial_ref_sys', 'UPDATE') AS can_update,
                            pg_catalog.has_table_privilege(?, 'public.spatial_ref_sys', 'DELETE') AS can_delete,
                            pg_catalog.has_table_privilege(?, 'public.spatial_ref_sys', 'TRUNCATE') AS can_truncate,
                            pg_catalog.has_table_privilege(?, 'public.spatial_ref_sys', 'REFERENCES') AS can_references,
                            pg_catalog.has_table_privilege(?, 'public.spatial_ref_sys', 'TRIGGER') AS can_trigger
                        """)) {
                    for (int idx = 1; idx <= 7; idx++) {
                        ps.setString(idx, runtimeRole);
                    }
                    try (ResultSet rs = ps.executeQuery()) {
                        assertTrue(rs.next());
                        assertTrue(rs.getBoolean("can_select"), runtimeRole + " deve possuir SELECT em public.spatial_ref_sys");
                        assertFalse(rs.getBoolean("can_insert"), runtimeRole + " não pode possuir INSERT em public.spatial_ref_sys");
                        assertFalse(rs.getBoolean("can_update"), runtimeRole + " não pode possuir UPDATE em public.spatial_ref_sys");
                        assertFalse(rs.getBoolean("can_delete"), runtimeRole + " não pode possuir DELETE em public.spatial_ref_sys");
                        assertFalse(rs.getBoolean("can_truncate"), runtimeRole + " não pode possuir TRUNCATE em public.spatial_ref_sys");
                        assertFalse(rs.getBoolean("can_references"), runtimeRole + " não pode possuir REFERENCES em public.spatial_ref_sys");
                        assertFalse(rs.getBoolean("can_trigger"), runtimeRole + " não pode possuir TRIGGER em public.spatial_ref_sys");
                    }
                }
            }

            // 5.10: Nenhuma role runtime possui objetos, database ou schema
            try (ResultSet rs = st.executeQuery("""
                    SELECT COUNT(*) AS owned_by_runtime
                    FROM pg_catalog.pg_shdepend d
                    JOIN pg_catalog.pg_roles r ON r.oid = d.refobjid
                    WHERE d.refclassid = 'pg_authid'::regclass
                      AND d.deptype = 'o'
                      AND r.rolname IN ('rewit_app', 'rewit_media', 'rewit_mod')
                    """)) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt("owned_by_runtime"), "Roles runtime nunca podem possuir objetos");
            }
        }

        // 5.11 e 5.12: Validação real do PostgresRuntimeRoleIdentityValidator contra os 3 pools físicos
        //              + comprovação de session_user = current_user e uso real de unaccent/PostGIS/pg_trgm em runtime
        HikariDataSource appPool = createRuntimePool("mgd-app-pool", disposableDbUrl,
                INFRA.appDbUser(), INFRA.appDbPassword());
        HikariDataSource mediaPool = createRuntimePool("mgd-media-pool", disposableDbUrl,
                INFRA.mediaDbUser(), INFRA.mediaDbPassword());
        HikariDataSource modPool = createRuntimePool("mgd-mod-pool", disposableDbUrl,
                INFRA.modDbUser(), INFRA.modDbPassword());

        PostgresRuntimeRoleIdentityValidator identityValidator =
                new PostgresRuntimeRoleIdentityValidator(appPool, mediaPool, modPool);
        assertDoesNotThrow(identityValidator::validateAllPools,
                "PostgresRuntimeRoleIdentityValidator deve aprovar os três pools runtime pós-migração");

        assertSessionMatchesCurrentUserAndReadsSpatialRefSys(appPool, RuntimeDatabaseRolePolicy.EXPECTED_APP_ROLE);
        assertSessionMatchesCurrentUserAndReadsSpatialRefSys(mediaPool, RuntimeDatabaseRolePolicy.EXPECTED_MEDIA_ROLE);
        assertSessionMatchesCurrentUserAndReadsSpatialRefSys(modPool, RuntimeDatabaseRolePolicy.EXPECTED_MOD_ROLE);

        // Smoke funcional em runtime (rewit_app): unaccent (V22), pg_trgm (similarity) e PostGIS (ST_DWithin)
        try (Connection appConn = appPool.getConnection();
             Statement st = appConn.createStatement();
             ResultSet rs = st.executeQuery("""
                     SELECT
                         public.rewit_search_normalize('Açaí São Paulo') AS normalized,
                         similarity('cafe', 'cafe') AS trgm_sim,
                         ST_DWithin(
                             ST_SetSRID(ST_MakePoint(-46.6333, -23.5505), 4326)::geography,
                             ST_SetSRID(ST_MakePoint(-46.6333, -23.5505), 4326)::geography,
                             50
                         ) AS within_radius
                     """)) {
            assertTrue(rs.next());
            assertEquals("acai sao paulo", rs.getString("normalized"));
            assertTrue(rs.getDouble("trgm_sim") > 0.9);
            assertTrue(rs.getBoolean("within_radius"));
        }
    }

    private HikariDataSource createRuntimePool(String name, String jdbcUrl, String user, String password) {
        HikariConfig cfg = new HikariConfig();
        cfg.setPoolName(name);
        cfg.setJdbcUrl(jdbcUrl);
        cfg.setUsername(user);
        cfg.setPassword(password);
        cfg.setMaximumPoolSize(2);
        cfg.setMinimumIdle(1);
        HikariDataSource ds = new HikariDataSource(cfg);
        poolsToClose.add(ds);
        return ds;
    }

    private static void assertSessionMatchesCurrentUserAndReadsSpatialRefSys(DataSource ds, String expectedRole)
            throws SQLException {
        try (Connection conn = ds.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("""
                     SELECT
                         session_user::text AS session_u,
                         current_user::text AS current_u,
                         (SELECT COUNT(*) FROM public.spatial_ref_sys WHERE srid = 4326) AS srid_4326
                     """)) {
            assertTrue(rs.next());
            assertEquals(expectedRole, rs.getString("session_u"), "session_user deve coincidir com " + expectedRole);
            assertEquals(expectedRole, rs.getString("current_u"), "current_user deve coincidir com " + expectedRole);
            assertEquals(1, rs.getInt("srid_4326"),
                    expectedRole + " deve conseguir consultar public.spatial_ref_sys (SRID 4326) com identidade real");
        }
    }

    private static Connection openSuperuserConnection(String url) throws SQLException {
        return DriverManager.getConnection(url, INFRA.migrationDbUser(), INFRA.migrationDbPassword());
    }

    private static String randomHex(int bytes) {
        byte[] buf = new byte[bytes];
        RANDOM.nextBytes(buf);
        return HexFormat.of().formatHex(buf);
    }

    /**
     * Instrumentação mínima de diagnóstico para contabilizar o pico de conexões simultâneas
     * abertas pelo Flyway sem interferir no comportamento do pool nem tornar o teste frágil.
     */
    private static final class PeakConnectionTrackingDataSource extends DelegatingDataSource {
        private final AtomicInteger active = new AtomicInteger();
        private final AtomicInteger peak = new AtomicInteger();

        private PeakConnectionTrackingDataSource(DataSource target) {
            super(target);
        }

        @Override
        public Connection getConnection() throws SQLException {
            return wrap(super.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return wrap(super.getConnection(username, password));
        }

        int peakActiveConnections() {
            return peak.get();
        }

        private Connection wrap(Connection delegate) {
            int current = active.incrementAndGet();
            peak.accumulateAndGet(current, Math::max);
            AtomicInteger closed = new AtomicInteger(0);
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        if ("close".equals(method.getName()) && closed.compareAndSet(0, 1)) {
                            active.decrementAndGet();
                        }
                        try {
                            return method.invoke(delegate, args);
                        } catch (java.lang.reflect.InvocationTargetException e) {
                            throw e.getTargetException();
                        }
                    }
            );
        }
    }
}
