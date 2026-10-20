package com.rewit.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

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

    /** Afirma a rejeição e que ela traz mensagem diagnóstica (nunca uma falha silenciosa). */
    private static void assertRejected(Class<? extends RuntimeException> type, org.junit.jupiter.api.function.Executable action) {
        RuntimeException ex = assertThrows(type, action);
        assertNotNull(ex.getMessage(), "Rejeição sem mensagem diagnóstica");
    }

    private static void assertRejected(Class<? extends RuntimeException> type, org.junit.jupiter.api.function.Executable action,
                                       String message) {
        RuntimeException ex = assertThrows(type, action, message);
        assertNotNull(ex.getMessage(), message);
    }

    // ------------------------------------------------------------------ A-1b: configuração explícita sem defaults

    private static final Map<String, String> DEV_VARIABLES = Map.of(
            "POSTGRES_HOST", "127.0.0.1", "POSTGRES_PORT", "5433", "POSTGRES_DB", "rewit_db",
            "DATABASE_URL", "jdbc:postgresql://127.0.0.1:5433/rewit_db",
            "REDIS_HOST", "127.0.0.1", "REDIS_PORT", "6379",
            "OBJECT_STORAGE_ENDPOINT", "http://127.0.0.1:8333", "STORAGE_ENDPOINT", "http://127.0.0.1:8333");

    private static final Map<String, String> TEST_VARIABLES = Map.ofEntries(
            Map.entry("TEST_DATASOURCE_URL", "jdbc:postgresql://127.0.0.1:61234/rewit_suite"),
            Map.entry("TEST_APP_DB_USER", "rewit_app"), Map.entry("TEST_APP_DB_PASSWORD", "app-secret"),
            Map.entry("TEST_MIGRATION_DB_USER", "rewit_owner"), Map.entry("TEST_MIGRATION_DB_PASSWORD", "owner-secret"),
            Map.entry("TEST_REDIS_HOST", "127.0.0.1"), Map.entry("TEST_REDIS_PORT", "61235"),
            Map.entry("TEST_STORAGE_ENDPOINT", "http://127.0.0.1:61236"), Map.entry("TEST_STORAGE_ACCESS_KEY", "access"),
            Map.entry("TEST_STORAGE_SECRET_KEY", "secret"), Map.entry("TEST_STORAGE_BUCKET", "bucket"));

    /** Ambiente com os YAMLs REAIS de teste (application.yml e application-local.yml do classpath de teste). */
    private static StandardEnvironment environmentWith(Map<String, String> variables) throws IOException {
        StandardEnvironment env = new StandardEnvironment();
        MutablePropertySources sources = env.getPropertySources();
        sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        sources.addFirst(new MapPropertySource("variaveis", new HashMap<>(variables)));
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        for (String file : List.of("application-local.yml", "application.yml")) {
            for (PropertySource<?> source : loader.load(file, new ClassPathResource(file))) {
                sources.addLast(source);
            }
        }
        return env;
    }

    @Test
    @DisplayName("A-1b: com apenas variáveis de desenvolvimento, os YAMLs de teste falham antes de qualquer conexão")
    void developmentVariablesAloneFailBeforeAnyConnection() throws Exception {
        StandardEnvironment env = environmentWith(DEV_VARIABLES);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> TestInfrastructureSafetyValidator.validateEnvironment(env));
        assertTrue(ex.getMessage().contains("TEST_DATASOURCE_URL"), ex.getMessage());
        assertTrue(ex.getMessage().contains("obrigatória ausente"), ex.getMessage());
        for (String property : List.of("spring.data.redis.port", "spring.data.redis.host", "rewit.minio.endpoint",
                "spring.datasource.username", "spring.datasource.password", "spring.flyway.user")) {
            assertRejected(IllegalArgumentException.class, () -> env.getProperty(property),
                    property + " não pode ter valor padrão nem usar variável de desenvolvimento");
        }
    }

    @Test
    @DisplayName("A-1b: com variáveis TEST_*, os YAMLs resolvem exatamente esses valores e ignoram as de desenvolvimento")
    void testVariablesWinAndDevelopmentVariablesAreIgnored() throws Exception {
        Map<String, String> all = new HashMap<>(DEV_VARIABLES);
        all.putAll(TEST_VARIABLES);
        StandardEnvironment env = environmentWith(all);

        assertDoesNotThrow(() -> TestInfrastructureSafetyValidator.validateEnvironment(env));
        assertEquals("jdbc:postgresql://127.0.0.1:61234/rewit_suite", env.getProperty("spring.datasource.url"));
        assertEquals("61235", env.getProperty("spring.data.redis.port"));
        assertEquals("http://127.0.0.1:61236", env.getProperty("rewit.minio.endpoint"));
        assertEquals("rewit_owner", env.getProperty("spring.flyway.user"));
    }

    @Test
    @DisplayName("A-1b: application-local.yml de teste sombreia o do main, cujos defaults apontam para serviços de desenvolvimento")
    void testLocalProfileShadowsMainLocalProfile() throws Exception {
        URL local = getClass().getClassLoader().getResource("application-local.yml");
        assertNotNull(local);
        assertTrue(local.toString().contains("test-classes"), "application-local.yml resolvido fora do classpath de teste: " + local);
        StandardEnvironment env = environmentWith(Map.of());
        assertRejected(IllegalStateException.class, () -> TestInfrastructureSafetyValidator.validateEnvironment(env));
    }

    @Test
    @DisplayName("A-1b: TestInfrastructureConfig falha sem TEST_* e nunca recorre a variáveis genéricas")
    void infrastructureConfigRequiresExplicitTestVariables() {
        TestInfrastructureConfig devOnly = TestInfrastructureConfig.of(DEV_VARIABLES);
        for (String variable : TestInfrastructureConfig.REQUIRED_VARIABLES) {
            IllegalStateException ex = assertThrows(IllegalStateException.class, () -> devOnly.require(variable));
            assertTrue(ex.getMessage().contains(variable));
        }
        assertRejected(IllegalStateException.class, devOnly::datasourceUrl);
        assertRejected(IllegalStateException.class, devOnly::redisPort);
        assertRejected(IllegalStateException.class, devOnly::storageEndpoint);
        // O teste destrutivo deriva o database descartável da URL explícita, nunca de uma porta padrão
        assertRejected(IllegalStateException.class, () -> devOnly.datasourceUrlForDatabase("rewit_migtest_0000000000000000"));
    }

    @Test
    @DisplayName("A-1b: database descartável derivado usa o host/porta de TEST_DATASOURCE_URL (nenhuma porta implícita)")
    void derivedDisposableDatabaseUsesExplicitServer() {
        TestInfrastructureConfig config = TestInfrastructureConfig.of(TEST_VARIABLES);
        String derived = config.datasourceUrlForDatabase("rewit_migtest_0123456789abcdef");
        assertEquals("jdbc:postgresql://127.0.0.1:61234/rewit_migtest_0123456789abcdef", derived);
        assertRejected(IllegalArgumentException.class, () -> config.datasourceUrlForDatabase("rewit; DROP DATABASE x"));

        Map<String, String> forbidden = new HashMap<>(TEST_VARIABLES);
        forbidden.put("TEST_DATASOURCE_URL", "jdbc:postgresql://127.0.0.1:5433/rewit_db");
        assertRejected(IllegalStateException.class, () -> TestInfrastructureConfig.of(forbidden).datasourceUrl());
        forbidden.put("TEST_DATASOURCE_URL", "jdbc:postgresql://127.0.0.1/rewit_db");
        assertRejected(IllegalStateException.class, () -> TestInfrastructureConfig.of(forbidden).datasourceUrl());
        forbidden.put("TEST_DATASOURCE_URL", "jdbc:postgresql://127.0.0.1:61234,127.0.0.1:61240/rewit_db");
        assertRejected(IllegalStateException.class, () -> TestInfrastructureConfig.of(forbidden).datasourceUrlForDatabase("rewit_x"));
        forbidden.put("TEST_REDIS_PORT", "6379");
        assertRejected(IllegalStateException.class, () -> TestInfrastructureConfig.of(forbidden).redisPort());
        forbidden.put("TEST_STORAGE_ENDPOINT", "http://127.0.0.1");
        assertRejected(IllegalStateException.class, () -> TestInfrastructureConfig.of(forbidden).storageEndpoint());
        forbidden.put("TEST_STORAGE_ENDPOINT", "http://127.0.0.1:8333");
        assertRejected(IllegalStateException.class, () -> TestInfrastructureConfig.of(forbidden).storageEndpoint());
    }

    @Test
    @DisplayName("A-1b: nenhum teste ou YAML de teste referencia variáveis de desenvolvimento ou portas descartáveis fixas")
    void testSourcesHaveNoGenericVariablesOrFixedPorts() throws Exception {
        // Somente leituras de configuração (literal "NOME" ou placeholder ${NOME}); constantes Java homônimas não contam
        Pattern generic = Pattern.compile(
                "(?:\"|\\$\\{)(POSTGRES_(HOST|PORT|DB|USER|PASSWORD)|REDIS_(HOST|PORT|URL)|OBJECT_STORAGE_ENDPOINT|STORAGE_ENDPOINT"
                        + "|MINIO_ENDPOINT|DATABASE_URL)(?=[\"}:])");
        Pattern fixedPorts = Pattern.compile("\\b(55432|56379|58333)\\b");
        List<String> offenders = new ArrayList<>();
        for (String root : List.of("src/test/java", "src/test/resources")) {
            try (Stream<Path> files = Files.walk(Path.of(root))) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    if (file.getFileName().toString().equals("TestInfrastructureIsolationTest.java")) {
                        continue;
                    }
                    String content = Files.readString(file);
                    // O lookbehind exclui nomes prefixados (TEST_*), que são os permitidos
                    Matcher genericMatch = generic.matcher(content);
                    if (genericMatch.find()) {
                        offenders.add(file + " -> " + genericMatch.group());
                    }
                    Matcher portMatch = fixedPorts.matcher(content);
                    if (portMatch.find()) {
                        offenders.add(file + " -> porta fixa " + portMatch.group());
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(), "Referências proibidas: " + offenders);
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
