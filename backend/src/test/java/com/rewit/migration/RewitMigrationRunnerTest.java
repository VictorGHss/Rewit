package com.rewit.migration;

import com.rewit.config.TestInfrastructureConfig;
import com.rewit.infrastructure.persistence.DatabaseRoleSecurityConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testes unitários, arquiteturais e de integração real contra PostgreSQL 18 descartável
 * do {@link RewitMigrationRunner} (F2-DEPLOY-03.5.2A).
 */
@DisplayName("F2-DEPLOY-03.5.2A: RewitMigrationRunner isolado (zero Spring Context + PostgreSQL real)")
class RewitMigrationRunnerTest {

    private static final TestInfrastructureConfig INFRA = TestInfrastructureConfig.current();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String DB_PREFIX = "rewit_migrun_";
    private static final Pattern DISPOSABLE_DB_PATTERN = Pattern.compile("^rewit_migrun_[0-9a-f]{12}$");
    private static final Pattern DISPOSABLE_ROLE_PATTERN = Pattern.compile("^rewit_mr(admin|mig|ro|mid1|mid2|priv|grp)_[0-9a-f]{12}$");

    private static final String PROD_URL = "jdbc:postgresql://db.rewit.internal:5432/rewit_prod?sslmode=verify-full";
    private static final String SYNTHETIC_MIG_CREDENTIAL = generateEphemeralCredential();

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

    // --------------------------------------------------------------------------------------
    // 1. Isolamento arquitetural: zero imports do Spring Framework no pacote com.rewit.migration
    // --------------------------------------------------------------------------------------

    @Test
    @DisplayName("Pacote com.rewit.migration e validadores compartilhados possuem zero imports de org.springframework.*")
    void migrationRunnerAndSharedValidatorsHaveZeroSpringDependencies() throws IOException {
        List<Path> filesToCheck = new ArrayList<>();
        try (Stream<Path> migrationSources = Files.walk(Path.of("src/main/java/com/rewit/migration"))) {
            migrationSources.filter(p -> p.toString().endsWith(".java")).forEach(filesToCheck::add);
        }
        filesToCheck.add(Path.of("src/main/java/com/rewit/infrastructure/persistence/PostgresProductionJdbcUrlValidator.java"));
        filesToCheck.add(Path.of("src/main/java/com/rewit/infrastructure/persistence/DatabaseRoleSecurityConstants.java"));

        assertFalse(filesToCheck.isEmpty());
        for (Path source : filesToCheck) {
            String content = Files.readString(source, StandardCharsets.UTF_8);
            assertFalse(content.contains("import org.springframework."),
                    "Dependência proibida de org.springframework.* encontrada em " + source);
            assertFalse(content.contains("SpringApplication.run"),
                    "Chamada proibida a SpringApplication.run encontrada em " + source);
            if (source.getFileName().toString().equals("RewitMigrationRunner.java")) {
                assertFalse(content.contains(".repair("),
                        "RewitMigrationRunner jamais pode executar flyway.repair() automaticamente");
                assertFalse(content.contains(".baseline("),
                        "RewitMigrationRunner jamais pode executar flyway.baseline() automaticamente");
                assertFalse(content.contains(".clean("),
                        "RewitMigrationRunner jamais pode executar flyway.clean() automaticamente");
            }
        }
    }

    // --------------------------------------------------------------------------------------
    // 2. Validação fail-closed de configuração (contrato estrito de produção, exit=2)
    // --------------------------------------------------------------------------------------

