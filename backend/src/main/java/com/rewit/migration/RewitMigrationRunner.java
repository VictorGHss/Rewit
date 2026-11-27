package com.rewit.migration;

import com.rewit.infrastructure.persistence.DatabaseRoleSecurityConstants;
import com.rewit.infrastructure.persistence.PostgresProductionJdbcUrlValidator;
import com.rewit.infrastructure.persistence.PostgresProductionJdbcUrlValidator.PostgresTarget;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.output.MigrateResult;
import org.flywaydb.core.api.output.ValidateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

/**
 * Runner dedicado e isolado para execução pré-deploy das migrations Flyway do Rewit
 * (F2-DEPLOY-03.5.2A).
 *
 * <p>Propriedades de isolamento garantidas por construção:
 * <ul>
 *   <li>Não possui nenhuma dependência do Spring Framework nem inicializa {@code SpringApplication},
 *       Spring Context, Tomcat, Redis, Object Storage ou schedulers.</li>
 *   <li>Reutiliza o mesmo artefato JAR do backend e exatamente o mesmo conjunto de migrations em
 *       {@code classpath:db/migration}, invocado via {@code PropertiesLauncher}:
 *       {@code java -Dloader.main=com.rewit.migration.RewitMigrationRunner -jar /app/app.jar}.</li>
 *   <li>Consome exclusivamente {@code DATABASE_URL}, {@code FLYWAY_DB_USER} e {@code FLYWAY_DB_PASSWORD},
 *       rejeitando variáveis conflitantes, credenciais runtime, placeholders e URLs sem
 *       {@code sslmode=verify-full}.</li>
 *   <li>Valida no PostgreSQL antes da migração que a role conectada não é {@code SUPERUSER},
 *       não possui {@code CREATEROLE}, {@code CREATEDB}, {@code REPLICATION}, {@code BYPASSRLS}
 *       nem memberships privilegiadas, não é role runtime ({@code rewit_app}/{@code rewit_media}/
 *       {@code rewit_mod}) e detém {@code CONNECT} no database e {@code USAGE, CREATE} em {@code public}
 *       (compatível com PostgreSQL gerenciado da F2-DEPLOY-03.5.1, sem exigir ownership de DB/schema).</li>
 *   <li>Executa Flyway com {@code baselineOnMigrate=false}, {@code validateOnMigrate=true},
 *       {@code cleanDisabled=true}, {@code outOfOrder=false}, {@code createSchemas=false} e
 *       {@code flyway.postgresql.transactional.lock=false} (lock de sessão via {@code pg_advisory_lock},
 *       compatível com {@code V16} e serializando execuções concorrentes).</li>
 *   <li>Ao concluir, comprova validação do histórico e executa {@code public.rewit_assert_runtime_privileges()}
 *       sob a identidade do migrator.</li>
 * </ul>
 */
public final class RewitMigrationRunner {

    private static final Logger log = LoggerFactory.getLogger(RewitMigrationRunner.class);

    public static final int EXIT_SUCCESS = 0;
    public static final int EXIT_INVALID_CONFIG = 2;
    public static final int EXIT_PRIVILEGE_VIOLATION = 3;
    public static final int EXIT_MIGRATION_ERROR = 4;

    public static final String ENV_DATABASE_URL = "DATABASE_URL";
    public static final String ENV_FLYWAY_DB_USER = "FLYWAY_DB_USER";
    public static final String ENV_FLYWAY_DB_PASSWORD = "FLYWAY_DB_PASSWORD";

    static final String MIGRATION_LOCATION = "classpath:db/migration";
    static final String SCHEMA_PUBLIC = "public";
    static final String HISTORY_TABLE = "flyway_schema_history";

    private static final Set<String> FORBIDDEN_EXACT_ENV_VARS = Set.of(
            "POSTGRES_USER",
            "POSTGRES_PASSWORD",
            "APP_DB_USER",
            "APP_DB_PASSWORD",
            "MEDIA_DB_USER",
            "MEDIA_DB_PASSWORD",
            "MOD_DB_USER",
            "MOD_DB_PASSWORD",
            "SPRING_DATASOURCE_URL",
            "SPRING_DATASOURCE_USERNAME",
            "SPRING_DATASOURCE_PASSWORD",
            "SPRING_APPLICATION_JSON"
    );

