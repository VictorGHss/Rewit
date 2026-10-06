package com.rewit.infrastructure.observability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import javax.annotation.Nonnull;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tracing HTTP e logs JSON com as mesmas propriedades de {@code application.yml} (Observabilidade V1, ADR-012).
 *
 * <p>Os spans vão para um exportador em memória; o Google Places é um servidor HTTP local. Os segredos enviados nas
 * requisições (JWT, refresh token, IP, User-Agent, query string, e-mail) não podem aparecer em nenhum span nem log.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "management.tracing.export.enabled=true",
                "management.tracing.sampling.probability=1.0",
                "management.opentelemetry.tracing.export.schedule-delay=50ms",
                "management.observations.enable[tasks.scheduled.execution]=false",
                "management.observations.enable[spring.security]=false",
                "management.observations.enable[lettuce]=false",
                "logging.structured.format.console=logstash",
                "logging.structured.json.stacktrace.printer=com.rewit.infrastructure.observability.SanitizedStackTracePrinter",
                "logging.structured.json.stacktrace.max-length=8192",
                "logging.structured.json.stacktrace.max-throwable-depth=30",
                "rewit.google.places.api-key=" + ObservabilityTracingLoggingIntegrationTest.GOOGLE_API_KEY
        }
)
@ActiveProfiles("local")
@ExtendWith(OutputCaptureExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Testes de Integração: tracing HTTP e logs JSON sem dados sensíveis (Observabilidade V1)")
class ObservabilityTracingLoggingIntegrationTest {

    static final String GOOGLE_API_KEY = "chave-google-de-teste";

    private static final String USER_AGENT = "SondaObservabilidade/9.9";
    private static final String FORWARDED_IP = "10.98.76.54";
    private static final String EXCEPTION_SECRET = "segredo-da-excecao";

    private static final CollectingSpanExporter EXPORTER = new CollectingSpanExporter();
    private static final AtomicReference<String> GOOGLE_TRACEPARENT = new AtomicReference<>();
    private static final AtomicReference<String> GOOGLE_BODY = new AtomicReference<>();
    private static final HttpServer GOOGLE_STUB = startGoogleStub();

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @LocalServerPort
    private int apiPort;

    @Autowired
    private ScheduledProbe scheduledProbe;

    @Autowired
    private MeterRegistry meterRegistry;

    private String email;
    private String userId;
    private String accessToken;
    private String refreshToken;

    @DynamicPropertySource
    static void googleBaseUrl(DynamicPropertyRegistry registry) {
        registry.add("rewit.google.places.base-url", () -> "http://127.0.0.1:" + GOOGLE_STUB.getAddress().getPort());
    }

    @BeforeAll
    void registerUser() throws Exception {
        String handle = "obs" + UUID.randomUUID().toString().substring(0, 8);
        email = handle + "@rewit.test";
        HttpResponse<String> response = send(HttpRequest.newBuilder(api("/api/v1/auth/register"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"email":"%s","password":"Sonda-Forte-123!","handle":"%s","displayName":"Sonda"}
                        """.formatted(email, handle))));
        assertEquals(201, response.statusCode());
        JsonNode body = json.readTree(response.body());
        accessToken = body.get("accessToken").asText();
        refreshToken = body.get("refreshToken").asText();
        userId = body.get("user").get("id").asText();
    }

    @AfterAll
    void stopGoogleStub() {
        GOOGLE_STUB.stop(0);
    }

    @Test
    @DisplayName("Requisição HTTP gera span de servidor com rota normalizada e sem credenciais, body ou query string")
    void serverSpanIsSanitized() throws Exception {
        HttpResponse<String> refreshed = send(HttpRequest.newBuilder(api("/api/v1/auth/refresh"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"refreshToken\":\"" + refreshToken + "\"}")));
        assertEquals(200, refreshed.statusCode());
        HttpResponse<String> me = send(authorized(api("/api/v1/auth/me?email=" + email)).GET());
        assertEquals(200, me.statusCode());

        SpanData refreshSpan = awaitSpan(span -> span.getKind() == SpanKind.SERVER
                && "/api/v1/auth/refresh".equals(attribute(span, "uri")));
        assertEquals("POST", attribute(refreshSpan, "method"));
        assertEquals("200", attribute(refreshSpan, "status"));
        SpanData meSpan = awaitSpan(span -> span.getKind() == SpanKind.SERVER
                && "/api/v1/auth/me".equals(attribute(span, "uri")));
        assertEquals("/api/v1/auth/me", attribute(meSpan, "http.url"));

        assertSpansFreeOf(accessToken, refreshToken, userId, email, "email=", USER_AGENT, FORWARDED_IP, "Bearer");
    }

    @Test
    @DisplayName("Chamada ao Google Places gera span de cliente filho do span HTTP e propaga traceparent, sem chave nem body")
    void googleClientSpanPropagatesContext() throws Exception {
        HttpResponse<String> response = send(authorized(api(
                "/api/v1/places/discovery/search?query=cafe-secreto&latitude=-23.5505&longitude=-46.6333")).GET());
        assertEquals(200, response.statusCode());

        SpanData server = awaitSpan(span -> span.getKind() == SpanKind.SERVER
                && "/api/v1/places/discovery/search".equals(attribute(span, "uri")));
        SpanData client = awaitSpan(span -> span.getKind() == SpanKind.CLIENT
                && span.getTraceId().equals(server.getTraceId()));
        assertEquals(server.getSpanId(), client.getParentSpanId());

        String traceparent = GOOGLE_TRACEPARENT.get();
        assertNotNull(traceparent, "o Google Places deve receber o header traceparent");
        assertTrue(traceparent.contains(server.getTraceId()));
        assertTrue(GOOGLE_BODY.get().contains("cafe-secreto"), "o stub recebeu a busca real");

        assertSpansFreeOf(GOOGLE_API_KEY, "cafe-secreto", "-23.5505", "-46.6333", "query=", accessToken);
    }

    @Test
    @DisplayName("Erro 500 inesperado gera um único log ERROR JSON com traceId do span e stack trace sem mensagens")
    void unexpectedErrorIsLoggedAsSanitizedJson(CapturedOutput output) throws Exception {
        HttpResponse<String> response = send(authorized(api(ProbeController.FAILURE_PATH))
                .header("User-Agent", USER_AGENT)
                .header("X-Forwarded-For", FORWARDED_IP)
                .GET());
        assertEquals(500, response.statusCode());
        assertTrue(response.body().contains("Erro Interno do Servidor"), "o JSON de erro HTTP não mudou");

        SpanData server = awaitSpan(span -> span.getKind() == SpanKind.SERVER
                && ProbeController.FAILURE_PATH.equals(attribute(span, "uri")));
        List<JsonNode> errors = awaitJsonLogs(output, log -> "ERROR".equals(log.path("level").asText())
                && log.path("logger_name").asText().endsWith("GlobalExceptionHandler"));
        assertEquals(1, errors.size());

        JsonNode error = errors.getFirst();
        for (String field : List.of("@timestamp", "level", "logger_name", "thread_name", "message", "traceId", "spanId", "stack_trace")) {
            assertTrue(error.hasNonNull(field), "campo obrigatório ausente: " + field);
        }
        assertEquals(server.getTraceId(), error.get("traceId").asText());
        assertTrue(error.get("message").asText().contains("java.lang.IllegalStateException"));
        assertTrue(error.get("stack_trace").asText().startsWith("java.lang.IllegalStateException"));
        assertTrue(error.get("stack_trace").asText().contains("ProbeController"));

        assertOutputFreeOf(output, EXCEPTION_SECRET, accessToken, refreshToken, userId, email, USER_AGENT, FORWARDED_IP,
                "Bearer ");
    }

    @Test
    @DisplayName("Rota inexistente não é erro do servidor: nenhum log ERROR, e a rota do span fica normalizada")
    void unknownRouteIsNotLoggedAsError(CapturedOutput output) throws Exception {
        String path = "/api/v1/rota-inexistente-" + UUID.randomUUID();
        HttpResponse<String> response = send(authorized(api(path)).GET());
        assertEquals(404, response.statusCode());
        assertTrue(response.body().contains("RESOURCE_NOT_FOUND"));
        // O handler de recursos estáticos casa "/**": a tag de rota não carrega o UUID do path
        SpanData span = awaitSpan(candidate -> candidate.getKind() == SpanKind.SERVER
                && path.equals(attribute(candidate, "http.url")));
        assertEquals("/**", attribute(span, "uri"));
        assertEquals("404", attribute(span, "status"));
        assertEquals("CLIENT_ERROR", attribute(span, "outcome"));

        assertTrue(jsonLogs(output, log -> "ERROR".equals(log.path("level").asText())).isEmpty());
    }

    @Test
    @DisplayName("Sem spans de JDBC, Spring Security ou @Scheduled: só HTTP servidor e cliente")
    void onlyHttpSpansAreCreated() throws Exception {
        assertEquals(200, send(authorized(api("/api/v1/auth/me")).GET()).statusCode());
        awaitSpan(span -> "/api/v1/auth/me".equals(attribute(span, "uri")));
        waitUntil(() -> scheduledProbe.runs.get() > 0);
        Thread.sleep(300);

        for (SpanData span : EXPORTER.spans) {
            assertTrue(span.getKind() == SpanKind.SERVER || span.getKind() == SpanKind.CLIENT,
                    "span inesperado: " + span.getName());
            assertTrue(span.getName().startsWith("http "), "span fora do escopo HTTP: " + span.getName());
        }
        assertNull(meterRegistry.find("tasks.scheduled.execution").meter(),
                "a observação de @Scheduled está desligada: nem span nem métrica");
    }

    private void assertSpansFreeOf(String... forbidden) {
        for (SpanData span : EXPORTER.spans) {
            String rendered = span.getName() + " " + span.getAttributes() + " " + span.getEvents();
            for (String value : forbidden) {
                assertFalse(rendered.contains(value), "span " + span.getName() + " contém dado proibido");
            }
        }
    }

    private static void assertOutputFreeOf(CapturedOutput output, String... forbidden) {
        String all = output.getAll();
        for (String value : forbidden) {
            assertFalse(all.contains(value), "log contém dado proibido");
        }
    }

    private List<JsonNode> awaitJsonLogs(CapturedOutput output, Predicate<JsonNode> filter) throws InterruptedException {
        List<JsonNode> found = List.of();
        for (int attempt = 0; attempt < 50 && found.isEmpty(); attempt++) {
            found = jsonLogs(output, filter);
            if (found.isEmpty()) {
                Thread.sleep(100);
            }
        }
        return found;
    }

    private List<JsonNode> jsonLogs(CapturedOutput output, Predicate<JsonNode> filter) {
        List<JsonNode> logs = new ArrayList<>();
        for (String line : output.getOut().split("\\R")) {
            if (!line.startsWith("{")) {
                continue;
            }
            try {
                JsonNode node = json.readTree(line);
                if (filter.test(node)) {
                    logs.add(node);
                }
            } catch (IOException e) {
                fail("linha de log não é JSON válido");
            }
        }
        return logs;
    }

    private SpanData awaitSpan(Predicate<SpanData> filter) throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            for (SpanData span : EXPORTER.spans) {
                if (filter.test(span)) {
                    return span;
                }
            }
            Thread.sleep(50);
        }
        return fail("span esperado não foi exportado: " + EXPORTER.spans.stream().map(span -> span.getName()).toList());
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        for (int attempt = 0; attempt < 100 && !condition.getAsBoolean(); attempt++) {
            Thread.sleep(50);
        }
        assertTrue(condition.getAsBoolean());
    }

    private static String attribute(SpanData span, String key) {
        return span.getAttributes().asMap().entrySet().stream()
                .filter(entry -> entry.getKey().getKey().equals(key))
                .map(entry -> String.valueOf(entry.getValue()))
                .findFirst()
                .orElse(null);
    }

    private HttpRequest.Builder authorized(URI uri) {
        return HttpRequest.newBuilder(uri).header("Authorization", "Bearer " + accessToken);
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws IOException, InterruptedException {
        return http.send(request.timeout(Duration.ofSeconds(20)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI api(String path) {
        return URI.create("http://127.0.0.1:" + apiPort + path);
    }

    private static HttpServer startGoogleStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                GOOGLE_TRACEPARENT.set(exchange.getRequestHeaders().getFirst("traceparent"));
                GOOGLE_BODY.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] body = "{\"places\":[]}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @TestConfiguration
    static class ObservabilityProbes {

        @Bean
        SpanExporter collectingSpanExporter() {
            return EXPORTER;
        }

        @Bean
        ProbeController probeController() {
            return new ProbeController();
        }

        @Bean
        ScheduledProbe scheduledProbe() {
            return new ScheduledProbe();
        }
    }

    @RestController
    static class ProbeController {

        static final String FAILURE_PATH = "/api/v1/observability-probe/failure";

        @GetMapping(FAILURE_PATH)
        String fail() {
            throw new IllegalStateException(EXCEPTION_SECRET + " " + FORWARDED_IP + " usuario@rewit.test");
        }
    }

    static class ScheduledProbe {

        final AtomicInteger runs = new AtomicInteger();

        @Scheduled(initialDelay = 0, fixedDelay = 86_400_000)
        void probe() {
            runs.incrementAndGet();
        }
    }

    static final class CollectingSpanExporter implements SpanExporter {

        final List<SpanData> spans = new CopyOnWriteArrayList<>();

        @Override
        public CompletableResultCode export(@Nonnull Collection<SpanData> batch) {
            spans.addAll(batch);
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }
    }
}
