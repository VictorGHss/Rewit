package com.rewit.config;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;

import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validador de integridade e isolamento de infraestrutura de teste (A-1, A-1a, A-1b).
 * Executado na inicialização de qualquer contexto Spring de teste.
 * Rejeita categoricamente conexões acidentais aos endpoints compartilhados/desenvolvimento
 * (PostgreSQL portas 5432/5433, Redis porta 6379, SeaweedFS porta 8333) e valida
 * que as propriedades de teste sejam explícitas e isoladas.
 */
public class TestInfrastructureSafetyValidator implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    public static final Set<Integer> FORBIDDEN_POSTGRES_PORTS = Set.of(5432, 5433);
    public static final int FORBIDDEN_REDIS_PORT = 6379;
    public static final int FORBIDDEN_STORAGE_PORT = 8333;

    private static final Pattern POSTGRES_URL_PATTERN = Pattern.compile("^jdbc:postgresql://([^/?#]+)(?:/.*)?$");

    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        ConfigurableEnvironment env = applicationContext.getEnvironment();
        validateEnvironment(env);
    }

    public static void validateEnvironment(ConfigurableEnvironment env) {
        validateDatasourceProperty(env.getProperty("spring.datasource.url"), "spring.datasource.url");
        validateDatasourceProperty(env.getProperty("spring.datasource.hikari.jdbc-url"), "spring.datasource.hikari.jdbc-url");

        validateRedisProperties(env);
        validateStorageProperty(env.getProperty("rewit.minio.endpoint"));
    }

    private static void validateDatasourceProperty(String datasourceUrl, String propertyName) {
        if (datasourceUrl == null || datasourceUrl.isBlank()) {
            return;
        }

        for (int forbiddenPort : FORBIDDEN_POSTGRES_PORTS) {
            if (datasourceUrl.contains(":" + forbiddenPort + "/") ||
                    datasourceUrl.contains(":" + forbiddenPort + "?") ||
                    datasourceUrl.endsWith(":" + forbiddenPort)) {
                throw new IllegalStateException(
                        "SEGURANÇA DE INFRAESTRUTURA DE TESTE VIOLADA: " + propertyName +
                                " aponta para porta proibida (" + forbiddenPort + "): " + datasourceUrl
                );
            }
        }

        Matcher matcher = POSTGRES_URL_PATTERN.matcher(datasourceUrl.trim());
        if (matcher.matches()) {
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
    }

    private static void validateStorageProperty(String storageEndpoint) {
        if (storageEndpoint != null && !storageEndpoint.isBlank()) {
            if (storageEndpoint.contains(":" + FORBIDDEN_STORAGE_PORT)) {
                throw new IllegalStateException(
                        "SEGURANÇA DE INFRAESTRUTURA DE TESTE VIOLADA: Storage de teste aponta para porta compartilhada proibida (" +
                                FORBIDDEN_STORAGE_PORT + "): " + storageEndpoint
                );
            }
        }
    }
}