    private static final Set<String> ALLOWED_FLYWAY_ENV_VARS = Set.of(
            ENV_FLYWAY_DB_USER,
            ENV_FLYWAY_DB_PASSWORD
    );

    private static final String IDENTITY_AND_PRIVILEGE_QUERY = """
            SELECT
                session_user::text AS session_user_name,
                current_user::text AS current_user_name,
                current_database()::text AS current_db,
                r.rolsuper,
                r.rolcreaterole,
                r.rolcreatedb,
                r.rolreplication,
                r.rolbypassrls,
                pg_catalog.has_database_privilege(current_user, current_database(), 'CONNECT') AS can_connect,
                pg_catalog.has_schema_privilege(current_user, 'public', 'USAGE') AS can_use_public,
                pg_catalog.has_schema_privilege(current_user, 'public', 'CREATE') AS can_create_public,
                (
                    SELECT COUNT(*)
                    FROM pg_catalog.pg_auth_members am
                    JOIN pg_catalog.pg_roles g ON g.oid = am.roleid
                    WHERE am.member = r.oid
                      AND (g.rolsuper OR g.rolcreaterole OR g.rolcreatedb OR g.rolreplication OR g.rolbypassrls)
                ) AS privileged_memberships
            FROM pg_catalog.pg_roles r
            WHERE r.rolname = current_user
            """;

    enum TlsPolicy {
        STRICT_PRODUCTION,
        LOCAL_TEST_ONLY
    }

    private record ValidatedConfig(
            String databaseUrl,
            PostgresTarget target,
            String migratorUser,
            String migratorPassword
    ) {
    }

    private RewitMigrationRunner() {
    }

    public static void main(String[] args) {
        int exitCode = run(args, System.getenv(), System.out, System.err);
        System.exit(exitCode);
    }

    /**
     * Executa o runner no contrato estrito de produção ({@code sslmode=verify-full} e host não-local).
     * Retorna código de saída determinístico sem chamar {@link System#exit(int)}.
     */
    public static int run(String[] args, Map<String, String> env, PrintStream out, PrintStream err) {
        return executeInternal(args, env, TlsPolicy.STRICT_PRODUCTION, out, err);
    }

    /**
     * Ponto de entrada restrito ao pacote para testes de integração contra PostgreSQL descartável
     * em loopback sem TLS. Jamais acessível por argumentos CLI ou variáveis de ambiente.
     */
    static int runForLocalIntegrationTest(
            String[] args,
            Map<String, String> env,
            PrintStream out,
            PrintStream err
    ) {
        return executeInternal(args, env, TlsPolicy.LOCAL_TEST_ONLY, out, err);
    }

