package com.rewit.infrastructure.observability;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Isolamento do Actuator em porta de management própria (Observabilidade V1, ADR-012).
 *
 * <p>Sobe o servidor real com a API e o management em portas aleatórias distintas e as mesmas propriedades de
 * management de {@code application.yml}: a garantia depende da porta, então não basta MockMvc.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "management.server.port=0",
                "management.endpoints.web.exposure.include=health,info,prometheus",
                "management.observations.enable[tasks.scheduled.execution]=false",
                "management.observations.enable[spring.security]=false",
                // O ambiente de testes não sobe Redis; sem isso o health agregado responderia 503
                "management.health.redis.enabled=false",
                // Storage GC montado só para registrar seus gauges: dry-run e primeiro ciclo daqui a um dia
                "rewit.storage-gc.enabled=true",
                "rewit.storage-gc.dry-run=true",
                "rewit.storage-gc.interval-ms=86400000",
                "rewit.storage-gc.initial-delay-ms=86400000",
                "rewit.storage-gc.page-size=10",
                "rewit.storage-gc.max-pages=1",
                "rewit.storage-gc.max-candidates=1",
                "rewit.storage-gc.max-deletes=1",
                "rewit.storage-gc.grace-period=30d",
                "rewit.storage-gc.max-duration=1m"
        }
)
@ActiveProfiles("local")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Testes de Integração: Actuator isolado na porta de management (Observabilidade V1)")
class ActuatorManagementPortIntegrationTest {

    private static final Pattern ACCESS_TOKEN = Pattern.compile("\"accessToken\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern USER_ID = Pattern.compile("\"id\"\\s*:\\s*\"([0-9a-f-]{36})\"");
    private static final Pattern LABELS = Pattern.compile("\\{(.*)}");
    private static final Pattern LABEL = Pattern.compile("([a-zA-Z_][a-zA-Z0-9_]*)=\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern UUID_VALUE = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Set<String> FORBIDDEN_LABELS = Set.of("user", "userid", "user_id", "session", "sessionid",
            "session_id", "reviewid", "review_id", "mediaid", "media_id", "objectkey", "object_key", "key", "token",
            "token_hash", "authorization", "ip", "client_ip", "user_agent", "query", "message", "error_message");

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    private int apiPort;

    @LocalManagementPort
    private int managementPort;

    private String userToken;
    private String userId;

