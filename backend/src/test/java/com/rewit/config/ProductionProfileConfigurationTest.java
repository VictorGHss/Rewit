package com.rewit.config;

import com.rewit.infrastructure.storage.MinioStorageAdapter;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.io.IOException;
import java.util.List;
import java.util.regex.Pattern;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("Contrato YAML do perfil prod, binding Redis TLS, CORS e comportamento S3 (F2-DEPLOY-03.3 / R1)")
class ProductionProfileConfigurationTest {

    private static PropertySource<?> baseConfig;
    private static PropertySource<?> prodProfile;

    private static final Pattern REQUIRED_PLACEHOLDER =
            Pattern.compile("^\\$\\{[A-Z][A-Z0-9_]*(?::\\$\\{[A-Z][A-Z0-9_]*\\})?\\}$");

    @BeforeAll
    static void loadConfigs() throws IOException {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> baseSources = loader.load("base", new ClassPathResource("application.yml"));
        assertEquals(1, baseSources.size());
        baseConfig = baseSources.getFirst();

        List<PropertySource<?>> prodSources = loader.load("prod", new ClassPathResource("application-prod.yml"));
        assertEquals(1, prodSources.size());
        prodProfile = prodSources.getFirst();
    }

    private static String rawProd(String key) {
        Object value = prodProfile.getProperty(key);
        return value == null ? null : value.toString();
    }

