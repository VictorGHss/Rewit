package com.rewit.infrastructure.persistence.schema;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.output.ValidateOutput;
import org.flywaydb.core.api.output.ValidateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Validador fail-closed e estritamente somente leitura do histórico de migrations Flyway na
 * inicialização da API (F2-DEPLOY-03.5.2A).
 *
 * <p>Garantias de segurança e isolamento:
 * <ul>
 *   <li>Utiliza exclusivamente {@code appDataSource} ({@code rewit_app}), nunca credenciais
 *       migrator nem o {@code ModerationRoutingDataSource}.</li>
 *   <li>Força {@link Connection#setReadOnly(boolean) setReadOnly(true)} em todas as conexões
 *       emprestadas ao Flyway e bloqueia qualquer tentativa de {@code setReadOnly(false)},
 *       impedindo escrita ou DDL no nível de sessão/transação PostgreSQL.</li>
 *   <li>Verifica previamente via {@code SELECT to_regclass('public.flyway_schema_history')} se a
 *       tabela de histórico existe e possui registros antes mesmo de acionar o Flyway.</li>
 *   <li>Executa somente leitura ({@link Flyway#validateWithResult()} e {@link Flyway#info()}),
 *       jamais {@code migrate()}, {@code repair()}, {@code baseline()}, {@code clean()} ou
 *       {@code rewit_assert_runtime_privileges()}.</li>
 *   <li>Recusa o startup diante de banco vazio, migrations pendentes, migrations com falha,
 *       checksum/descrição/tipo divergentes, migrations faltantes ou versões futuras/inesperadas.</li>
 * </ul>
 */
public class ReadOnlyFlywaySchemaValidator {

    private static final Logger log = LoggerFactory.getLogger(ReadOnlyFlywaySchemaValidator.class);

    static final String MIGRATION_LOCATION = "classpath:db/migration";
    static final String SCHEMA_PUBLIC = "public";
    static final String HISTORY_TABLE = "flyway_schema_history";

    private final DataSource appDataSource;
    private final String locations;

    public ReadOnlyFlywaySchemaValidator(DataSource appDataSource) {
        this(appDataSource, MIGRATION_LOCATION);
    }

    ReadOnlyFlywaySchemaValidator(DataSource appDataSource, String locations) {
        this.appDataSource = Objects.requireNonNull(appDataSource, "appDataSource obrigatório");
        this.locations = Objects.requireNonNull(locations, "locations obrigatório");
    }

    /**
     * Executa a validação somente leitura completa do estado do schema Flyway.
     * Lança {@link SchemaValidationException} em qualquer divergência ou falha de leitura.
     */
    public void validate() {
        ReadOnlyEnforcingDataSource readOnlyDs = new ReadOnlyEnforcingDataSource(appDataSource);
        List<String> errors = new ArrayList<>();

        // 1. Verifica de forma estritamente somente leitura se public.flyway_schema_history existe e tem registros,
        //    antes de invocar qualquer operação do Flyway (evita qualquer tentativa de bootstrap em banco vazio).
        try (Connection conn = readOnlyDs.getConnection()) {
            boolean historyExists;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT to_regclass('public.flyway_schema_history') IS NOT NULL");
                 ResultSet rs = ps.executeQuery()) {
                historyExists = rs.next() && rs.getBoolean(1);
            }
            if (!historyExists) {
                throw new SchemaValidationException(List.of(
                        "Tabela public.flyway_schema_history ausente: o banco está vazio ou as migrations pré-deploy não foram executadas."));
            }

            long historyRows;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM public.flyway_schema_history");
                 ResultSet rs = ps.executeQuery()) {
                historyRows = rs.next() ? rs.getLong(1) : 0L;
            }
            if (historyRows == 0L) {
                throw new SchemaValidationException(List.of(
                        "Tabela public.flyway_schema_history está vazia: nenhuma migration aplicada no banco."));
            }
        } catch (SchemaValidationException e) {
            throw e;
        } catch (SQLException e) {
            throw new SchemaValidationException(List.of(
                    "Falha ao consultar public.flyway_schema_history em modo somente leitura (SQLState="
                            + (e.getSQLState() == null ? "desconhecido" : e.getSQLState()) + ")."), e);
        }

        // 2. Valida checksums, integridade e alinhamento completo entre classpath:db/migration e flyway_schema_history
        //    usando exclusivamente o DataSource somente leitura (rewit_app).
        try {
            Flyway flyway = Flyway.configure(ReadOnlyFlywaySchemaValidator.class.getClassLoader())
                    .dataSource(readOnlyDs)
                    .locations(locations)
                    .defaultSchema(SCHEMA_PUBLIC)
                    .schemas(SCHEMA_PUBLIC)
                    .table(HISTORY_TABLE)
                    .createSchemas(false)
                    .baselineOnMigrate(false)
                    .outOfOrder(false)
                    .cleanDisabled(true)
                    .failOnMissingLocations(true)
                    .ignoreMigrationPatterns(new String[0])
                    .load();

            ValidateResult validateResult = flyway.validateWithResult();
            if (validateResult == null) {
                errors.add("Flyway validateWithResult retornou resultado nulo.");
            } else if (!validateResult.validationSuccessful) {
                if (validateResult.invalidMigrations != null && !validateResult.invalidMigrations.isEmpty()) {
                    for (ValidateOutput invalid : validateResult.invalidMigrations) {
                        String versionLabel = invalid.version == null ? "sem-versao" : "V" + invalid.version;
                        String detail = invalid.errorDetails != null && invalid.errorDetails.errorMessage != null
                                ? invalid.errorDetails.errorMessage
                                : "inconsistência detectada";
                        errors.add("Falha de validação Flyway em " + versionLabel + ": " + detail);
                    }
                } else if (validateResult.errorDetails != null && validateResult.errorDetails.errorMessage != null) {
                    errors.add("Falha de validação Flyway: " + validateResult.errorDetails.errorMessage);
                } else {
                    errors.add("Validação do histórico Flyway falhou sem detalhes adicionais.");
                }
            }

            MigrationInfoService info = flyway.info();
            MigrationInfo[] all = info.all();
            MigrationInfo[] applied = info.applied();
            MigrationInfo[] pending = info.pending();

            if (all == null || all.length == 0) {
                errors.add("Nenhuma migration encontrada no histórico ou no classpath.");
            }
            if (applied == null || applied.length == 0) {
                errors.add("Nenhuma migration aplicada encontrada em public.flyway_schema_history.");
            }
            if (pending != null && pending.length > 0) {
                List<String> pendingVersions = new ArrayList<>();
                for (MigrationInfo mi : pending) {
                    pendingVersions.add(mi.getVersion() == null ? mi.getDescription() : "V" + mi.getVersion());
                }
                errors.add("Existem " + pending.length + " migrations pendentes no banco ("
                        + String.join(", ", pendingVersions) + "): execute o runner pré-deploy.");
            }

            if (all != null) {
                for (MigrationInfo mi : all) {
                    MigrationState state = mi.getState();
                    String versionLabel = mi.getVersion() == null ? mi.getDescription() : "V" + mi.getVersion();
                    if (state == MigrationState.PENDING) {
                        // Já registrado acima com lista consolidada de pendentes
                        continue;
                    }
                    if (state != MigrationState.SUCCESS) {
                        errors.add("Migration " + versionLabel + " em estado inválido no histórico: " + state + ".");
                    } else {
                        if (!mi.isChecksumMatching()) {
                            errors.add("Checksum divergente detectado na migration " + versionLabel + ".");
                        }
                        if (!mi.isDescriptionMatching()) {
                            errors.add("Descrição divergente detectada na migration " + versionLabel + ".");
                        }
                        if (!mi.isTypeMatching()) {
                            errors.add("Tipo divergente detectado na migration " + versionLabel + ".");
                        }
                    }
                }
            }

            if (!errors.isEmpty()) {
                throw new SchemaValidationException(errors);
            }

            MigrationInfo current = info.current();
            String currentVersion = current != null && current.getVersion() != null
                    ? current.getVersion().getVersion()
                    : "desconhecida";
            log.info("Validação somente leitura do schema Flyway aprovada (rewit_app, readOnly=true): "
                    + "versaoAtual={}, migrationsAplicadas={}, pendentes=0.",
                    currentVersion, applied.length);
        } catch (SchemaValidationException e) {
            throw e;
        } catch (Exception e) {
            throw new SchemaValidationException(List.of(
                    "Erro ao executar validação somente leitura do histórico Flyway: "
                            + e.getClass().getSimpleName() + ": " + safeMessage(e.getMessage())), e);
        }
    }

    private static String safeMessage(String message) {
        return (message == null || message.isBlank()) ? "sem detalhe adicional" : message;
    }

    /**
     * Decorador de {@link DataSource} que impõe {@code readOnly=true} em toda conexão aberta
     * (garantindo {@code SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY} no PostgreSQL)
     * e recusa qualquer tentativa de reativar escrita via {@code setReadOnly(false)}, restaurando
     * o estado original apenas ao devolver a conexão física ao pool Hikari no {@code close()}.
     */
    static final class ReadOnlyEnforcingDataSource extends DelegatingDataSource {

        ReadOnlyEnforcingDataSource(DataSource targetDataSource) {
            super(targetDataSource);
        }

        @Override
        public Connection getConnection() throws SQLException {
            return enforceReadOnly(super.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return enforceReadOnly(super.getConnection(username, password));
        }

        private static Connection enforceReadOnly(Connection delegate) throws SQLException {
            boolean originalReadOnly = delegate.isReadOnly();
            try {
                if (!originalReadOnly) {
                    delegate.setReadOnly(true);
                }
                // Impõe READ ONLY diretamente no servidor PostgreSQL (independente do readOnlyMode do pgjdbc)
                try (java.sql.Statement st = delegate.createStatement()) {
                    st.execute("SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY");
                }
            } catch (SQLException e) {
                try {
                    delegate.close();
                } catch (SQLException suppressed) {
                    e.addSuppressed(suppressed);
                }
                throw e;
            }

            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        String name = method.getName();
                        if ("setReadOnly".equals(name) && args != null && args.length == 1
                                && Boolean.FALSE.equals(args[0])) {
                            throw new SQLException(
                                    "Operação proibida: conexão reservada à validação somente leitura do schema Flyway.",
                                    "25006");
                        }
                        if ("close".equals(name)) {
                            SQLException restoreError = null;
                            try {
                                if (!delegate.isClosed() && !originalReadOnly) {
                                    if (!delegate.getAutoCommit()) {
                                        delegate.rollback();
                                    }
                                    try (java.sql.Statement st = delegate.createStatement()) {
                                        st.execute("SET SESSION CHARACTERISTICS AS TRANSACTION READ WRITE");
                                    }
                                    delegate.setReadOnly(false);
                                }
                            } catch (SQLException e) {
                                restoreError = e;
                            } finally {
                                try {
                                    delegate.close();
                                } catch (SQLException closeEx) {
                                    if (restoreError != null) {
                                        restoreError.addSuppressed(closeEx);
                                    } else {
                                        restoreError = closeEx;
                                    }
                                }
                            }
                            if (restoreError != null) {
                                throw restoreError;
                            }
                            return null;
                        }
                        try {
                            return method.invoke(delegate, args);
                        } catch (InvocationTargetException e) {
                            throw e.getTargetException();
                        }
                    }
            );
        }
    }
}