    @Test
    @DisplayName("Rejeita variáveis obrigatórias ausentes, argumentos CLI e senhas placeholder sem vazar valores")
    void rejectsMissingVarsCliArgsAndPlaceholderPasswords() {
        // Ambiente vazio
        RunOutput empty = runStrict(new String[0], Map.of());
        assertEquals(RewitMigrationRunner.EXIT_INVALID_CONFIG, empty.exitCode);
        assertTrue(empty.stderr.contains(RewitMigrationRunner.ENV_DATABASE_URL));
        assertTrue(empty.stderr.contains(RewitMigrationRunner.ENV_FLYWAY_DB_USER));
        assertTrue(empty.stderr.contains(RewitMigrationRunner.ENV_FLYWAY_DB_PASSWORD));

        // Argumentos CLI inesperados
        RunOutput withArgs = runStrict(new String[]{"--spring.flyway.enabled=true"}, validProdEnv());
        assertEquals(RewitMigrationRunner.EXIT_INVALID_CONFIG, withArgs.exitCode);
        assertTrue(withArgs.stderr.contains("Argumentos de linha de comando"));
        assertFalse(withArgs.stderr.contains(SYNTHETIC_MIG_CREDENTIAL));

        // Senhas vazias e placeholders proibidos (lidos da constante canônica sem duplicar literais)
        List<String> rejectedInputs = new ArrayList<>();
        rejectedInputs.add("");
        rejectedInputs.add("   ");
        rejectedInputs.addAll(DatabaseRoleSecurityConstants.FORBIDDEN_PASSWORDS);
        for (String rejectedInput : rejectedInputs) {
            Map<String, String> env = validProdEnv();
            env.put(RewitMigrationRunner.ENV_FLYWAY_DB_PASSWORD, rejectedInput);
            RunOutput res = runStrict(new String[0], env);
            assertEquals(RewitMigrationRunner.EXIT_INVALID_CONFIG, res.exitCode,
                    "Deve rejeitar credencial insegura");
            assertTrue(res.stderr.contains(RewitMigrationRunner.ENV_FLYWAY_DB_PASSWORD));
            if (!rejectedInput.isBlank()) {
                assertFalse(res.stderr.contains(rejectedInput));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"rewit_app", "rewit_media", "rewit_mod", "REWit_App", "postgres", "POSTGRES"})
    @DisplayName("Rejeita FLYWAY_DB_USER sendo role runtime ou conta postgres")
    void rejectsRuntimeRolesAndPostgresUser(String forbiddenUser) {
        Map<String, String> env = validProdEnv();
        env.put(RewitMigrationRunner.ENV_FLYWAY_DB_USER, forbiddenUser);
        RunOutput res = runStrict(new String[0], env);
        assertEquals(RewitMigrationRunner.EXIT_INVALID_CONFIG, res.exitCode);
        assertTrue(res.stderr.contains(RewitMigrationRunner.ENV_FLYWAY_DB_USER));
        assertFalse(res.stderr.contains(SYNTHETIC_MIG_CREDENTIAL));
    }

    @Test
    @DisplayName("Rejeita variáveis proibidas ou conflitantes no processo do runner")
    void rejectsConflictingOrRuntimeEnvVars() {
        List<String> forbiddenVars = List.of(
                com.rewit.infrastructure.persistence.routing.RuntimeDatabaseRolePolicy.MIGRATOR_POSTGRES_USER_ENV,
                com.rewit.infrastructure.persistence.routing.RuntimeDatabaseRolePolicy.MIGRATOR_POSTGRES_PASSWORD_ENV,
                "APP_DB_USER",
                "APP_DB_PASSWORD",
                "MEDIA_DB_USER",
                "MEDIA_DB_PASSWORD",
                "MOD_DB_USER",
                "MOD_DB_PASSWORD",
                "SPRING_FLYWAY_URL",
                "SPRING_FLYWAY_BASELINE_ON_MIGRATE",
                "FLYWAY_CLEAN_DISABLED",
                "SPRING_APPLICATION_JSON"
        );
        for (String forbiddenVar : forbiddenVars) {
            Map<String, String> env = validProdEnv();
            String ephemeralValue = generateEphemeralCredential();
            env.put(forbiddenVar, ephemeralValue);

            RunOutput res = runStrict(new String[0], env);
            assertEquals(RewitMigrationRunner.EXIT_INVALID_CONFIG, res.exitCode,
                    "Deve rejeitar variável proibida: " + forbiddenVar);
            assertTrue(res.stderr.contains(forbiddenVar));
            assertFalse(res.stderr.contains(ephemeralValue), "Nunca vazar valor de variável proibida");
            assertFalse(res.stderr.contains(SYNTHETIC_MIG_CREDENTIAL), "Nunca vazar FLYWAY_DB_PASSWORD");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "jdbc:postgresql://db.rewit.internal:5432/rewit_prod",
            "jdbc:postgresql://db.rewit.internal:5432/rewit_prod?sslmode=disable",
            "jdbc:postgresql://db.rewit.internal:5432/rewit_prod?sslmode=require",
            "jdbc:postgresql://db.rewit.internal:5432/rewit_prod?sslmode=verify-ca",
            "jdbc:postgresql://db.rewit.internal:5432/rewit_prod?sslMode=verify-full",
            "jdbc:postgresql://db.rewit.internal:5432/rewit_prod?sslmode=verify-full&ssl=false",
            "jdbc:postgresql://db.rewit.internal:5432/rewit_prod?sslmode=verify-full&sslfactory=org.postgresql.ssl.NonValidatingFactory",
            "jdbc:postgresql://db.rewit.internal:5432/rewit_prod?sslmode=verify-full&sslhostnameverifier=com.example.Noop",
            "jdbc:postgresql://localhost:5432/rewit_prod?sslmode=verify-full",
            "jdbc:postgresql://127.0.0.1:5432/rewit_prod?sslmode=verify-full",
            "jdbc:postgresql://[::1]:5432/rewit_prod?sslmode=verify-full"
    })
    @DisplayName("Rejeita URLs JDBC inseguras, sem sslmode=verify-full ou locais")
    void rejectsInsecureProductionDatabaseUrls(String badUrl) {
        Map<String, String> env = validProdEnv();
        env.put(RewitMigrationRunner.ENV_DATABASE_URL, badUrl);

        RunOutput res = runStrict(new String[0], env);
        assertEquals(RewitMigrationRunner.EXIT_INVALID_CONFIG, res.exitCode,
                "URL deve ser rejeitada: " + badUrl);
        assertTrue(res.stderr.contains(RewitMigrationRunner.ENV_DATABASE_URL));
        assertFalse(res.stderr.contains(SYNTHETIC_MIG_CREDENTIAL));
    }

    @Test
    @DisplayName("Rejeita URL JDBC com userinfo na authority ou parâmetros user/password na query")
    void rejectsDatabaseUrlWithEmbeddedUserInfo() {
        String ephemeralToken = generateEphemeralCredential();
        List<String> urlsWithEmbeddedCreds = List.of(
                "jdbc:postgresql://" + "mig" + ":" + ephemeralToken
                        + "@db.rewit.internal:5432/rewit_prod?sslmode=verify-full",
                "jdbc:postgresql://db.rewit.internal:5432/rewit_prod?sslmode=verify-full&"
                        + "user=mig&" + "password=" + ephemeralToken
        );
        for (String urlWithCreds : urlsWithEmbeddedCreds) {
            Map<String, String> env = validProdEnv();
            env.put(RewitMigrationRunner.ENV_DATABASE_URL, urlWithCreds);

            RunOutput res = runStrict(new String[0], env);
            assertEquals(RewitMigrationRunner.EXIT_INVALID_CONFIG, res.exitCode);
            assertTrue(res.stderr.contains(RewitMigrationRunner.ENV_DATABASE_URL));
            assertFalse(res.stderr.contains(ephemeralToken));
            assertFalse(res.stderr.contains(SYNTHETIC_MIG_CREDENTIAL));
        }
    }

    // --------------------------------------------------------------------------------------
    // 3. Testes de integração reais contra PostgreSQL 18 descartável (topologia 03.5.1)
    // --------------------------------------------------------------------------------------

    @Test
    @DisplayName("PostgreSQL real: rejeita SUPERUSER, CREATEROLE e role sem CREATE em public antes de criar histórico")
    void rejectsSuperuserCreateRoleAndInsufficientPrivilegesInRealPostgres() throws SQLException {
        String suffix = randomHex(6);
        provisionManagedDatabase(suffix);

        String managedAdminRole = "rewit_mradmin_" + suffix;
        String managedAdminCredential = generateEphemeralCredential();
        String readOnlyRole = "rewit_mrro_" + suffix;
        String readOnlyCredential = generateEphemeralCredential();

        try (Connection admin = openSuperuserConnection(INFRA.datasourceUrl());
             Statement st = admin.createStatement()) {
            st.execute("CREATE ROLE " + managedAdminRole
                    + " WITH LOGIN NOSUPERUSER NOCREATEDB CREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS PASSWORD '"
                    + managedAdminCredential + "'");
            disposableRoles.add(managedAdminRole);
            st.execute("GRANT CONNECT ON DATABASE " + disposableDbName + " TO " + managedAdminRole);

            st.execute("CREATE ROLE " + readOnlyRole
                    + " WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS PASSWORD '"
                    + readOnlyCredential + "'");
            disposableRoles.add(readOnlyRole);
            st.execute("GRANT CONNECT ON DATABASE " + disposableDbName + " TO " + readOnlyRole);
        }
        try (Connection dbAdmin = openSuperuserConnection(disposableDbUrl);
             Statement st = dbAdmin.createStatement()) {
            st.execute("GRANT USAGE, CREATE ON SCHEMA public TO " + managedAdminRole + " WITH GRANT OPTION");
            st.execute("GRANT USAGE ON SCHEMA public TO " + readOnlyRole);
        }

        // A. SUPERUSER (INFRA.migrationDbUser() no cluster descartável) -> rejeitado com EXIT_PRIVILEGE_VIOLATION (3)
        RunOutput superRes = runLocalIntegration(Map.of(
                RewitMigrationRunner.ENV_DATABASE_URL, disposableDbUrl,
                RewitMigrationRunner.ENV_FLYWAY_DB_USER, INFRA.migrationDbUser(),
                RewitMigrationRunner.ENV_FLYWAY_DB_PASSWORD, INFRA.migrationDbPassword()
        ));
        assertEquals(RewitMigrationRunner.EXIT_PRIVILEGE_VIOLATION, superRes.exitCode);
        assertTrue(superRes.stderr.contains("SUPERUSER"));
        assertFalse(superRes.stderr.contains(INFRA.migrationDbPassword()));
        assertFalse(historyTableExists(), "SUPERUSER não pode criar flyway_schema_history");

        // B. Conta administrativa com CREATEROLE (managed_admin) -> rejeitada com EXIT_PRIVILEGE_VIOLATION (3)
        RunOutput adminRes = runLocalIntegration(Map.of(
                RewitMigrationRunner.ENV_DATABASE_URL, disposableDbUrl,
                RewitMigrationRunner.ENV_FLYWAY_DB_USER, managedAdminRole,
                RewitMigrationRunner.ENV_FLYWAY_DB_PASSWORD, managedAdminCredential
        ));
        assertEquals(RewitMigrationRunner.EXIT_PRIVILEGE_VIOLATION, adminRes.exitCode);
        assertTrue(adminRes.stderr.contains("CREATEROLE"));
        assertFalse(adminRes.stderr.contains(managedAdminCredential));
        assertFalse(historyTableExists(), "Role com CREATEROLE não pode criar flyway_schema_history");

        // C. Role sem CREATE em public -> rejeitada com EXIT_PRIVILEGE_VIOLATION (3)
        RunOutput roRes = runLocalIntegration(Map.of(
                RewitMigrationRunner.ENV_DATABASE_URL, disposableDbUrl,
                RewitMigrationRunner.ENV_FLYWAY_DB_USER, readOnlyRole,
                RewitMigrationRunner.ENV_FLYWAY_DB_PASSWORD, readOnlyCredential
        ));
        assertEquals(RewitMigrationRunner.EXIT_PRIVILEGE_VIOLATION, roRes.exitCode);
        assertTrue(roRes.stderr.contains("USAGE e CREATE"));
        assertFalse(roRes.stderr.contains(readOnlyCredential));
        assertFalse(historyTableExists(), "Role sem CREATE em public não pode criar flyway_schema_history");
    }

    @Test
    @DisplayName("PostgreSQL real: migra V1–V46 do zero com Migrator não-superuser (03.5.1), valida privilégios e é idempotente")
    void migratesV1ToV46WithNonSuperuserMigratorAndIsIdempotent() throws SQLException {
        String suffix = randomHex(6);
        provisionManagedDatabase(suffix);

        String managedAdminRole = "rewit_mradmin_" + suffix;
        String managedAdminCredential = generateEphemeralCredential();
        String migratorRole = "rewit_mrmig_" + suffix;
        String migratorCredential = generateEphemeralCredential();

        try (Connection admin = openSuperuserConnection(INFRA.datasourceUrl());
             Statement st = admin.createStatement()) {
            st.execute("CREATE ROLE " + managedAdminRole
                    + " WITH LOGIN NOSUPERUSER NOCREATEDB CREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS PASSWORD '"
                    + managedAdminCredential + "'");
            disposableRoles.add(managedAdminRole);
            st.execute("GRANT CONNECT ON DATABASE " + disposableDbName + " TO " + managedAdminRole);
        }
        try (Connection dbAdmin = openSuperuserConnection(disposableDbUrl);
             Statement st = dbAdmin.createStatement()) {
            st.execute("GRANT USAGE, CREATE ON SCHEMA public TO " + managedAdminRole + " WITH GRANT OPTION");
        }
        // managed_admin (não-superuser) cria o migrator sem SUPERUSER nem CREATEROLE
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

        Map<String, String> env = Map.of(
                RewitMigrationRunner.ENV_DATABASE_URL, disposableDbUrl,
                RewitMigrationRunner.ENV_FLYWAY_DB_USER, migratorRole,
                RewitMigrationRunner.ENV_FLYWAY_DB_PASSWORD, migratorCredential
        );

        // 1. Primeira execução em banco vazio -> V1..V46 aplicadas + rewit_assert_runtime_privileges() OK
        RunOutput firstRun = runLocalIntegration(env);
        assertEquals(RewitMigrationRunner.EXIT_SUCCESS, firstRun.exitCode,
                "Primeira execução deveria retornar 0; stderr=" + firstRun.stderr);
        assertTrue(firstRun.stdout.contains("executadas=46"));
        assertTrue(firstRun.stdout.contains("versaoAtual=46"));
        assertTrue(firstRun.stdout.contains("rewit_assert_runtime_privileges=OK"));
        assertFalse(firstRun.stdout.contains(migratorCredential), "stdout nunca pode expor senha");
        assertFalse(firstRun.stderr.contains(migratorCredential), "stderr nunca pode expor senha");

        // Confirma V1..V46 no histórico real
        Set<Integer> versions = new TreeSet<>();
        try (Connection conn = DriverManager.getConnection(disposableDbUrl, migratorRole, migratorCredential);
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

        // 2. Segunda execução (reexecução idempotente) -> exit=0, executadas=0, versaoAtual=46
        RunOutput secondRun = runLocalIntegration(env);
        assertEquals(RewitMigrationRunner.EXIT_SUCCESS, secondRun.exitCode,
                "Reexecução idempotente deveria retornar 0; stderr=" + secondRun.stderr);
        assertTrue(secondRun.stdout.contains("executadas=0"));
        assertTrue(secondRun.stdout.contains("versaoAtual=46"));
        assertTrue(secondRun.stdout.contains("rewit_assert_runtime_privileges=OK"));
    }

    @Test
    @DisplayName("PostgreSQL real (R1-A): detecta memberships diretas, transitivas (2 e 3 saltos), roles predefinidas pg_* e cadeias INHERIT/SET/ADMIN sem falso positivo")
    void detectsDirectTransitivePredefinedAndInheritSetMembershipsInRealPostgres() throws SQLException {
        String suffix = randomHex(6);
        provisionManagedDatabase(suffix);

        String managedAdminRole = "rewit_mradmin_" + suffix;
        String managedAdminCredential = generateEphemeralCredential();
        String migratorRole = "rewit_mrmig_" + suffix;
        String migratorCredential = generateEphemeralCredential();
        String mid1Role = "rewit_mrmid1_" + suffix;
        String mid2Role = "rewit_mrmid2_" + suffix;
        String privRole = "rewit_mrpriv_" + suffix;
        String benignGroupRole = "rewit_mrgrp_" + suffix;

        try (Connection admin = openSuperuserConnection(INFRA.datasourceUrl());
             Statement st = admin.createStatement()) {
            st.execute("CREATE ROLE " + managedAdminRole
                    + " WITH LOGIN NOSUPERUSER NOCREATEDB CREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS PASSWORD '"
                    + managedAdminCredential + "'");
            disposableRoles.add(managedAdminRole);
            st.execute("GRANT CONNECT ON DATABASE " + disposableDbName + " TO " + managedAdminRole);

            // Roles auxiliares de cadeia (intermediárias não privilegiadas e role alvo privilegiada)
            st.execute("CREATE ROLE " + mid1Role + " WITH NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT");
            disposableRoles.add(mid1Role);
            st.execute("CREATE ROLE " + mid2Role + " WITH NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT");
            disposableRoles.add(mid2Role);
            st.execute("CREATE ROLE " + privRole + " WITH NOLOGIN NOSUPERUSER NOCREATEDB CREATEROLE NOINHERIT");
            disposableRoles.add(privRole);
            st.execute("CREATE ROLE " + benignGroupRole + " WITH NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT");
            disposableRoles.add(benignGroupRole);
        }
        try (Connection dbAdmin = openSuperuserConnection(disposableDbUrl);
             Statement st = dbAdmin.createStatement()) {
            st.execute("GRANT USAGE, CREATE ON SCHEMA public TO " + managedAdminRole + " WITH GRANT OPTION");
        }
        // Cria o migrator via managed_admin (gera ADMIN OPTION inverso PG 16+: managed_admin -> migrator)
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

        Map<String, String> env = Map.of(
                RewitMigrationRunner.ENV_DATABASE_URL, disposableDbUrl,
                RewitMigrationRunner.ENV_FLYWAY_DB_USER, migratorRole,
                RewitMigrationRunner.ENV_FLYWAY_DB_PASSWORD, migratorCredential
        );

        try (Connection admin = openSuperuserConnection(INFRA.datasourceUrl());
             Statement st = admin.createStatement()) {

            // 1. Membership direta (1 salto) em role privilegiada (CREATEROLE) -> rejeitada
            st.execute("GRANT " + privRole + " TO " + migratorRole);
            RunOutput directRes = runLocalIntegration(env);
            assertEquals(RewitMigrationRunner.EXIT_PRIVILEGE_VIOLATION, directRes.exitCode);
            assertTrue(directRes.stderr.contains(privRole));
            assertFalse(historyTableExists(), "Membership direta proibida não pode criar histórico");
            st.execute("REVOKE " + privRole + " FROM " + migratorRole);

            // 2. Membership indireta com 2 saltos (migrator -> mid1 -> privRole) -> rejeitada
            st.execute("GRANT " + privRole + " TO " + mid1Role);
            st.execute("GRANT " + mid1Role + " TO " + migratorRole);
            RunOutput twoHopsRes = runLocalIntegration(env);
            assertEquals(RewitMigrationRunner.EXIT_PRIVILEGE_VIOLATION, twoHopsRes.exitCode);
            assertTrue(twoHopsRes.stderr.contains(privRole));
            assertFalse(historyTableExists(), "Membership transitiva de 2 saltos não pode criar histórico");
            st.execute("REVOKE " + mid1Role + " FROM " + migratorRole);
            st.execute("REVOKE " + privRole + " FROM " + mid1Role);

            // 3. Membership indireta com 3 saltos (migrator -> mid1 -> mid2 -> privRole) -> rejeitada
            st.execute("GRANT " + privRole + " TO " + mid2Role);
            st.execute("GRANT " + mid2Role + " TO " + mid1Role);
            st.execute("GRANT " + mid1Role + " TO " + migratorRole);
            RunOutput threeHopsRes = runLocalIntegration(env);
            assertEquals(RewitMigrationRunner.EXIT_PRIVILEGE_VIOLATION, threeHopsRes.exitCode);
            assertTrue(threeHopsRes.stderr.contains(privRole));
            assertFalse(historyTableExists(), "Membership transitiva de 3 saltos não pode criar histórico");
            st.execute("REVOKE " + mid1Role + " FROM " + migratorRole);
            st.execute("REVOKE " + mid2Role + " FROM " + mid1Role);
            st.execute("REVOKE " + privRole + " FROM " + mid2Role);

            // 4. Roles predefinidas perigosas (direta e via 2 saltos) -> rejeitadas
            for (String dangerousRole : List.of(
                    "pg_execute_server_program",
                    "pg_read_server_files",
                    "pg_write_server_files",
                    "pg_read_all_data",
                    "pg_write_all_data",
                    "pg_maintain"
            )) {
                st.execute("GRANT " + dangerousRole + " TO " + mid1Role);
                st.execute("GRANT " + mid1Role + " TO " + migratorRole);
                RunOutput predefRes = runLocalIntegration(env);
                assertEquals(RewitMigrationRunner.EXIT_PRIVILEGE_VIOLATION, predefRes.exitCode,
                        "Membership transitiva em " + dangerousRole + " deve ser rejeitada");
                assertTrue(predefRes.stderr.contains(dangerousRole),
                        "Erro deve citar " + dangerousRole + ": " + predefRes.stderr);
                assertFalse(historyTableExists());
                st.execute("REVOKE " + mid1Role + " FROM " + migratorRole);
                st.execute("REVOKE " + dangerousRole + " FROM " + mid1Role);
            }

            // 5. Cadeias com semântica PG 16+: INHERIT FALSE / SET TRUE, INHERIT TRUE / SET FALSE e ADMIN TRUE
            for (String grantOptions : List.of(
                    "WITH INHERIT FALSE, SET TRUE",
                    "WITH INHERIT TRUE, SET FALSE",
                    "WITH INHERIT FALSE, SET FALSE, ADMIN TRUE"
            )) {
                st.execute("GRANT pg_read_server_files TO " + mid1Role + " " + grantOptions);
                st.execute("GRANT " + mid1Role + " TO " + migratorRole + " " + grantOptions);
                RunOutput optRes = runLocalIntegration(env);
                assertEquals(RewitMigrationRunner.EXIT_PRIVILEGE_VIOLATION, optRes.exitCode,
                        "Cadeia com '" + grantOptions + "' deve ser rejeitada");
                assertTrue(optRes.stderr.contains("pg_read_server_files"));
                assertFalse(historyTableExists());
                st.execute("REVOKE " + mid1Role + " FROM " + migratorRole);
                st.execute("REVOKE pg_read_server_files FROM " + mid1Role);
            }

            // 6. Ausência de falso positivo: migrator limpo (inclusive com ADMIN OPTION inverso de managed_admin
            //    e sem memberships privilegiadas, mesmo pertencendo a grupo comum não privilegiado) é aprovado
            st.execute("GRANT " + benignGroupRole + " TO " + migratorRole);
            RunOutput cleanRes = runLocalIntegration(env);
            assertEquals(RewitMigrationRunner.EXIT_SUCCESS, cleanRes.exitCode,
                    "Migrator legítimo (03.5.1) sem memberships privilegiadas deve ser aprovado; stderr=" + cleanRes.stderr);
            assertTrue(cleanRes.stdout.contains("executadas=46"));
            assertTrue(cleanRes.stdout.contains("rewit_assert_runtime_privileges=OK"));
            st.execute("REVOKE " + benignGroupRole + " FROM " + migratorRole);
        }
    }

    private void provisionManagedDatabase(String suffix) throws SQLException {
        disposableDbName = DB_PREFIX + suffix;
        disposableDbMarker = "rewit-migration-runner-test:" + UUID.randomUUID();
        disposableDbUrl = INFRA.datasourceUrlForDatabase(disposableDbName);

        try (Connection admin = openSuperuserConnection(INFRA.datasourceUrl());
             Statement st = admin.createStatement()) {
            st.execute("CREATE DATABASE " + disposableDbName);
            st.execute("COMMENT ON DATABASE " + disposableDbName + " IS '" + disposableDbMarker + "'");
        }
        try (Connection dbAdmin = openSuperuserConnection(disposableDbUrl);
             Statement st = dbAdmin.createStatement()) {
            for (String ext : List.of("uuid-ossp", "postgis", "pg_trgm", "pgcrypto", "unaccent")) {
                st.execute("CREATE EXTENSION IF NOT EXISTS \"" + ext + "\" WITH SCHEMA public");
            }
            st.execute("GRANT CONNECT ON DATABASE " + disposableDbName + " TO rewit_app, rewit_media, rewit_mod");
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

    private static Map<String, String> validProdEnv() {
        Map<String, String> env = new HashMap<>();
        env.put(RewitMigrationRunner.ENV_DATABASE_URL, PROD_URL);
        env.put(RewitMigrationRunner.ENV_FLYWAY_DB_USER, "rewit_migrator");
        env.put(RewitMigrationRunner.ENV_FLYWAY_DB_PASSWORD, SYNTHETIC_MIG_CREDENTIAL);
        return env;
    }

    private static String generateEphemeralCredential() {
        return randomHex(16);
    }

    private record RunOutput(int exitCode, String stdout, String stderr) {
    }

    private static RunOutput runStrict(String[] args, Map<String, String> env) {
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        int code = RewitMigrationRunner.run(
                args, env,
                new PrintStream(outBuf, true, StandardCharsets.UTF_8),
                new PrintStream(errBuf, true, StandardCharsets.UTF_8));
        return new RunOutput(code, outBuf.toString(StandardCharsets.UTF_8), errBuf.toString(StandardCharsets.UTF_8));
    }

    private static RunOutput runLocalIntegration(Map<String, String> env) {
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        int code = RewitMigrationRunner.runForLocalIntegrationTest(
                new String[0], env,
                new PrintStream(outBuf, true, StandardCharsets.UTF_8),
                new PrintStream(errBuf, true, StandardCharsets.UTF_8));
        return new RunOutput(code, outBuf.toString(StandardCharsets.UTF_8), errBuf.toString(StandardCharsets.UTF_8));
    }

    private static Connection openSuperuserConnection(String url) throws SQLException {
        return DriverManager.getConnection(url, INFRA.migrationDbUser(), INFRA.migrationDbPassword());
    }

    private static String randomHex(int bytes) {
        byte[] buf = new byte[bytes];
        RANDOM.nextBytes(buf);
        return HexFormat.of().formatHex(buf);
    }
}
