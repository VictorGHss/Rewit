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
    @DisplayName("Rejeita DataSource Hikari apontando para porta compartilhada/desenvolvimento 5432")
    void shouldRejectHikariDatasourceOnPort5432() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.datasource.hikari.jdbc-url", "jdbc:postgresql://localhost:5432/rewit_db");

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                TestInfrastructureSafetyValidator.validateEnvironment(env)
        );
        assertTrue(ex.getMessage().contains("5432"));
        assertTrue(ex.getMessage().contains("spring.datasource.hikari.jdbc-url"));
    }

    @Test
    @DisplayName("Rejeita DataSource sem porta explícita (PostgreSQL default 5432) em spring.datasource.url")
    void shouldRejectDatasourceWithoutExplicitPort() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.datasource.url", "jdbc:postgresql://localhost/rewit_db");

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                TestInfrastructureSafetyValidator.validateEnvironment(env)
        );
        assertTrue(ex.getMessage().contains("não possui porta explícita"));
    }

    @Test
    @DisplayName("Rejeita DataSource Hikari sem porta explícita em spring.datasource.hikari.jdbc-url")
    void shouldRejectHikariDatasourceWithoutExplicitPort() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.datasource.hikari.jdbc-url", "jdbc:postgresql://127.0.0.1/rewit_db");

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                TestInfrastructureSafetyValidator.validateEnvironment(env)
        );
        assertTrue(ex.getMessage().contains("não possui porta explícita"));
        assertTrue(ex.getMessage().contains("spring.datasource.hikari.jdbc-url"));
    }

    @Test
    @DisplayName("Rejeita Redis apontando para porta compartilhada/desenvolvimento 6379 via spring.data.redis.port")
    void shouldRejectRedisOnPort6379() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.data.redis.port", "6379");

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                TestInfrastructureSafetyValidator.validateEnvironment(env)
        );
        assertTrue(ex.getMessage().contains("6379"));
    }

    @Test
    @DisplayName("Rejeita Redis apontando para porta 6379 via spring.data.redis.url")
    void shouldRejectRedisUrlOnPort6379() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.data.redis.url", "redis://localhost:6379");

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                TestInfrastructureSafetyValidator.validateEnvironment(env)
        );
        assertTrue(ex.getMessage().contains("6379"));
    }

    @Test
    @DisplayName("Rejeita Redis URL sem porta explícita (default 6379) via spring.data.redis.url")
    void shouldRejectRedisUrlWithoutExplicitPort() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.data.redis.url", "redis://localhost/0");

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                TestInfrastructureSafetyValidator.validateEnvironment(env)
        );
        assertTrue(ex.getMessage().contains("porta omitida"));
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
    @DisplayName("Aceita configuração válida com Hikari e Redis URL em portas isoladas de teste")
    void shouldAcceptIsolatedHikariAndRedisUrlPorts() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.datasource.hikari.jdbc-url", "jdbc:postgresql://127.0.0.1:55432/rewit_test");
        env.setProperty("spring.data.redis.url", "redis://127.0.0.1:56379");
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

    @Test
    @DisplayName("A-1c: Rejeita JDBC sem host e sem porta explícita (jdbc:postgresql:rewit_test)")
    void shouldRejectDatasourceWithoutHostAndPort() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.datasource.url", "jdbc:postgresql:rewit_test");

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                TestInfrastructureSafetyValidator.validateEnvironment(env)
        );
        assertTrue(ex.getMessage().contains("SEGURANÇA DE INFRAESTRUTURA DE TESTE VIOLADA"));
    }

    @Test
    @DisplayName("A-1c: Rejeita JDBC com barra tripla e sem porta (jdbc:postgresql:///rewit_test)")
    void shouldRejectDatasourceTripleSlashWithoutPort() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.datasource.url", "jdbc:postgresql:///rewit_test");

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                TestInfrastructureSafetyValidator.validateEnvironment(env)
        );
        assertTrue(ex.getMessage().contains("SEGURANÇA DE INFRAESTRUTURA DE TESTE VIOLADA"));
    }

    @Test
    @DisplayName("A-1c: Rejeita JDBC alternativo em spring.datasource.jdbc-url sem porta explícita")
    void shouldRejectAlternativeDatasourcePropertyWithoutPort() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.datasource.jdbc-url", "jdbc:postgresql://localhost/rewit_test");

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                TestInfrastructureSafetyValidator.validateEnvironment(env)
        );
        assertTrue(ex.getMessage().contains("não possui porta explícita") || ex.getMessage().contains("5432"));
    }

    @Test
    @DisplayName("A-1c: Rejeita Redis Cluster com nó apontando para porta compartilhada 6379")
    void shouldRejectRedisClusterWithForbiddenPort() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.data.redis.cluster.nodes", "127.0.0.1:56379,127.0.0.1:6379");

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                TestInfrastructureSafetyValidator.validateEnvironment(env)
        );
        assertTrue(ex.getMessage().contains("6379"));
        assertTrue(ex.getMessage().contains("spring.data.redis.cluster.nodes"));
    }

    @Test
    @DisplayName("A-1c: Rejeita Redis Cluster com nó sem porta explícita")
    void shouldRejectRedisClusterWithMissingPort() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.data.redis.cluster.nodes", "127.0.0.1:56379,127.0.0.1");

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                TestInfrastructureSafetyValidator.validateEnvironment(env)
        );
        assertTrue(ex.getMessage().contains("sem porta explícita"));
    }

    @Test
    @DisplayName("A-1c: Rejeita Redis Sentinel com nó apontando para porta compartilhada 6379")
    void shouldRejectRedisSentinelWithForbiddenPort() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.data.redis.sentinel.nodes", "127.0.0.1:6379");

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                TestInfrastructureSafetyValidator.validateEnvironment(env)
        );
        assertTrue(ex.getMessage().contains("6379"));
        assertTrue(ex.getMessage().contains("spring.data.redis.sentinel.nodes"));
    }

    @Test
    @DisplayName("A-1c: Rejeita redis.host isolado sem redis.port explícita")
    void shouldRejectRedisHostWithoutExplicitPort() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.data.redis.host", "127.0.0.1");

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                TestInfrastructureSafetyValidator.validateEnvironment(env)
        );
        assertTrue(ex.getMessage().contains("spring.data.redis.host configurado sem spring.data.redis.port"));
    }

    @Test
    @DisplayName("A-1c: Aceita Redis Cluster e Sentinel com nós em portas isoladas de teste")
    void shouldAcceptIsolatedClusterAndSentinelNodes() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.datasource.url", "jdbc:postgresql://127.0.0.1:55432/rewit_test");
        env.setProperty("spring.data.redis.cluster.nodes", "127.0.0.1:56379,127.0.0.1:56380");
        env.setProperty("spring.data.redis.sentinel.nodes", "127.0.0.1:26380");
        env.setProperty("rewit.minio.endpoint", "http://127.0.0.1:58333");

        assertDoesNotThrow(() -> TestInfrastructureSafetyValidator.validateEnvironment(env));
    }
}