    private static int executeInternal(
            String[] args,
            Map<String, String> env,
            TlsPolicy tlsPolicy,
            PrintStream out,
            PrintStream err
    ) {
        List<String> configErrors = new ArrayList<>();
        ValidatedConfig config = validateConfiguration(args, env, tlsPolicy, configErrors);
        if (!configErrors.isEmpty() || config == null) {
            emitError(err, "Configuração inválida do runner de migrations (fail-closed, exit="
                    + EXIT_INVALID_CONFIG + "):", configErrors);
            return EXIT_INVALID_CONFIG;
        }

        String sanitizedTarget = config.target().sanitizedDisplay();
        emitInfo(out, "Iniciando RewitMigrationRunner (sem Spring Context) contra "
                + sanitizedTarget + " como migrator='" + config.migratorUser() + "'.");

        List<String> privilegeErrors = new ArrayList<>();
        try {
            verifyMigratorIdentityAndPrivileges(config, privilegeErrors);
        } catch (SQLException e) {
            emitError(err, "Falha de conexão/consulta ao verificar identidade do migrator em "
                    + sanitizedTarget + " (SQLState=" + safeSqlState(e) + ", exit="
                    + EXIT_MIGRATION_ERROR + ").", List.of());
            return EXIT_MIGRATION_ERROR;
        }
        if (!privilegeErrors.isEmpty()) {
            emitError(err, "Identidade ou privilégios do migrator recusados (fail-closed, exit="
                    + EXIT_PRIVILEGE_VIOLATION + "):", privilegeErrors);
            return EXIT_PRIVILEGE_VIOLATION;
        }

        try {
            Flyway flyway = buildFlyway(config);
            MigrateResult result = flyway.migrate();
            if (result == null || !result.success) {
                emitError(err, "Flyway migrate não confirmou sucesso (exit=" + EXIT_MIGRATION_ERROR + ").", List.of());
                return EXIT_MIGRATION_ERROR;
            }

            ValidateResult validation = flyway.validateWithResult();
            if (validation == null || !validation.validationSuccessful) {
                emitError(err, "Validação pós-migração do Flyway falhou (exit=" + EXIT_MIGRATION_ERROR + "): "
                        + (validation == null ? "resultado nulo" : sanitizeMessage(validation.getAllErrorMessages(), config)),
                        List.of());
                return EXIT_MIGRATION_ERROR;
            }

            MigrationInfoService info = flyway.info();
            MigrationInfo[] pending = info.pending();
            if (pending != null && pending.length > 0) {
                emitError(err, "Restaram " + pending.length + " migrations pendentes após execução do Flyway (exit="
                        + EXIT_MIGRATION_ERROR + ").", List.of());
                return EXIT_MIGRATION_ERROR;
            }

            for (MigrationInfo mi : info.all()) {
                if (mi.getState() != MigrationState.SUCCESS) {
                    emitError(err, "Estado inesperado no histórico Flyway após migração: versão="
                            + mi.getVersion() + ", estado=" + mi.getState() + " (exit=" + EXIT_MIGRATION_ERROR + ").",
                            List.of());
                    return EXIT_MIGRATION_ERROR;
                }
            }

            assertRuntimePrivileges(config);

            String initialVersion = result.initialSchemaVersion == null ? "vazio" : result.initialSchemaVersion;
            MigrationInfo current = info.current();
            String currentVersion = (current != null && current.getVersion() != null)
                    ? current.getVersion().getVersion()
                    : (result.targetSchemaVersion != null ? result.targetSchemaVersion : initialVersion);
            emitInfo(out, "Migrations Flyway concluídas com sucesso em " + sanitizedTarget
                    + ": flywayVersion=" + result.flywayVersion
                    + ", executadas=" + result.migrationsExecuted
                    + ", versaoInicial=" + initialVersion
                    + ", versaoAtual=" + currentVersion
                    + ", totalAplicadas=" + info.applied().length
                    + ", rewit_assert_runtime_privileges=OK.");
            return EXIT_SUCCESS;
        } catch (Exception e) {
            emitError(err, "Erro durante execução das migrations Flyway em " + sanitizedTarget
                    + " (exit=" + EXIT_MIGRATION_ERROR + "): "
                    + e.getClass().getSimpleName() + ": " + sanitizeMessage(e.getMessage(), config),
                    List.of());
            return EXIT_MIGRATION_ERROR;
        }
    }