    @BeforeAll
    void registerUser() throws Exception {
        String handle = "obs" + UUID.randomUUID().toString().substring(0, 8);
        String body = """
                {"email":"%s@rewit.test","password":"Sonda-Forte-123!","handle":"%s","displayName":"Sonda"}
                """.formatted(handle, handle);
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(api("/api/v1/auth/register")))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(201, response.statusCode());
        Matcher matcher = ACCESS_TOKEN.matcher(response.body());
        assertTrue(matcher.find());
        userToken = matcher.group(1);
        Matcher id = USER_ID.matcher(response.body());
        assertTrue(id.find());
        userId = id.group(1);
    }

    @Test
    @DisplayName("Management e API usam portas diferentes")
    void managementRunsOnSeparatePort() {
        assertNotEquals(apiPort, managementPort);
    }

    @Test
    @DisplayName("health, info e prometheus respondem na porta de management, sem credenciais")
    void allowedEndpointsAnswerOnManagementPort() throws Exception {
        assertEquals(200, get(management("/actuator/health"), null).statusCode());
        assertEquals(200, get(management("/actuator/info"), null).statusCode());

        HttpResponse<String> prometheus = get(management("/actuator/prometheus"), null);
        assertEquals(200, prometheus.statusCode());
        assertTrue(prometheus.body().contains("rewit_outbox_pending"));
        assertTrue(prometheus.body().contains("jvm_memory_used_bytes"));
        assertTrue(prometheus.body().contains("hikaricp_connections"));
    }

    @Test
    @DisplayName("Prometheus expõe as métricas rewit.* existentes, inclusive o Storage GC quando habilitado")
    void prometheusExportsExistingMetrics() throws Exception {
        String body = get(management("/actuator/prometheus"), null).body();

        for (String series : new String[]{"rewit_outbox_pending", "rewit_outbox_failed", "rewit_outbox_oldest_pending_age",
                "rewit_storage_gc_quarantine_observed", "rewit_storage_gc_quarantine_confirmed",
                "http_server_requests_seconds_count", "jvm_threads_live_threads", "process_cpu_usage"}) {
            assertTrue(body.contains(series), "série ausente: " + series);
        }
    }

    @Test
    @DisplayName("Nenhuma série tem label de identidade, segredo ou valor de alta cardinalidade")
    void prometheusLabelsHaveNoForbiddenData() throws Exception {
        get(api("/api/v1/auth/me"), userToken);
        get(api("/api/v1/users/" + UUID.randomUUID()), userToken);
        get(api("/api/v1/rota-inexistente-" + UUID.randomUUID() + "?email=sonda@rewit.test"), userToken);
        get(api("/api/v1/auth/me"), "token-invalido");

        String body = get(management("/actuator/prometheus"), null).body();
        int series = 0;
        for (String line : body.split("\\R")) {
            Matcher labels = LABELS.matcher(line);
            if (line.startsWith("#") || !labels.find()) {
                continue;
            }
            Matcher label = LABEL.matcher(labels.group(1));
            while (label.find()) {
                series++;
                String key = label.group(1).toLowerCase(Locale.ROOT);
                String value = label.group(2);
                assertFalse(FORBIDDEN_LABELS.contains(key), "label proibido: " + key);
                assertFalse(UUID_VALUE.matcher(value).find(), "valor com UUID no label " + key);
                assertFalse(value.contains("@") || value.contains("?") || value.contains("Bearer ")
                        || value.contains("reviews/") || value.contains(userToken), "valor proibido no label " + key);
            }
        }
        assertTrue(series > 0);
        assertFalse(body.contains(userId));
        assertFalse(body.contains("spring_security_"), "observações do Spring Security desligadas");
        assertFalse(body.contains("tasks_scheduled_execution"), "observação de @Scheduled desligada");
    }

    @Test
    @DisplayName("Endpoints fora da lista não existem na porta de management, nem com JWT")
    void otherEndpointsAreNotAvailable() throws Exception {
        for (String path : new String[]{"/actuator/metrics", "/actuator/env", "/actuator/beans",
                "/actuator/configprops", "/actuator/mappings", "/actuator/loggers",
                "/actuator/threaddump", "/actuator/heapdump"}) {
            assertNotEquals(200, get(management(path), null).statusCode(), path);
            assertNotEquals(200, get(management(path), userToken).statusCode(), path + " com JWT");
        }
    }

    @Test
    @DisplayName("A API não serve nenhum endpoint do Actuator, nem para USER autenticado")
    void apiPortDoesNotServeActuator() throws Exception {
        for (String path : new String[]{"/actuator/prometheus", "/actuator/metrics", "/actuator/health",
                "/actuator/info", "/actuator"}) {
            assertNotEquals(200, get(api(path), null).statusCode(), path);
            HttpResponse<String> withUser = get(api(path), userToken);
            assertNotEquals(200, withUser.statusCode(), path + " com JWT de USER");
            assertFalse(withUser.body().contains("rewit_outbox_pending"), path);
        }
    }

    @Test
    @DisplayName("O JWT de USER não é necessário nem amplia o acesso na porta de management")
    void userTokenDoesNotChangeManagementAccess() throws Exception {
        assertEquals(200, get(management("/actuator/prometheus"), userToken).statusCode());
        assertNotEquals(200, get(management("/actuator/metrics"), userToken).statusCode());
    }

    private HttpResponse<String> get(String url, String bearer) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).GET();
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String api(String path) {
        return "http://127.0.0.1:" + apiPort + path;
    }

    private String management(String path) {
        return "http://127.0.0.1:" + managementPort + path;
    }
}
