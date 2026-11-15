package com.rewit.infrastructure.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewit.application.port.AuthSessionRepository;
import com.rewit.application.port.TokenService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * IP do cliente gravado na sessão atrás do Cloudflare Tunnel, por HTTP real (o RemoteIpValve só existe no Tomcat, não
 * no MockMvc). O servidor sobe com as propriedades de proxy e de OpenAPI lidas do próprio application-external-test.yml;
 * o cliente conecta por 127.0.0.1, o mesmo peer que o cloudflared apresenta via host.docker.internal.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
@DisplayName("Testes de Integração: IP do cliente e OpenAPI no perfil external-test (Cloudflare Tunnel)")
class ClientIpResolutionIntegrationTest {

    private static final List<String> EXTERNAL_TEST_KEYS = List.of(
            "server.forward-headers-strategy",
            "server.tomcat.remoteip.remote-ip-header",
            "server.tomcat.remoteip.internal-proxies",
            "springdoc.api-docs.enabled",
            "springdoc.swagger-ui.enabled"
    );

    private static final String LOOPBACK = "127.0.0.1";

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Autowired
    private AuthSessionRepository authSessionRepository;

    @Autowired
    private TokenService tokenService;

    @DynamicPropertySource
    static void externalTestProxyProperties(DynamicPropertyRegistry registry) throws IOException {
        PropertySource<?> profile = new YamlPropertySourceLoader()
                .load("external-test", new ClassPathResource("application-external-test.yml")).getFirst();
        for (String key : EXTERNAL_TEST_KEYS) {
            String value = String.valueOf(profile.getProperty(key));
            registry.add(key, () -> value);
        }
    }

    private URI api(String path) {
        return URI.create("http://" + LOOPBACK + ":" + port + path);
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Registra uma conta com os headers dados e devolve o IP gravado na sessão criada. */
    private String registerAndGetSessionIp(String... headers) throws Exception {
        String handle = "ip" + UUID.randomUUID().toString().substring(0, 8);
        HttpRequest.Builder request = HttpRequest.newBuilder(api("/api/v1/auth/register"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"email":"%s@rewit.test","password":"Sonda-Forte-123!","handle":"%s","displayName":"Sonda IP"}
                        """.formatted(handle, handle)));
        if (headers.length > 0) {
            request.headers(headers);
        }
        HttpResponse<String> response = send(request);
        assertEquals(201, response.statusCode(), response.body());
        return sessionIp(json.readTree(response.body()));
    }

    private String sessionIp(JsonNode authResponse) {
        String tokenHash = tokenService.hashRefreshToken(authResponse.get("refreshToken").asText());
        return authSessionRepository.findByTokenHash(tokenHash).orElseThrow().getIpAddress();
    }

    @Test
    @DisplayName("CF-Connecting-IP vindo do proxy local confiável vira o IP da sessão, no cadastro e no login")
    void cloudflareHeaderFromTrustedProxyIsRecorded() throws Exception {
        assertEquals("198.51.100.23", registerAndGetSessionIp("CF-Connecting-IP", "198.51.100.23"));

        String handle = "ip" + UUID.randomUUID().toString().substring(0, 8);
        String email = handle + "@rewit.test";
        assertEquals(201, send(HttpRequest.newBuilder(api("/api/v1/auth/register"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"email":"%s","password":"Sonda-Forte-123!","handle":"%s","displayName":"Sonda IP"}
                        """.formatted(email, handle)))).statusCode());

        HttpResponse<String> login = send(HttpRequest.newBuilder(api("/api/v1/auth/login"))
                .header("Content-Type", "application/json")
                .header("CF-Connecting-IP", "2001:db8::42")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"email":"%s","password":"Sonda-Forte-123!"}
                        """.formatted(email))));
        assertEquals(200, login.statusCode(), login.body());
        assertEquals("2001:db8::42", sessionIp(json.readTree(login.body())));
    }

    @Test
    @DisplayName("X-Forwarded-For enviado pelo cliente é ignorado: vale o peer da conexão")
    void forwardedForIsIgnored() throws Exception {
        assertEquals(LOOPBACK, registerAndGetSessionIp("X-Forwarded-For", "203.0.113.7"));
    }

    @Test
    @DisplayName("X-Forwarded-For forjado junto do CF-Connecting-IP não substitui o IP definido pela Cloudflare")
    void forgedForwardedForDoesNotOverrideCloudflareHeader() throws Exception {
        assertEquals("198.51.100.24", registerAndGetSessionIp(
                "X-Forwarded-For", "203.0.113.7, 198.51.100.24",
                "CF-Connecting-IP", "198.51.100.24"));
        assertNotEquals("203.0.113.7", registerAndGetSessionIp(
                "X-Forwarded-For", "203.0.113.7",
                "CF-Connecting-IP", "198.51.100.25"));
    }

    @Test
    @DisplayName("X-Forwarded-For malformado não chega à coluna INET nem quebra o cadastro")
    void malformedForwardedForDoesNotBreakAuthentication() throws Exception {
        assertEquals(LOOPBACK, registerAndGetSessionIp("X-Forwarded-For", "nao-e-um-ip"));
    }

    @Test
    @DisplayName("OpenAPI e Swagger UI não são servidos com a configuração do perfil")
    void openApiIsNotServed() throws Exception {
        for (String path : List.of("/v3/api-docs", "/api-docs", "/swagger-ui.html", "/swagger-ui/index.html")) {
            HttpResponse<String> response = send(HttpRequest.newBuilder(api(path)).GET());
            assertNotEquals(200, response.statusCode(), path + " não pode responder 200");
            assertFalse(response.body().contains("\"openapi\""), path + " não pode expor o documento OpenAPI");
        }
    }
}