    private static ValidatedConfig validateConfiguration(
            String[] args,
            Map<String, String> env,
            TlsPolicy tlsPolicy,
            List<String> errors
    ) {
        if (args != null && args.length > 0) {
            errors.add("Argumentos de linha de comando não são aceitos pelo RewitMigrationRunner (use exclusivamente DATABASE_URL, FLYWAY_DB_USER e FLYWAY_DB_PASSWORD).");
        }

        Map<String, String> safeEnv = env == null ? Map.of() : env;

        // Rejeita variáveis conflitantes, administrativas ambíguas, de runtime ou overrides do Flyway/Spring
        Set<String> forbiddenPresent = new TreeSet<>();
        for (Map.Entry<String, String> entry : safeEnv.entrySet()) {
            String key = entry.getKey();
            String val = entry.getValue();
            if (key == null || val == null || val.isBlank()) {
                continue;
            }
            String upper = key.trim().toUpperCase(Locale.ROOT);
            if (FORBIDDEN_EXACT_ENV_VARS.contains(upper)) {
                forbiddenPresent.add(key);
            } else if (upper.startsWith("SPRING_FLYWAY_")) {
                forbiddenPresent.add(key);
            } else if (upper.startsWith("FLYWAY_") && !ALLOWED_FLYWAY_ENV_VARS.contains(upper)) {
                forbiddenPresent.add(key);
            }
        }
        for (String forbiddenVar : forbiddenPresent) {
            errors.add("Variável proibida ou conflitante no processo do runner de migrations: " + forbiddenVar + ".");
        }

        String rawUrl = trimToNull(safeEnv.get(ENV_DATABASE_URL));
        PostgresTarget target = null;
        if (rawUrl == null) {
            errors.add(ENV_DATABASE_URL + " ausente ou vazia.");
        } else if (tlsPolicy == TlsPolicy.STRICT_PRODUCTION) {
            target = PostgresProductionJdbcUrlValidator.validateProductionUrl(rawUrl, ENV_DATABASE_URL, errors);
        } else {
            target = PostgresProductionJdbcUrlValidator.validateNonProductionUrl(rawUrl, ENV_DATABASE_URL, errors);
        }

        String user = trimToNull(safeEnv.get(ENV_FLYWAY_DB_USER));
        if (user == null) {
            errors.add(ENV_FLYWAY_DB_USER + " ausente ou vazio.");
        } else {
            if (DatabaseRoleSecurityConstants.isRuntimeRole(user)) {
                errors.add(ENV_FLYWAY_DB_USER + " não pode usar role de runtime (rewit_app, rewit_media ou rewit_mod).");
            }
            if ("postgres".equalsIgnoreCase(user)) {
                errors.add(ENV_FLYWAY_DB_USER + " não pode usar a conta administrativa 'postgres' (use role migrator dedicada sem SUPERUSER).");
            }
        }

        String password = safeEnv.get(ENV_FLYWAY_DB_PASSWORD);
        if (password == null || password.isBlank()) {
            errors.add(ENV_FLYWAY_DB_PASSWORD + " ausente ou vazio.");
        } else if (DatabaseRoleSecurityConstants.isForbiddenPassword(password)) {
            errors.add(ENV_FLYWAY_DB_PASSWORD + " usa valor de exemplo/inseguro proibido.");
        }

        if (!errors.isEmpty() || target == null || user == null || password == null) {
            return null;
        }
        return new ValidatedConfig(rawUrl, target, user, password);
    }

