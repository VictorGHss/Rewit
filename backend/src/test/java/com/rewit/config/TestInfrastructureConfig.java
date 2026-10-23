package com.rewit.config;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Configuração explícita da infraestrutura descartável de teste (A-1b).
 *
 * <p>Fonte única de endpoints e credenciais para os testes que se conectam fora do contexto Spring
 * (JDBC direto, Flyway programático, Lettuce e MinIO). Lê exclusivamente as variáveis {@code TEST_*}
 * abaixo, como propriedade de sistema ou variável de ambiente com o mesmo nome. Não há valores padrão
 * nem fallback para variáveis de desenvolvimento ({@code POSTGRES_*}, {@code REDIS_*},
 * {@code OBJECT_STORAGE_*}): a ausência de um valor obrigatório falha com mensagem explícita antes de
 * qualquer conexão, e todo endpoint passa pelo {@link TestInfrastructureSafetyValidator}.
 */
public final class TestInfrastructureConfig {

    public static final String DATASOURCE_URL = "TEST_DATASOURCE_URL";
    public static final String APP_DB_USER = "TEST_APP_DB_USER";
    public static final String APP_DB_PASSWORD = "TEST_APP_DB_PASSWORD";
    public static final String MIGRATION_DB_USER = "TEST_MIGRATION_DB_USER";
    public static final String MIGRATION_DB_PASSWORD = "TEST_MIGRATION_DB_PASSWORD";
    public static final String REDIS_HOST = "TEST_REDIS_HOST";
    public static final String REDIS_PORT = "TEST_REDIS_PORT";
    public static final String STORAGE_ENDPOINT = "TEST_STORAGE_ENDPOINT";
    public static final String STORAGE_ACCESS_KEY = "TEST_STORAGE_ACCESS_KEY";
    public static final String STORAGE_SECRET_KEY = "TEST_STORAGE_SECRET_KEY";
    public static final String STORAGE_BUCKET = "TEST_STORAGE_BUCKET";

    /** Variáveis obrigatórias da suíte backend (documentadas em docs/development/local-setup.md). */
    public static final List<String> REQUIRED_VARIABLES = List.of(
            DATASOURCE_URL, APP_DB_USER, APP_DB_PASSWORD, MIGRATION_DB_USER, MIGRATION_DB_PASSWORD,
            REDIS_HOST, REDIS_PORT,
            STORAGE_ENDPOINT, STORAGE_ACCESS_KEY, STORAGE_SECRET_KEY, STORAGE_BUCKET);

    private static final Pattern DATABASE_NAME = Pattern.compile("^[a-z_][a-z0-9_]{0,62}$");

    private static final Function<String, String> SYSTEM_LOOKUP = name -> {
        String value = System.getProperty(name);
        return value != null && !value.isBlank() ? value : System.getenv(name);
    };

    private final Function<String, String> lookup;

    private TestInfrastructureConfig(Function<String, String> lookup) {
        this.lookup = Objects.requireNonNull(lookup);
    }

    /** Configuração do processo atual (propriedades de sistema e ambiente). */
    public static TestInfrastructureConfig current() {
        return new TestInfrastructureConfig(SYSTEM_LOOKUP);
    }

    /** Configuração a partir de valores fixos, para testes do próprio mecanismo. */
    public static TestInfrastructureConfig of(Map<String, String> values) {
        return new TestInfrastructureConfig(values::get);
    }

    public String require(String name) {
        String value = lookup.apply(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Configuração de teste obrigatória ausente: " + name
                    + ". A suíte usa somente infraestrutura descartável declarada por variáveis TEST_* "
                    + "(ver docs/development/local-setup.md); não há valor padrão.");
        }
        return value.trim();
    }

    public String datasourceUrl() {
        String url = require(DATASOURCE_URL);
        TestInfrastructureSafetyValidator.validateJdbcUrl(url, DATASOURCE_URL);
        return url;
    }

    public String appDbUser() {
        return require(APP_DB_USER);
    }

    public String appDbPassword() {
        return require(APP_DB_PASSWORD);
    }

    public String migrationDbUser() {
        return require(MIGRATION_DB_USER);
    }

    public String migrationDbPassword() {
        return require(MIGRATION_DB_PASSWORD);
    }

    public String redisHost() {
        return require(REDIS_HOST);
    }

    public int redisPort() {
        String raw = require(REDIS_PORT);
        int port;
        try {
            port = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new IllegalStateException(REDIS_PORT + " inválida: " + raw, e);
        }
        if (port <= 0 || port > 65535) {
            throw new IllegalStateException(REDIS_PORT + " fora do intervalo válido: " + raw);
        }
        if (port == TestInfrastructureSafetyValidator.FORBIDDEN_REDIS_PORT) {
            throw new IllegalStateException("SEGURANÇA DE INFRAESTRUTURA DE TESTE VIOLADA: " + REDIS_PORT
                    + " aponta para a porta compartilhada " + port);
        }
        return port;
    }

    public String storageEndpoint() {
        String endpoint = require(STORAGE_ENDPOINT);
        TestInfrastructureSafetyValidator.validateStorageEndpoint(endpoint, STORAGE_ENDPOINT);
        return endpoint;
    }

    public String storageAccessKey() {
        return require(STORAGE_ACCESS_KEY);
    }

    public String storageSecretKey() {
        return require(STORAGE_SECRET_KEY);
    }

    public String storageBucket() {
        return require(STORAGE_BUCKET);
    }

    /**
     * URL JDBC de outro database no MESMO servidor descartável de {@code TEST_DATASOURCE_URL}
     * (mesmo host e porta, já validados). Rejeita URLs com vários hosts e nomes fora do padrão.
     */
    public String datasourceUrlForDatabase(String database) {
        if (database == null || !DATABASE_NAME.matcher(database).matches()) {
            throw new IllegalArgumentException("Nome de database de teste inválido: " + database);
        }
        String url = datasourceUrl();
        Properties parsed = org.postgresql.Driver.parseURL(url, new Properties());
        if (parsed == null) {
            throw new IllegalStateException(DATASOURCE_URL + " não é uma URL JDBC PostgreSQL válida");
        }
        String host = parsed.getProperty("PGHOST");
        String port = parsed.getProperty("PGPORT");
        if (host == null || port == null || host.contains(",") || port.contains(",")) {
            throw new IllegalStateException(DATASOURCE_URL + " precisa apontar para um único host:porta descartável");
        }
        String derived = "jdbc:postgresql://" + host + ":" + port + "/" + database;
        TestInfrastructureSafetyValidator.validateJdbcUrl(derived, DATASOURCE_URL + " (derivada)");
        return derived;
    }

    /** Host e porta efetivos de {@code TEST_DATASOURCE_URL}, para diagnóstico. */
    public String datasourceAuthority() {
        URI uri = URI.create(datasourceUrl().substring("jdbc:".length()));
        return uri.getHost() + ":" + uri.getPort();
    }
}
