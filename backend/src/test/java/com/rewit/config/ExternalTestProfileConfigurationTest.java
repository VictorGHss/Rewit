package com.rewit.config;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contrato do application-external-test.yml publicado pelo túnel: credenciais sem default, API e Actuator em
 * loopback, IP do cliente só de CF-Connecting-IP vindo do cloudflared local e documentação OpenAPI desligada.
 */
@DisplayName("Perfil external-test: contrato de configuração para publicação pelo Cloudflare Tunnel")
class ExternalTestProfileConfigurationTest {

    private static PropertySource<?> profile;

    @BeforeAll
    static void loadProfile() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("external-test", new ClassPathResource("application-external-test.yml"));
        assertEquals(1, sources.size());
        profile = sources.getFirst();
    }

    static String raw(String key) {
        Object value = profile.getProperty(key);
        return value == null ? null : value.toString();
    }

    /** Placeholder obrigatório: ${VAR} ou ${VAR:${OUTRA}}, nunca com valor literal de fallback. */
    private static final Pattern REQUIRED_PLACEHOLDER =
            Pattern.compile("^\\$\\{[A-Z][A-Z0-9_]*(?::\\$\\{[A-Z][A-Z0-9_]*\\})?\\}$");

    @ParameterizedTest
    @ValueSource(strings = {"spring.datasource.url", "spring.datasource.password", "spring.flyway.user",
            "spring.flyway.password", "spring.data.redis.host", "spring.data.redis.port", "rewit.minio.endpoint",
            "rewit.minio.access-key", "rewit.minio.secret-key", "rewit.minio.bucket-name", "rewit.security.jwt-secret",
            "rewit.rate-limit.key-secret"})
    @DisplayName("Credenciais e conexões vêm só do ambiente, sem valor padrão")
    void credentialsHaveNoDefaults(String key) {
        String value = raw(key);
        assertTrue(value != null && REQUIRED_PLACEHOLDER.matcher(value).matches(),
                key + " precisa ser um placeholder de ambiente sem valor literal de fallback");
    }

    @Test
    @DisplayName("Todas as credenciais exigidas pelo perfil são validadas na inicialização")
    void validatorCoversEveryRequiredProperty() {
        for (String key : ExternalTestEnvironmentValidator.requiredProperties()) {
            assertTrue(raw(key) != null, key + " é validada mas não está declarada no perfil");
        }
    }

    @Test
    @DisplayName("Schema só por Flyway com validação do Hibernate")
    void schemaIsValidatedNotGenerated() {
        assertEquals("true", raw("spring.flyway.enabled"));
        assertEquals("validate", raw("spring.jpa.hibernate.ddl-auto"));
    }

    @Test
    @DisplayName("API e Actuator escutam em loopback por padrão")
    void bindsToLoopback() {
        assertEquals("${SERVER_ADDRESS:127.0.0.1}", raw("server.address"));
        assertEquals("127.0.0.1", raw("management.server.address"));
        assertNull(raw("management.server.port"), "a porta de management segue a do perfil base (8081)");
    }

    @Test
    @DisplayName("Swagger UI e OpenAPI desligados")
    void openApiDisabled() {
        assertEquals("false", raw("springdoc.api-docs.enabled"));
        assertEquals("false", raw("springdoc.swagger-ui.enabled"));
    }

    @Test
    @DisplayName("IP do cliente: CF-Connecting-IP pelo RemoteIpValve, nunca X-Forwarded-For")
    void clientIpComesFromCloudflareHeader() {
        assertEquals("native", raw("server.forward-headers-strategy"));
        assertEquals("CF-Connecting-IP", raw("server.tomcat.remoteip.remote-ip-header"));
    }

    @Test
    @DisplayName("Proxy confiável é exatamente o loopback onde o cloudflared chega")
    void trustsOnlyLoopbackProxy() {
        Pattern internalProxies = Pattern.compile(raw("server.tomcat.remoteip.internal-proxies"));
        assertTrue(internalProxies.matcher("127.0.0.1").matches());
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.10", "127.0.0.2", "192.168.1.2", "192.168.65.254", "10.0.0.5", "172.17.0.1",
            "100.78.116.16", "::1", "0:0:0:0:0:0:0:1", "203.0.113.7"})
    @DisplayName("Rede local, Docker, Tailscale e internet não são proxies confiáveis")
    void doesNotTrustOtherPeers(String peer) {
        Pattern internalProxies = Pattern.compile(raw("server.tomcat.remoteip.internal-proxies"));
        assertFalse(internalProxies.matcher(peer).matches(), peer + " não pode definir o IP do cliente");
    }
}
