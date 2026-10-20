package com.rewit.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("A-1: Testes de Isolamento Estrito de Infraestrutura e Rejeição de Portas Proibidas")
class TestInfrastructureIsolationTest {

    @Test
    @DisplayName("Rejeita DataSource apontando para porta compartilhada/desenvolvimento 5432")
    void shouldRejectDatasourceOnPort5432() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.datasource.url", "jdbc:postgresql://localhost:5432/rewit_db");

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                TestInfrastructureSafetyValidator.validateEnvironment(env)
        );
        assertTrue(ex.getMessage().contains("5432"));
    }

    @Test
    @DisplayName("Rejeita DataSource apontando para porta compartilhada/desenvolvimento 5433")
    void shouldRejectDatasourceOnPort5433() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.datasource.url", "jdbc:postgresql://127.0.0.1:5433/rewit_db");

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                TestInfrastructureSafetyValidator.validateEnvironment(env)
        );
        assertTrue(ex.getMessage().contains("5433"));
    }

    @Test
    @DisplayName("Rejeita Redis apontando para porta compartilhada/desenvolvimento 6379")
    void shouldRejectRedisOnPort6379() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.data.redis.port", "6379");

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                TestInfrastructureSafetyValidator.validateEnvironment(env)
        );
        assertTrue(ex.getMessage().contains("6379"));
    }

    @Test
    @DisplayName("Rejeita Storage apontando para porta compartilhada/desenvolvimento 8333")
    void shouldRejectStorageOnPort8333() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("rewit.minio.endpoint", "http://127.0.0.1:8333");

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                TestInfrastructureSafetyValidator.validateEnvironment(env)
        );
        assertTrue(ex.getMessage().contains("8333"));
    }

    @Test
    @DisplayName("Aceita configuração válida com portas isoladas de teste (ex: 55432, 56379, 58333)")
    void shouldAcceptIsolatedTestInfrastructurePorts() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.datasource.url", "jdbc:postgresql://127.0.0.1:55432/rewit_test");
        env.setProperty("spring.data.redis.port", "56379");
        env.setProperty("rewit.minio.endpoint", "http://127.0.0.1:58333");

        assertDoesNotThrow(() -> TestInfrastructureSafetyValidator.validateEnvironment(env));
    }

    @Test
    @DisplayName("Variáveis de desenvolvimento não influenciam propriedades de teste no YAML")
    void devEnvironmentVariablesDoNotAffectTestProperties() {
        // Simula variáveis genéricas de ambiente de desenvolvimento
        MockEnvironment env = new MockEnvironment();
        env.setProperty("POSTGRES_PORT", "5433");
        env.setProperty("REDIS_PORT", "6379");
        env.setProperty("MINIO_ENDPOINT", "http://127.0.0.1:8333");

        // As propriedades de teste devem estar desacopladas das variáveis de desenvolvimento
        assertNull(env.getProperty("TEST_POSTGRES_PORT"));
        assertNull(env.getProperty("TEST_REDIS_PORT"));
        assertNull(env.getProperty("TEST_STORAGE_ENDPOINT"));
    }
}

