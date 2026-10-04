package com.rewit.infrastructure.observability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;

import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contrato da configuração de observabilidade por ambiente (Observabilidade V1, ADR-012).
 *
 * <p>O {@code src/test/resources/application.yml} substitui o principal no classpath dos testes; por isso os arquivos
 * da aplicação são lidos do disco, como a aplicação real os carrega.
 */
@DisplayName("Testes de Configuração: observabilidade por ambiente (Observabilidade V1)")
class ObservabilityConfigurationTest {

    private static final List<String> FORBIDDEN_ENDPOINTS = List.of("metrics", "env", "beans", "configprops",
            "mappings", "loggers", "threaddump", "heapdump", "*");

    @Test
    @DisplayName("Base: management em porta própria, só health/info/prometheus, JSON, sampling 0.1 e sem endpoint OTLP")
    void baseConfiguration() {
        Properties base = load("src/main/resources/application.yml");

        assertEquals("${SERVER_PORT:8080}", base.getProperty("server.port"));
        assertEquals("${MANAGEMENT_SERVER_PORT:8081}", base.getProperty("management.server.port"));
        List<String> exposed = Arrays.stream(base.getProperty("management.endpoints.web.exposure.include").split(","))
                .map(value -> value.trim()).toList();
        assertEquals(List.of("health", "info", "prometheus"), exposed);
        FORBIDDEN_ENDPOINTS.forEach(endpoint -> assertFalse(exposed.contains(endpoint), endpoint));

        assertEquals("false", base.getProperty("management.observations.enable[tasks.scheduled.execution]"));
        assertEquals("false", base.getProperty("management.observations.enable[spring.security]"));
        assertEquals("${TRACING_SAMPLING_PROBABILITY:0.1}", base.getProperty("management.tracing.sampling.probability"));
        assertEquals("false", base.getProperty("management.logging.export.otlp.enabled"));
        assertTrue(base.stringPropertyNames().stream().noneMatch(name -> name.contains("otlp.endpoint")
                        || name.startsWith("management.otlp.metrics")),
                "nenhum endpoint OTLP ou exportação de métricas OTLP declarado");

        assertEquals("${LOG_STRUCTURED_FORMAT:logstash}", base.getProperty("logging.structured.format.console"));
        assertEquals(SanitizedStackTracePrinter.class.getName(),
                base.getProperty("logging.structured.json.stacktrace.printer"));
        assertEquals("true", base.getProperty("spring.threads.virtual.enabled"));
    }

    @Test
    @DisplayName("Local: logs em texto e todas as requisições amostradas; porta de management herdada do base")
    void localConfiguration() {
        Properties local = load("src/main/resources/application-local.yml");

        assertEquals("${LOG_STRUCTURED_FORMAT:}", local.getProperty("logging.structured.format.console"));
        assertEquals("${TRACING_SAMPLING_PROBABILITY:1.0}", local.getProperty("management.tracing.sampling.probability"));
        assertNull(local.getProperty("management.server.port"));
        assertNull(local.getProperty("management.endpoints.web.exposure.include"));
    }

    @Test
    @DisplayName("Testes: exportação de spans desligada, sem collector, logs em texto e Actuator no default")
    void testConfiguration() {
        Properties test = load("src/test/resources/application.yml");

        assertEquals("false", test.getProperty("management.tracing.export.enabled"));
        assertNull(test.getProperty("logging.structured.format.console"));
        assertNull(test.getProperty("management.endpoints.web.exposure.include"));
        assertTrue(test.stringPropertyNames().stream().noneMatch(name -> name.contains("otlp")));
    }

    @Test
    @DisplayName("Configuração inválida é detectada: porta de management igual à da API ou endpoint proibido exposto")
    void invalidConfigurationIsDetected() {
        Properties base = load("src/main/resources/application.yml");
        assertNotEquals(defaultOf(base.getProperty("server.port")), defaultOf(base.getProperty("management.server.port")));

        Properties invalid = new Properties();
        invalid.setProperty("management.endpoints.web.exposure.include", "health,info,prometheus,metrics");
        List<String> exposed = Arrays.asList(invalid.getProperty("management.endpoints.web.exposure.include").split(","));
        assertTrue(FORBIDDEN_ENDPOINTS.stream().anyMatch(exposed::contains));
    }

    private static String defaultOf(String placeholder) {
        return placeholder.substring(placeholder.indexOf(':') + 1, placeholder.length() - 1);
    }

    private static Properties load(String path) {
        FileSystemResource resource = new FileSystemResource(path);
        assertTrue(resource.exists(), path);
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(resource);
        Properties properties = factory.getObject();
        assertNotNull(properties);
        return properties;
    }
}
