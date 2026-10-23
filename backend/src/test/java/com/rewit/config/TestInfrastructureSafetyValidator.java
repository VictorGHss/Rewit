package com.rewit.config;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;

import java.net.URI;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validador de integridade e isolamento de infraestrutura de teste (A-1, A-1a, A-1b, A-1c).
 * Executado na inicialização de qualquer contexto Spring de teste.
 * Rejeita categoricamente conexões acidentais aos endpoints compartilhados/desenvolvimento
 * (PostgreSQL portas 5432/5433, Redis porta 6379, SeaweedFS porta 8333) e valida
 * que as propriedades de teste sejam explícitas, parseadas com precisão pelo driver e isoladas.
 */
public class TestInfrastructureSafetyValidator implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    public static final Set<Integer> FORBIDDEN_POSTGRES_PORTS = Set.of(5432, 5433);
    public static final int FORBIDDEN_REDIS_PORT = 6379;
    public static final int FORBIDDEN_STORAGE_PORT = 8333;

    private static final Pattern STRICT_POSTGRES_URL_PATTERN = Pattern.compile("^jdbc:postgresql://([^/?#]+)(?:/.*)?$");

    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        ConfigurableEnvironment env = applicationContext.getEnvironment();
        validateEnvironment(env);
    }

    public static void validateEnvironment(ConfigurableEnvironment env) {
        validateDatasourceProperty(resolve(env, "spring.datasource.url"), "spring.datasource.url");
        validateDatasourceProperty(resolve(env, "spring.datasource.hikari.jdbc-url"), "spring.datasource.hikari.jdbc-url");
        validateDatasourceProperty(resolve(env, "spring.datasource.jdbc-url"), "spring.datasource.jdbc-url");

        validateRedisProperties(env);
        String storageEndpoint = resolve(env, "rewit.minio.endpoint");
        if (storageEndpoint != null && !storageEndpoint.isBlank()) {
            validateStorageEndpoint(storageEndpoint, "rewit.minio.endpoint");
        }
    }

    /**
     * Resolve a propriedade convertendo placeholder sem valor (variável TEST_* ausente, sem default
     * no YAML de teste) em falha explícita antes que qualquer bean tente conectar.
     */
    private static String resolve(ConfigurableEnvironment env, String property) {
        try {
            return env.getProperty(property);
        } catch (IllegalArgumentException unresolved) {
            throw new IllegalStateException("Configuração de teste obrigatória ausente para " + property
                    + ": " + unresolved.getMessage()
                    + ". Defina as variáveis TEST_* da infraestrutura descartável (docs/development/local-setup.md).",
                    unresolved);
        }
    }

    /** Valida uma URL JDBC de teste avulsa (testes sem contexto Spring). Exige URL PostgreSQL não vazia. */
    public static void validateJdbcUrl(String url, String name) {
        if (url == null || url.isBlank() || !url.trim().startsWith("jdbc:postgresql:")) {
            throw new IllegalStateException("SEGURANÇA DE INFRAESTRUTURA DE TESTE VIOLADA: " + name
                    + " precisa ser uma URL jdbc:postgresql://host:porta/database explícita");
        }
        validateDatasourceProperty(url, name);
    }

    private static void validateDatasourceProperty(String datasourceUrl, String propertyName) {
        if (datasourceUrl == null || datasourceUrl.isBlank()) {
            return;
        }

        String trimmedUrl = datasourceUrl.trim();

        // 1. Verificação prévia de substrings com portas proibidas
        for (int forbiddenPort : FORBIDDEN_POSTGRES_PORTS) {
            if (trimmedUrl.contains(":" + forbiddenPort + "/") ||
                    trimmedUrl.contains(":" + forbiddenPort + "?") ||
                    trimmedUrl.endsWith(":" + forbiddenPort)) {
                throw new IllegalStateException(
                        "SEGURANÇA DE INFRAESTRUTURA DE TESTE VIOLADA: " + propertyName +
                                " aponta para porta proibida (" + forbiddenPort + "): " + datasourceUrl
                );
            }
        }

        // 2. Análise estrita via driver PostgreSQL oficial (A-1c)
        if (trimmedUrl.startsWith("jdbc:postgresql:")) {
            Properties driverProps = org.postgresql.Driver.parseURL(trimmedUrl, new Properties());
            if (driverProps == null) {
                throw new IllegalStateException(
                        "SEGURANÇA DE INFRAESTRUTURA DE TESTE VIOLADA: " + propertyName +
                                " contém URL JDBC PostgreSQL com formato inválido ou não suportado: " + datasourceUrl
                );
            }

            // O driver PostgreSQL assume localhost:5432 quando host e/ou porta são omitidos
            // (ex: jdbc:postgresql:rewit_test, jdbc:postgresql:/rewit_test, jdbc:postgresql:///rewit_test, jdbc:postgresql://localhost/rewit_test)
            String portsStr = driverProps.getProperty("PGPORT");
            if (portsStr != null && !portsStr.isBlank()) {
                String[] parsedPorts = portsStr.split(",");
                for (String p : parsedPorts) {
                    try {
                        int parsedPort = Integer.parseInt(p.trim());
                        if (FORBIDDEN_POSTGRES_PORTS.contains(parsedPort)) {
                            throw new IllegalStateException(
                                    "SEGURANÇA DE INFRAESTRUTURA DE TESTE VIOLADA: " + propertyName +
                                            " não possui porta explícita ou aponta para porta proibida (" + parsedPort + ") (driver PostgreSQL conectaria na porta compartilhada padrão 5432): " + datasourceUrl
                            );
                        }
                    } catch (NumberFormatException e) {
                        throw new IllegalStateException(
                                "SEGURANÇA DE INFRAESTRUTURA DE TESTE VIOLADA: " + propertyName +
                                        " possui porta parseada inválida: " + datasourceUrl
                        );
                    }
                }
            }

            // 3. Exigência estrita de formato canônico jdbc:postgresql://host:port/... com porta explícita para cada nó
            Matcher matcher = STRICT_POSTGRES_URL_PATTERN.matcher(trimmedUrl);
            if (!matcher.matches()) {
                throw new IllegalStateException(
                        "SEGURANÇA DE INFRAESTRUTURA DE TESTE VIOLADA: " + propertyName +
                                " não possui authority/host/porta explícitos (driver conectaria no default compartilhado localhost:5432): " + datasourceUrl
                );
            }

            String authority = matcher.group(1);
            String[] hostPortPairs = authority.split(",");
            for (String hostPort : hostPortPairs) {
                int colonIdx = hostPort.lastIndexOf(':');
                if (colonIdx == -1 || colonIdx == hostPort.length() - 1) {
                    throw new IllegalStateException(
                            "SEGURANÇA DE INFRAESTRUTURA DE TESTE VIOLADA: " + propertyName +
                                    " não possui porta explícita (driver PostgreSQL conectaria na porta compartilhada padrão 5432): " + datasourceUrl
                    );
                }
                String portPart = hostPort.substring(colonIdx + 1);
                try {
                    int port = Integer.parseInt(portPart);
                    if (FORBIDDEN_POSTGRES_PORTS.contains(port)) {
                        throw new IllegalStateException(
                                "SEGURANÇA DE INFRAESTRUTURA DE TESTE VIOLADA: " + propertyName +
                                        " aponta para porta proibida (" + port + "): " + datasourceUrl
                        );
                    }
                } catch (NumberFormatException e) {
                    throw new IllegalStateException(
                            "SEGURANÇA DE INFRAESTRUTURA DE TESTE VIOLADA: " + propertyName +
                                    " possui porta inválida: " + datasourceUrl
                    );
                }
            }
        }
    }

    private static void validateRedisProperties(ConfigurableEnvironment env) {
        String redisUrl = env.getProperty("spring.data.redis.url");
        if (redisUrl != null && !redisUrl.isBlank()) {
            try {
                URI uri = URI.create(redisUrl.trim());
                int port = uri.getPort();
                if (port == -1 || port == FORBIDDEN_REDIS_PORT) {
                    throw new IllegalStateException(
                            "SEGURANÇA DE INFRAESTRUTURA DE TESTE VIOLADA: spring.data.redis.url " +
                                    "aponta para porta proibida ou porta omitida (padrão " + FORBIDDEN_REDIS_PORT + "): " + redisUrl
                    );
                }
            } catch (IllegalArgumentException e) {
                if (redisUrl.contains(":" + FORBIDDEN_REDIS_PORT)) {
                    throw new IllegalStateException(
                            "SEGURANÇA DE INFRAESTRUTURA DE TESTE VIOLADA: spring.data.redis.url " +
                                    "aponta para porta proibida (" + FORBIDDEN_REDIS_PORT + "): " + redisUrl
                    );
                }
            }
        }

        String redisPortStr = env.getProperty("spring.data.redis.port");
        if (redisPortStr != null && !redisPortStr.isBlank()) {
            try {
                int redisPort = Integer.parseInt(redisPortStr.trim());
                if (redisPort == FORBIDDEN_REDIS_PORT) {
                    throw new IllegalStateException(
                            "SEGURANÇA DE INFRAESTRUTURA DE TESTE VIOLADA: spring.data.redis.port " +
                                    "aponta para porta compartilhada proibida (" + FORBIDDEN_REDIS_PORT + ")"
                    );
                }
            } catch (NumberFormatException ignored) {
            }
        }

        String redisHost = env.getProperty("spring.data.redis.host");
        if (redisHost != null && !redisHost.isBlank() && (redisPortStr == null || redisPortStr.isBlank()) && (redisUrl == null || redisUrl.isBlank())) {
            throw new IllegalStateException(
                    "SEGURANÇA DE INFRAESTRUTURA DE TESTE VIOLADA: spring.data.redis.host configurado sem spring.data.redis.port explícita (Spring Boot usaria porta padrão compartilhada " + FORBIDDEN_REDIS_PORT + ")"
            );
        }

        String clusterNodes = env.getProperty("spring.data.redis.cluster.nodes");
        if (clusterNodes != null && !clusterNodes.isBlank()) {
            for (String node : clusterNodes.split(",")) {
                validateRedisNode(node.trim(), "spring.data.redis.cluster.nodes");
            }
        }

        String sentinelNodes = env.getProperty("spring.data.redis.sentinel.nodes");
        if (sentinelNodes != null && !sentinelNodes.isBlank()) {
            for (String node : sentinelNodes.split(",")) {
                validateRedisNode(node.trim(), "spring.data.redis.sentinel.nodes");
            }
        }
    }

    private static void validateRedisNode(String node, String propertyName) {
        if (node.isBlank()) return;
        int colonIdx = node.lastIndexOf(':');
        if (colonIdx == -1 || colonIdx == node.length() - 1) {
            throw new IllegalStateException(
                    "SEGURANÇA DE INFRAESTRUTURA DE TESTE VIOLADA: " + propertyName +
                            " contém nó sem porta explícita (default compartilhado " + FORBIDDEN_REDIS_PORT + "): " + node
            );
        }
        try {
            int port = Integer.parseInt(node.substring(colonIdx + 1));
            if (port == FORBIDDEN_REDIS_PORT) {
                throw new IllegalStateException(
                        "SEGURANÇA DE INFRAESTRUTURA DE TESTE VIOLADA: " + propertyName +
                                " contém nó apontando para porta proibida (" + FORBIDDEN_REDIS_PORT + "): " + node
                );
            }
        } catch (NumberFormatException e) {
            throw new IllegalStateException(
                    "SEGURANÇA DE INFRAESTRUTURA DE TESTE VIOLADA: " + propertyName +
                            " possui porta inválida no nó: " + node
            );
        }
    }

    /** Endpoint S3 de teste: http(s)://host:porta com porta explícita e diferente da compartilhada. */
    public static void validateStorageEndpoint(String storageEndpoint, String name) {
        URI uri;
        try {
            uri = URI.create(storageEndpoint.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("SEGURANÇA DE INFRAESTRUTURA DE TESTE VIOLADA: " + name
                    + " inválido: " + storageEndpoint, e);
        }
        if (uri.getScheme() == null || !(uri.getScheme().equals("http") || uri.getScheme().equals("https"))
                || uri.getHost() == null) {
            throw new IllegalStateException("SEGURANÇA DE INFRAESTRUTURA DE TESTE VIOLADA: " + name
                    + " precisa ser http(s)://host:porta: " + storageEndpoint);
        }
        if (uri.getPort() == -1) {
            throw new IllegalStateException("SEGURANÇA DE INFRAESTRUTURA DE TESTE VIOLADA: " + name
                    + " não possui porta explícita: " + storageEndpoint);
        }
        if (uri.getPort() == FORBIDDEN_STORAGE_PORT) {
            throw new IllegalStateException(
                    "SEGURANÇA DE INFRAESTRUTURA DE TESTE VIOLADA: Storage de teste aponta para porta compartilhada proibida (" +
                            FORBIDDEN_STORAGE_PORT + "): " + storageEndpoint
            );
        }
    }
}