    private static void verifyMigratorIdentityAndPrivileges(
            ValidatedConfig config,
            List<String> errors
    ) throws SQLException {
        try (Connection conn = openMigratorConnection(config);
             PreparedStatement ps = conn.prepareStatement(IDENTITY_AND_PRIVILEGE_QUERY);
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                errors.add("Não foi possível consultar a identidade efetiva da role migrator em pg_catalog.pg_roles.");
                return;
            }

            String sessionUser = rs.getString("session_user_name");
            String currentUser = rs.getString("current_user_name");
            boolean rolSuper = rs.getBoolean("rolsuper");
            boolean rolCreateRole = rs.getBoolean("rolcreaterole");
            boolean rolCreateDb = rs.getBoolean("rolcreatedb");
            boolean rolReplication = rs.getBoolean("rolreplication");
            boolean rolBypassRls = rs.getBoolean("rolbypassrls");
            boolean canConnect = rs.getBoolean("can_connect");
            boolean canUsePublic = rs.getBoolean("can_use_public");
            boolean canCreatePublic = rs.getBoolean("can_create_public");
            long privilegedMemberships = rs.getLong("privileged_memberships");

            if (!config.migratorUser().equals(sessionUser)) {
                errors.add("session_user efetivo ('" + sessionUser + "') diverge de "
                        + ENV_FLYWAY_DB_USER + " ('" + config.migratorUser() + "').");
            }
            if (!config.migratorUser().equals(currentUser)) {
                errors.add("current_user efetivo ('" + currentUser + "') diverge de "
                        + ENV_FLYWAY_DB_USER + " ('" + config.migratorUser() + "').");
            }
            if (DatabaseRoleSecurityConstants.isRuntimeRole(sessionUser)
                    || DatabaseRoleSecurityConstants.isRuntimeRole(currentUser)) {
                errors.add("Role de runtime conectada como migrator: execução de DDL proibida.");
            }
            if (rolSuper) {
                errors.add("A role migrator não pode possuir atributo SUPERUSER.");
            }
            if (rolCreateRole) {
                errors.add("A role migrator não pode possuir atributo CREATEROLE.");
            }
            if (rolCreateDb) {
                errors.add("A role migrator não pode possuir atributo CREATEDB.");
            }
            if (rolReplication) {
                errors.add("A role migrator não pode possuir atributo REPLICATION.");
            }
            if (rolBypassRls) {
                errors.add("A role migrator não pode possuir atributo BYPASSRLS.");
            }
            if (privilegedMemberships > 0) {
                errors.add("A role migrator possui membership em role privilegiada (" + privilegedMemberships + ").");
            }
            if (!canConnect) {
                errors.add("A role migrator não possui privilégio CONNECT no database alvo.");
            }
            if (!canUsePublic || !canCreatePublic) {
                errors.add("A role migrator exige privilégios USAGE e CREATE no schema public.");
            }
        }
    }

    private static Flyway buildFlyway(ValidatedConfig config) {
        return Flyway.configure(RewitMigrationRunner.class.getClassLoader())
                .dataSource(config.databaseUrl(), config.migratorUser(), config.migratorPassword())
                .locations(MIGRATION_LOCATION)
                .defaultSchema(SCHEMA_PUBLIC)
                .schemas(SCHEMA_PUBLIC)
                .table(HISTORY_TABLE)
                .baselineOnMigrate(false)
                .validateOnMigrate(true)
                .cleanDisabled(true)
                .outOfOrder(false)
                .createSchemas(false)
                .failOnMissingLocations(true)
                .ignoreMigrationPatterns(new String[0])
                .configuration(Map.of("flyway.postgresql.transactional.lock", "false"))
                .load();
    }

    private static void assertRuntimePrivileges(ValidatedConfig config) throws SQLException {
        try (Connection conn = openMigratorConnection(config);
             Statement st = conn.createStatement()) {
            st.execute("SELECT public.rewit_assert_runtime_privileges()");
        }
    }

    private static Connection openMigratorConnection(ValidatedConfig config) throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", config.migratorUser());
        props.setProperty("password", config.migratorPassword());
        return DriverManager.getConnection(config.databaseUrl(), props);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String safeSqlState(SQLException e) {
        return e.getSQLState() == null ? "desconhecido" : e.getSQLState();
    }

    private static String sanitizeMessage(String raw, ValidatedConfig config) {
        if (raw == null || raw.isBlank()) {
            return "sem detalhe adicional";
        }
        String sanitized = raw;
        if (config != null) {
            if (config.migratorPassword() != null && !config.migratorPassword().isEmpty()) {
                sanitized = sanitized.replace(config.migratorPassword(), "[REDACTED]");
            }
            if (config.databaseUrl() != null && !config.databaseUrl().isEmpty()) {
                sanitized = sanitized.replace(config.databaseUrl(), config.target().sanitizedDisplay());
            }
        }
        return sanitized;
    }

    private static void emitInfo(PrintStream out, String message) {
        log.info(message);
        if (out != null) {
            out.println("[rewit-migration-runner] " + message);
        }
    }

    private static void emitError(PrintStream err, String header, List<String> details) {
        StringBuilder sb = new StringBuilder(header);
        for (String detail : details) {
            sb.append("\n - ").append(detail);
        }
        String full = sb.toString();
        log.error(full);
        if (err != null) {
            err.println("[rewit-migration-runner] " + full);
        }
    }
}
