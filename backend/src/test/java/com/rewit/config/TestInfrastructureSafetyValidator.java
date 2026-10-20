package com.rewit.config;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;

import java.util.Set;

/**
 * Validador de integridade e isolamento de infraestrutura de teste (A-1).
 * Executado na inicialização de qualquer contexto Spring de teste.
 * Rejeita categoricamente conexões acidentais aos endpoints compartilhados/desenvolvimento
 * (PostgreSQL portas 5432/5433, Redis porta 6379, SeaweedFS porta 8333) e valida
 * que as propriedades de teste sejam explícitas e isoladas.
 */
public class TestInfrastructureSafetyValidator implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    public static final Set<Integer> FORBIDDEN_POSTGRES_PORTS = Set.of(5432, 5433);
    public static final int FORBIDDEN_REDIS_PORT = 6379;
    public static final int FORBIDDEN_STORAGE_PORT = 8333;

    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        ConfigurableEnvironment env = applicationContext.getEnvironment();
        validateEnvironment(env);
    }

    public static void validateEnvironment(ConfigurableEnvironment env) {
        String datasourceUrl = env.getProperty("spring.datasource.url");
        if (datasourceUrl != null && !datasourceUrl.isBlank()) {
            for (int forbiddenPort : FORBIDDEN_POSTGRES_PORTS) {
                if (datasourceUrl.contains(":" + forbiddenPort + "/") || datasourceUrl.endsWith(":" + forbiddenPort)) {
                    throw new IllegalStateException(
                            "SEGURANÇA DE INFRAESTRUTURA DE TESTE VIOLADA: DataSource de teste aponta para porta proibida (" +
                                    forbiddenPort + "): " + datasourceUrl
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
                            "SEGURANÇA DE INFRAESTRUTURA DE TESTE VIOLADA: Redis de teste aponta para porta compartilhada proibida (" +
                                    FORBIDDEN_REDIS_PORT + ")"
                    );
                }
            } catch (NumberFormatException ignored) {
            }
        }

        String storageEndpoint = env.getProperty("rewit.minio.endpoint");
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

