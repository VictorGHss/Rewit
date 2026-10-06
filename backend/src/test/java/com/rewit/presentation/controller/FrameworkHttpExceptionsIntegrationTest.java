package com.rewit.presentation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Testes de integração para mapeamento de exceções HTTP 4xx do framework Spring MVC.
 * Verifica status HTTP, RFC 7807 (problem+json), code, type, ausência de detalhes sensíveis/stack traces,
 * ausência de log ERROR, e métricas Micrometer (CLIENT_ERROR sem conversão para 500).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "management.server.port=0",
                "management.endpoints.web.exposure.include=health,info,prometheus",
                "management.observations.enable[tasks.scheduled.execution]=false",
                "management.observations.enable[spring.security]=false",
                "management.health.redis.enabled=false"
        }
)
@ActiveProfiles("local")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("Testes de Integração: Mapeamento de Exceções HTTP 4xx do Framework")
class FrameworkHttpExceptionsIntegrationTest {

    private static final Pattern ACCESS_TOKEN = Pattern.compile("\"accessToken\"\\s*:\\s*\"([^\"]+)\"");

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @LocalServerPort
    private int apiPort;

    @LocalManagementPort
    private int managementPort;

    private String userToken;

    @BeforeAll
    void setupUser() throws Exception {
        String handle = "ex" + UUID.randomUUID().toString().substring(0, 8);
        String body = """
                {"email":"%s@rewit.test","password":"Password-123!","handle":"%s","displayName":"Tester"}
                """.formatted(handle, handle);
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(api("/api/v1/auth/register")))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(201, response.statusCode());
        Matcher matcher = ACCESS_TOKEN.matcher(response.body());
        assertTrue(matcher.find());
        userToken = matcher.group(1);
    }

    private String api(String path) {
        return "http://localhost:" + apiPort + path;
    }

    private String management(String path) {
        return "http://localhost:" + managementPort + path;
    }

    private void assertNoUnexpectedErrorLogs(CapturedOutput output) {
        long errorCount = output.toString().lines()
                .filter(line -> line.contains("\"level\":\"ERROR\"") || line.contains("ERROR com.rewit"))
                .count();
        assertEquals(0, errorCount, "Nenhum log ERROR deve ser gerado para erros HTTP 4xx");
    }

    private void assertPrometheusOutcome(int expectedStatus) throws Exception {
        HttpResponse<String> prometheusResponse = http.send(
                HttpRequest.newBuilder(URI.create(management("/actuator/prometheus"))).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, prometheusResponse.statusCode());
        String body = prometheusResponse.body();

        assertTrue(body.lines().anyMatch(line -> line.startsWith("http_server_requests_seconds_count")
                        && line.contains("status=\"" + expectedStatus + "\"")
                        && line.contains("outcome=\"CLIENT_ERROR\"")),
                "Métricas devem conter status=" + expectedStatus + " com outcome=CLIENT_ERROR");

        assertTrue(body.lines().noneMatch(line -> line.startsWith("http_server_requests_seconds_count")
                        && line.contains("status=\"500\"")),
                "Nenhuma requisição deve ter sido convertida para HTTP 500");
    }

    @Test
    @DisplayName("1. POST em endpoint que aceita apenas GET deve retornar 405 Method Not Allowed")
    void shouldReturn405ForMethodNotAllowed(CapturedOutput output) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(api("/api/v1/auth/me")))
                .header("Authorization", "Bearer " + userToken)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(405, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("problem+json"));

        JsonNode json = objectMapper.readTree(response.body());
        assertEquals(405, json.get("status").asInt());
        assertEquals("METHOD_NOT_ALLOWED", json.get("code").asText());
        assertEquals("https://api.rewit.app/errors/method-not-allowed", json.get("type").asText());
        assertEquals("Método Não Permitido", json.get("title").asText());
        assertNotNull(json.get("timestamp"));
        assertNull(json.get("trace"));
        assertNull(json.get("stackTrace"));

        assertNoUnexpectedErrorLogs(output);
        assertPrometheusOutcome(405);
    }

    @Test
    @DisplayName("2. Content-Type incompatível deve retornar 415 Unsupported Media Type")
    void shouldReturn415ForUnsupportedMediaType(CapturedOutput output) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(api("/api/v1/auth/login")))
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("corpo em texto plano"))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(415, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("problem+json"));

        JsonNode json = objectMapper.readTree(response.body());
        assertEquals(415, json.get("status").asInt());
        assertEquals("UNSUPPORTED_MEDIA_TYPE", json.get("code").asText());
        assertEquals("https://api.rewit.app/errors/unsupported-media-type", json.get("type").asText());
        assertEquals("Tipo de Mídia Não Suportado", json.get("title").asText());
        assertNotNull(json.get("timestamp"));
        assertNull(json.get("trace"));
        assertNull(json.get("stackTrace"));

        assertNoUnexpectedErrorLogs(output);
        assertPrometheusOutcome(415);
    }

    @Test
    @DisplayName("3. Parâmetro obrigatório ausente deve retornar 400 Bad Request")
    void shouldReturn400ForMissingServletRequestParameter(CapturedOutput output) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(api("/api/v1/search")))
                .header("Authorization", "Bearer " + userToken)
                .GET()
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(400, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("problem+json"));

        JsonNode json = objectMapper.readTree(response.body());
        assertEquals(400, json.get("status").asInt());
        assertEquals("MISSING_PARAMETER", json.get("code").asText());
        assertEquals("https://api.rewit.app/errors/missing-parameter", json.get("type").asText());
        assertEquals("Parâmetro Ausente", json.get("title").asText());
        assertTrue(json.get("detail").asText().contains("q"));
        assertNotNull(json.get("timestamp"));
        assertNull(json.get("trace"));
        assertNull(json.get("stackTrace"));

        assertNoUnexpectedErrorLogs(output);
        assertPrometheusOutcome(400);
    }

    @Test
    @DisplayName("4. Parâmetro com tipo inválido deve retornar 400 Bad Request")
    void shouldReturn400ForMethodArgumentTypeMismatch(CapturedOutput output) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(api("/api/v1/search?q=teste&page=invalido")))
                .header("Authorization", "Bearer " + userToken)
                .GET()
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(400, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("problem+json"));

        JsonNode json = objectMapper.readTree(response.body());
        assertEquals(400, json.get("status").asInt());
        assertEquals("TYPE_MISMATCH", json.get("code").asText());
        assertEquals("https://api.rewit.app/errors/type-mismatch", json.get("type").asText());
        assertEquals("Tipo de Parâmetro Inválido", json.get("title").asText());
        assertTrue(json.get("detail").asText().contains("page"));
        assertNotNull(json.get("timestamp"));
        assertNull(json.get("trace"));
        assertNull(json.get("stackTrace"));

        assertNoUnexpectedErrorLogs(output);
        assertPrometheusOutcome(400);
    }

    @Test
    @DisplayName("5. JSON malformado deve retornar 400 Bad Request")
    void shouldReturn400ForMalformedJson(CapturedOutput output) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(api("/api/v1/auth/login")))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{json_malformado:"))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(400, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("problem+json"));

        JsonNode json = objectMapper.readTree(response.body());
        assertEquals(400, json.get("status").asInt());
        assertEquals("MALFORMED_REQUEST", json.get("code").asText());
        assertEquals("https://api.rewit.app/errors/malformed-request", json.get("type").asText());
        assertEquals("Requisição Inválida", json.get("title").asText());
        assertNotNull(json.get("timestamp"));
        assertNull(json.get("trace"));
        assertNull(json.get("stackTrace"));

        assertNoUnexpectedErrorLogs(output);
        assertPrometheusOutcome(400);
    }

    @Test
    @DisplayName("6. Erro de validação de request deve retornar 400 Bad Request com fieldErrors")
    void shouldReturn400ForValidationErrors(CapturedOutput output) throws Exception {
        String invalidBody = """
                {"email":"nao-e-um-email","password":""}
                """;
        HttpRequest request = HttpRequest.newBuilder(URI.create(api("/api/v1/auth/login")))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(invalidBody))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(400, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("problem+json"));

        JsonNode json = objectMapper.readTree(response.body());
        assertEquals(400, json.get("status").asInt());
        assertEquals("VALIDATION_ERROR", json.get("code").asText());
        assertEquals("https://api.rewit.app/errors/validation-error", json.get("type").asText());
        assertEquals("Erro de Validação de Dados", json.get("title").asText());
        assertNotNull(json.get("fieldErrors"));
        assertTrue(json.get("fieldErrors").has("email") || json.get("fieldErrors").has("password"));
        assertNotNull(json.get("timestamp"));
        assertNull(json.get("trace"));
        assertNull(json.get("stackTrace"));

        assertNoUnexpectedErrorLogs(output);
        assertPrometheusOutcome(400);
    }

    @Test
    @DisplayName("7. Negociação de Accept incompatível deve retornar 406 Not Acceptable")
    void shouldReturn406ForNotAcceptable(CapturedOutput output) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(api("/api/v1/search?q=teste")))
                .header("Authorization", "Bearer " + userToken)
                .header("Accept", "application/xml")
                .GET()
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(406, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("problem+json"));

        JsonNode json = objectMapper.readTree(response.body());
        assertEquals(406, json.get("status").asInt());
        assertEquals("NOT_ACCEPTABLE", json.get("code").asText());
        assertEquals("https://api.rewit.app/errors/not-acceptable", json.get("type").asText());
        assertEquals("Não Aceitável", json.get("title").asText());
        assertNotNull(json.get("timestamp"));
        assertNull(json.get("trace"));
        assertNull(json.get("stackTrace"));

        assertNoUnexpectedErrorLogs(output);
        assertPrometheusOutcome(406);
    }

    @Test
    @DisplayName("8. Rate limit excedido deve retornar 429 com Retry-After e métrica CLIENT_ERROR no Prometheus")
    void shouldReturn429WithRetryAfterAndClientErrorMetric(CapturedOutput output) throws Exception {
        String testEmail = "rl-metrics-" + UUID.randomUUID() + "@rewit.test";
        String body = """
                {"email":"%s","password":"Password-Wrong"}
                """.formatted(testEmail);

        HttpResponse<String> lastResponse = null;
        for (int i = 0; i < 11; i++) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(api("/api/v1/auth/login")))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            lastResponse = http.send(request, HttpResponse.BodyHandlers.ofString());
        }

        assertNotNull(lastResponse);
        assertEquals(429, lastResponse.statusCode());
        assertTrue(lastResponse.headers().firstValue("Content-Type").orElse("").contains("problem+json"));
        assertTrue(lastResponse.headers().firstValue("Retry-After").isPresent(), "Cabeçalho Retry-After deve estar presente");
        String retryAfter = lastResponse.headers().firstValue("Retry-After").get();
        assertTrue(retryAfter.matches("^[1-9]\\d*$"), "Retry-After deve ser inteiro positivo: " + retryAfter);

        JsonNode json = objectMapper.readTree(lastResponse.body());
        assertEquals(429, json.get("status").asInt());
        assertEquals("RATE_LIMIT_EXCEEDED", json.get("code").asText());
        assertEquals("https://api.rewit.app/errors/rate_limit_exceeded", json.get("type").asText());
        assertNotNull(json.get("timestamp"));
        assertNull(json.get("trace"));
        assertNull(json.get("stackTrace"));

        assertNoUnexpectedErrorLogs(output);
        assertPrometheusOutcome(429);
    }
}