    @Test
    @DisplayName("application.yml não define fallback silencioso para profile local")
    void baseConfigHasNoSilentLocalProfileFallback() {
        assertNull(baseConfig.getProperty("spring.profiles.active"),
                "application.yml não pode definir spring.profiles.active com default local");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "spring.datasource.url",
            "spring.datasource.username",
            "spring.datasource.password",
            "rewit.datasource.media.username",
            "rewit.datasource.media.password",
            "rewit.datasource.moderation.username",
            "rewit.datasource.moderation.password",
            "spring.flyway.user",
            "spring.flyway.password",
            "spring.data.redis.host",
            "spring.data.redis.port",
            "spring.data.redis.password",
            "rewit.minio.endpoint",
            "rewit.minio.access-key",
            "rewit.minio.secret-key",
            "rewit.minio.bucket-name",
            "rewit.security.jwt-secret",
            "rewit.security.password-pepper",
            "rewit.security.refresh-token-secret",
            "rewit.security.moderation-audit-secret",
            "rewit.rate-limit.key-secret"
    })
    @DisplayName("25. Ausência de fallback para banco, Redis, S3 ou segredos em application-prod.yml")
    void prodPropertiesHaveNoLocalFallback(String key) {
        String value = rawProd(key);
        assertTrue(value != null && REQUIRED_PLACEHOLDER.matcher(value).matches(),
                key + " em application-prod.yml deve vir exclusivamente do ambiente sem valor default: " + value);
    }

    @Test
    @DisplayName("23. Swagger UI e OpenAPI desabilitados em prod")
    void swaggerDisabledInProd() {
        assertEquals("false", rawProd("springdoc.api-docs.enabled"));
        assertEquals("false", rawProd("springdoc.swagger-ui.enabled"));
    }

    @Test
    @DisplayName("24 & AUDIT-07. Mensagens detalhadas, binding errors, stacktrace e exception desabilitados em prod")
    void detailedErrorsAndStacktraceDisabledInProd() {
        assertEquals("never", rawProd("spring.web.error.include-message"));
        assertEquals("never", rawProd("spring.web.error.include-binding-errors"));
        assertEquals("never", rawProd("spring.web.error.include-stacktrace"));
        assertEquals("false", rawProd("spring.web.error.include-exception"));
    }

    @Test
    @DisplayName("Persistência, Flyway, Redis TLS e gestão seguem contrato de produção")
    void persistenceRedisAndManagementContract() {
        assertEquals("true", rawProd("spring.flyway.enabled"));
        assertEquals("false", rawProd("spring.flyway.baseline-on-migrate"));
        assertEquals("false", rawProd("spring.flyway.postgresql.transactional-lock"));
        assertEquals("validate", rawProd("spring.jpa.hibernate.ddl-auto"));
        assertEquals("false", rawProd("spring.jpa.show-sql"));
        assertEquals("${REDIS_SSL_ENABLED:true}", rawProd("spring.data.redis.ssl.enabled"));
        assertEquals("${OBJECT_STORAGE_AUTO_CREATE_BUCKET:false}", rawProd("rewit.minio.auto-create-bucket"));
        assertEquals("${OBJECT_STORAGE_REGION:}", rawProd("rewit.minio.region"));
        assertEquals("${MANAGEMENT_SERVER_ADDRESS:127.0.0.1}", rawProd("management.server.address"));
    }

    @Test
    @DisplayName("F2-DEPLOY-03.4. Contrato YAML de liveness/readiness, show-details=never, additional paths e Docker HEALTHCHECK")
    void healthProbesContractInBaseProdAndDockerfile() throws IOException {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        PropertySource<?> mainBaseConfig = loader.load(
                "main-base", new org.springframework.core.io.FileSystemResource("src/main/resources/application.yml")
        ).getFirst();

        for (PropertySource<?> source : List.of(mainBaseConfig, prodProfile)) {
            assertEquals("never", String.valueOf(source.getProperty("management.endpoint.health.show-details")));
            assertEquals("never", String.valueOf(source.getProperty("management.endpoint.health.show-components")));
            assertEquals("true", String.valueOf(source.getProperty("management.endpoint.health.probes.enabled")));
            assertEquals("true", String.valueOf(source.getProperty("management.endpoint.health.probes.add-additional-paths")));
            assertEquals("livenessState", String.valueOf(source.getProperty("management.endpoint.health.group.liveness.include")));
            assertEquals("readinessState,db,redis,objectStorage",
                    String.valueOf(source.getProperty("management.endpoint.health.group.readiness.include")));
            assertEquals("never", String.valueOf(source.getProperty("management.endpoint.health.group.liveness.show-details")));
            assertEquals("never", String.valueOf(source.getProperty("management.endpoint.health.group.readiness.show-details")));
            assertEquals("DOWN,OUT_OF_SERVICE,UNKNOWN,UP",
                    String.valueOf(source.getProperty("management.endpoint.health.group.readiness.status.order")));
            assertEquals("503",
                    String.valueOf(source.getProperty("management.endpoint.health.group.readiness.status.http-mapping.DOWN")));
            assertEquals("503",
                    String.valueOf(source.getProperty("management.endpoint.health.group.readiness.status.http-mapping.OUT_OF_SERVICE")));
            assertEquals("503",
                    String.valueOf(source.getProperty("management.endpoint.health.group.readiness.status.http-mapping.UNKNOWN")));
        }

        assertEquals("read_only", rawProd("management.endpoint.health.access"));
        assertEquals("true", rawProd("management.health.defaults.enabled"));
        assertEquals("true", rawProd("management.health.livenessstate.enabled"));
        assertEquals("true", rawProd("management.health.readinessstate.enabled"));
        assertEquals("true", rawProd("management.health.db.enabled"));
        assertEquals("true", rawProd("management.health.db.ignore-routing-data-sources"));
        assertEquals("true", String.valueOf(mainBaseConfig.getProperty("management.health.db.ignore-routing-data-sources")));
        assertEquals("true", rawProd("management.health.redis.enabled"));
        assertEquals("true", rawProd("management.health.object-storage.enabled"));
        assertEquals("${RATE_LIMIT_ENABLED:true}", rawProd("rewit.rate-limit.enabled"));
        assertEquals("${RATE_LIMIT_BACKEND_FAILURE_MODE:LOCAL_FALLBACK}",
                rawProd("rewit.rate-limit.backend.failure-mode"));

        java.nio.file.Path dockerfile = java.nio.file.Path.of("../infrastructure/docker/backend.Dockerfile");
        assertTrue(java.nio.file.Files.exists(dockerfile), "backend.Dockerfile deve existir");
        String content = java.nio.file.Files.readString(dockerfile);
        assertTrue(content.contains("/actuator/health/readiness"),
                "Docker HEALTHCHECK deve consultar /actuator/health/readiness na porta de management");
        assertTrue(content.contains("MANAGEMENT_SERVER_PORT:-8081"),
                "Docker HEALTHCHECK deve preservar a porta de management");
        assertTrue(content.contains("--timeout=10s") && content.contains("--max-time 8"),
                "Orçamento de timeout do Docker HEALTHCHECK (10s / curl 8s) deve comportar pior caso S3 sem região (2x3s)");
    }

    @Test
    @DisplayName("AUDIT-04. Propriedades Redis aprovadas pelo validador vinculam TLS efetivamente em DataRedisProperties")
    void validatedRedisConfigBindsEffectiveTlsProperties() {
        MockEnvironment env = ProductionEnvironmentValidatorTest.validProdEnvironment();
        ProductionEnvironmentValidator.validate(env);

        DataRedisProperties bound = Binder.get(env)
                .bind("spring.data.redis", DataRedisProperties.class)
                .get();
        assertEquals("redis.rewit.internal", bound.getHost());
        assertEquals(6380, bound.getPort());
        assertTrue(bound.getSsl().isEnabled(), "TLS deve estar efetivamente habilitado no cliente Redis");
        assertNotNull(bound.getPassword());
    }

    @Test
    @DisplayName("AUDIT-05. CORS conectado ao SecurityConfig: restritivo vazio, funcional para origem HTTPS explícita e rejeita wildcard")
    void corsConfigurationSourceWiring() {
        SecurityConfig emptyCors = new SecurityConfig(
                "prod-jwt-secret-entropia-csprng-Zp4!Qw8@Lk2#9x",
                "prod-pepper-secret-entropia-csprng-Rm7!Vx2@Np9#", "",
                "prod-refresh-secret-entropia-csprng-Tk5!Bj8@Wd3#", "",
                "prod-mod-audit-secret-entropia-csprng-Kp9!Zx4@Qc1#", "",
                "", "", "", "", "rewit-api", "rewit-clients", ""
        );
        MockHttpServletRequest apiRequest = new MockHttpServletRequest("OPTIONS", "/api/v1/auth/login");
        assertNull(emptyCors.corsConfigurationSource().getCorsConfiguration(apiRequest),
                "Sem CORS_ALLOWED_ORIGINS nenhuma origem cross-origin deve ser liberada");

        SecurityConfig explicitCors = new SecurityConfig(
                "prod-jwt-secret-entropia-csprng-Zp4!Qw8@Lk2#9x",
                "prod-pepper-secret-entropia-csprng-Rm7!Vx2@Np9#", "",
                "prod-refresh-secret-entropia-csprng-Tk5!Bj8@Wd3#", "",
                "prod-mod-audit-secret-entropia-csprng-Kp9!Zx4@Qc1#", "",
                "", "", "", "", "rewit-api", "rewit-clients", "https://admin.rewit.app"
        );
        CorsConfigurationSource source = explicitCors.corsConfigurationSource();
        CorsConfiguration config = source.getCorsConfiguration(apiRequest);
        assertNotNull(config);
        assertEquals("https://admin.rewit.app", config.checkOrigin("https://admin.rewit.app"));
        assertNull(config.checkOrigin("https://evil.example.com"));
        assertEquals(Boolean.FALSE, config.getAllowCredentials());
        assertTrue(config.getAllowedMethods().containsAll(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")));
        assertTrue(config.getAllowedHeaders().containsAll(List.of("Authorization", "Content-Type", "Accept")));

        assertThrows(IllegalArgumentException.class, () -> new SecurityConfig(
                "prod-jwt-secret-entropia-csprng-Zp4!Qw8@Lk2#9x",
                "prod-pepper-secret-entropia-csprng-Rm7!Vx2@Np9#", "",
                "prod-refresh-secret-entropia-csprng-Tk5!Bj8@Wd3#", "",
                "prod-mod-audit-secret-entropia-csprng-Kp9!Zx4@Qc1#", "",
                "", "", "", "", "rewit-api", "rewit-clients", "*"
        ));

        for (String badPortOrigin : new String[]{
                "https://admin.rewit.app:0",
                "https://admin.rewit.app:99999",
                "https://admin.rewit.app:"
        }) {
            assertThrows(IllegalArgumentException.class, () -> new SecurityConfig(
                    "prod-jwt-secret-entropia-csprng-Zp4!Qw8@Lk2#9x",
                    "prod-pepper-secret-entropia-csprng-Rm7!Vx2@Np9#", "",
                    "prod-refresh-secret-entropia-csprng-Tk5!Bj8@Wd3#", "",
                    "prod-mod-audit-secret-entropia-csprng-Kp9!Zx4@Qc1#", "",
                    "", "", "", "", "rewit-api", "rewit-clients", badPortOrigin
            ), "SecurityConfig deve rejeitar porta inválida em CORS: " + badPortOrigin);
        }
    }

    @Test
    @DisplayName("R1-06. CORS atravessa a SecurityFilterChain real via MockMvc (preflight, GET/POST, headers, allowCredentials=false e lista vazia)")
    void corsEnforcedThroughRealSecurityFilterChainWithMockMvc() throws Exception {
        try (AnnotationConfigWebApplicationContext allowedCtx = createSecurityWebContext("https://admin.rewit.app:8443")) {
            MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(allowedCtx)
                    .apply(SecurityMockMvcConfigurers.springSecurity())
                    .build();

            // 1. Preflight OPTIONS para origem permitida (com Authorization e Content-Type)
            mockMvc.perform(options("/api/v1/me")
                            .header(HttpHeaders.ORIGIN, "https://admin.rewit.app:8443")
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization, Content-Type"))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://admin.rewit.app:8443"))
                    .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, containsString("Authorization")))
                    .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, containsString("Content-Type")))
                    .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, nullValue()));

            // 2. Preflight OPTIONS para origem externa não permitida -> rejeitado (403, sem ACAO)
            mockMvc.perform(options("/api/v1/me")
                            .header(HttpHeaders.ORIGIN, "https://evil.example.com")
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization"))
                    .andExpect(status().isForbidden())
                    .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));

            // 3. GET real autenticado com origem permitida -> 200 OK com ACAO e sem allowCredentials
            mockMvc.perform(get("/api/v1/me")
                            .with(SecurityMockMvcRequestPostProcessors.jwt())
                            .header(HttpHeaders.ORIGIN, "https://admin.rewit.app:8443")
                            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://admin.rewit.app:8443"))
                    .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));

            // 4. GET real com origem rejeitada -> 403 Forbidden sem ACAO
            mockMvc.perform(get("/api/v1/me")
                            .with(SecurityMockMvcRequestPostProcessors.jwt())
                            .header(HttpHeaders.ORIGIN, "https://evil.example.com"))
                    .andExpect(status().isForbidden())
                    .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));

            // 5. POST público com Origin permitido + Content-Type -> 200 OK com ACAO
            mockMvc.perform(post("/api/v1/auth/login")
                            .header(HttpHeaders.ORIGIN, "https://admin.rewit.app:8443")
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://admin.rewit.app:8443"))
                    .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
        }

        // 6. Lista vazia de origens: nenhuma origem cross-origin recebe Access-Control-Allow-Origin
        try (AnnotationConfigWebApplicationContext emptyCtx = createSecurityWebContext("")) {
            MockMvc emptyMvc = MockMvcBuilders.webAppContextSetup(emptyCtx)
                    .apply(SecurityMockMvcConfigurers.springSecurity())
                    .build();

            emptyMvc.perform(options("/api/v1/me")
                            .header(HttpHeaders.ORIGIN, "https://admin.rewit.app")
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                    .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));

            emptyMvc.perform(get("/api/v1/me")
                            .with(SecurityMockMvcRequestPostProcessors.jwt())
                            .header(HttpHeaders.ORIGIN, "https://admin.rewit.app"))
                    .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        }
    }

    private static AnnotationConfigWebApplicationContext createSecurityWebContext(String allowedOrigins) {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("rewit.security.jwt-secret", "prod-jwt-secret-entropia-csprng-Zp4!Qw8@Lk2#9x");
        env.setProperty("rewit.security.password-pepper", "prod-pepper-secret-entropia-csprng-Rm7!Vx2@Np9#");
        env.setProperty("rewit.security.refresh-token-secret", "prod-refresh-secret-entropia-csprng-Tk5!Bj8@Wd3#");
        env.setProperty("rewit.security.moderation-audit-secret", "prod-mod-audit-secret-entropia-csprng-Kp9!Zx4@Qc1#");
        env.setProperty("rewit.security.cors.allowed-origins", allowedOrigins);

        AnnotationConfigWebApplicationContext ctx = new AnnotationConfigWebApplicationContext();
        ctx.setServletContext(new MockServletContext());
        ctx.setEnvironment(env);
        ctx.register(CorsFilterTestConfig.class, SecurityConfig.class);
        ctx.refresh();
        return ctx;
    }

    @Configuration
    @EnableWebMvc
    static class CorsFilterTestConfig {
        @Bean
        CorsProbeController corsProbeController() {
            return new CorsProbeController();
        }
    }

    @RestController
    static class CorsProbeController {
        @GetMapping("/api/v1/me")
        String me() {
            return "ok";
        }

        @PostMapping("/api/v1/auth/login")
        String login() {
            return "ok";
        }
    }

    @Test
    @DisplayName("20 & 22. MinioConfig carrega OBJECT_STORAGE_REGION corretamente e recusa credenciais ausentes")
    void minioConfigLoadsRegionAndRejectsBlankCredentials() {
        MinioConfig valid = new MinioConfig(
                "https://s3.us-east-1.amazonaws.com",
                "s3-prod-access-key-fixture-001",
                "super-secret-prod-storage-key",
                "us-east-1"
        );
        assertEquals("us-east-1", valid.getRegion());
        assertNotNull(valid.minioClient());

        assertThrows(IllegalStateException.class,
                () -> new MinioConfig("", "access", "secret", "us-east-1"));
        assertThrows(IllegalStateException.class,
                () -> new MinioConfig("https://s3.us-east-1.amazonaws.com", "", "secret", "us-east-1"));
        assertThrows(IllegalStateException.class,
                () -> new MinioConfig("https://s3.us-east-1.amazonaws.com", "access", "", "us-east-1"));
    }

    @Test
    @DisplayName("21. MinioStorageAdapter com auto-create-bucket=false nunca chama bucketExists nem makeBucket")
    void storageAdapterWithAutoCreateDisabledNeverCreatesBucket() throws Exception {
        MinioClient minioClient = mock(MinioClient.class);
        MinioStorageAdapter adapter = new MinioStorageAdapter(minioClient, "rewit-prod-media", false);

        assertFalse(adapter.isAutoCreateBucket());
        adapter.put("reviews/1/photo.jpg", "image/jpeg", new byte[]{1, 2, 3});

        verify(minioClient, never()).bucketExists(any(BucketExistsArgs.class));
        verify(minioClient, never()).makeBucket(any(MakeBucketArgs.class));
        verify(minioClient).putObject(any(PutObjectArgs.class));
    }
}
