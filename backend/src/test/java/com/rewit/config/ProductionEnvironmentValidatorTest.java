package com.rewit.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.Driver;
import org.springframework.boot.autoconfigure.web.ErrorProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Validação fail-closed do perfil prod (F2-DEPLOY-03.3 / R1)")
class ProductionEnvironmentValidatorTest {

    private static final String APP_DB_PASS = "app-prod-pass-Xq7#kP2v!9z";
    private static final String MEDIA_DB_PASS = "media-prod-pass-Kp4!xV7s#3w";
    private static final String MOD_DB_PASS = "mod-prod-pass-Yr8#mN4q!6t";
    private static final String FLYWAY_DB_PASS = "flyway-prod-pass-Lw3!rT9d#5y";
    private static final String REDIS_PASS = "redis-prod-pass-Bn2Hj8k4m";
    private static final String STORAGE_SECRET = "storage-prod-secret-9fT2mQ8z!7p";

    private static final String JWT_SECRET = "prod-jwt-secret-entropia-csprng-Zp4!Qw8@Lk2#9x";
    private static final String PEPPER_SECRET = "prod-pepper-secret-entropia-csprng-Rm7!Vx2@Np9#";
    private static final String REFRESH_SECRET = "prod-refresh-secret-entropia-csprng-Tk5!Bj8@Wd3#";
    private static final String MOD_AUDIT_SECRET = "prod-mod-audit-secret-entropia-csprng-Kp9!Zx4@Qc1#";
    private static final String RATE_LIMIT_SECRET = "prod-rate-limit-secret-entropia-Hy6$Vb3%Nm1&8z";
    private static final String EMAIL_RES_SECRET = "prod-email-reservation-secret-csprng-Gf2!Rt7@Jk4#";

    static MockEnvironment validProdEnvironment() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(ProductionEnvironmentValidator.PROFILE);
        env.setProperty("rewit.environment", "production");

        // HTTP & runtime hardening
        env.setProperty("server.port", "8080");
        env.setProperty("management.server.port", "8081");
        env.setProperty("spring.web.error.include-message", "never");
        env.setProperty("spring.web.error.include-binding-errors", "never");
        env.setProperty("spring.web.error.include-stacktrace", "never");
        env.setProperty("spring.web.error.include-exception", "false");
        env.setProperty("springdoc.api-docs.enabled", "false");
        env.setProperty("springdoc.swagger-ui.enabled", "false");
        env.setProperty("spring.jpa.show-sql", "false");
        env.setProperty("spring.jpa.hibernate.ddl-auto", "validate");
        env.setProperty("server.forward-headers-strategy", "native");
        env.setProperty("server.tomcat.remoteip.internal-proxies", "10\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");

        // Database & Flyway (TLS verify-full)
        env.setProperty("spring.datasource.url", "jdbc:postgresql://db.rewit.internal:5432/rewit_prod?sslmode=verify-full");
        env.setProperty("spring.datasource.username", "rewit_app");
        env.setProperty("spring.datasource.password", APP_DB_PASS);
        env.setProperty("rewit.datasource.media.username", "rewit_media");
        env.setProperty("rewit.datasource.media.password", MEDIA_DB_PASS);
        env.setProperty("rewit.datasource.moderation.username", "rewit_mod");
        env.setProperty("rewit.datasource.moderation.password", MOD_DB_PASS);
        env.setProperty("spring.flyway.enabled", "true");
        env.setProperty("spring.flyway.user", "rewit_migrator");
        env.setProperty("spring.flyway.password", FLYWAY_DB_PASS);
        env.setProperty("spring.flyway.baseline-on-migrate", "false");
        env.setProperty("spring.flyway.postgresql.transactional-lock", "false");

        // Cryptographic secrets
        env.setProperty("rewit.security.jwt-secret", JWT_SECRET);
        env.setProperty("rewit.security.password-pepper", PEPPER_SECRET);
        env.setProperty("rewit.security.password-pepper-key-id", "v1");
        env.setProperty("rewit.security.refresh-token-secret", REFRESH_SECRET);
        env.setProperty("rewit.security.moderation-audit-secret", MOD_AUDIT_SECRET);
        env.setProperty("rewit.security.moderation-audit-key-id", "v1");
        env.setProperty("rewit.rate-limit.enabled", "true");
        env.setProperty("rewit.rate-limit.key-secret", RATE_LIMIT_SECRET);
        env.setProperty("rewit.account.purge.enabled", "true");
        env.setProperty("rewit.account.email-reservation-secret", EMAIL_RES_SECRET);

        // Redis TLS
        env.setProperty("spring.data.redis.host", "redis.rewit.internal");
        env.setProperty("spring.data.redis.port", "6380");
        env.setProperty("spring.data.redis.password", REDIS_PASS);
        env.setProperty("spring.data.redis.ssl.enabled", "true");

        // Object Storage HTTPS
        env.setProperty("rewit.minio.endpoint", "https://s3.us-east-1.amazonaws.com");
        env.setProperty("rewit.minio.access-key", "AKIAIOSFODNN7PRODKEY");
        env.setProperty("rewit.minio.secret-key", STORAGE_SECRET);
        env.setProperty("rewit.minio.bucket-name", "rewit-prod-media");
        env.setProperty("rewit.minio.region", "us-east-1");
        env.setProperty("rewit.minio.auto-create-bucket", "false");

        return env;
    }

    @Test
    @DisplayName("Configuração completa e válida de produção é aceita e coincide com Driver.parseURL")
    void acceptsValidProductionEnvironmentAndMatchesPostgresDriver() {
        MockEnvironment env = validProdEnvironment();
        assertDoesNotThrow(() -> ProductionEnvironmentValidator.validate(env));

        Properties parsed = Driver.parseURL(env.getProperty("spring.datasource.url"), new Properties());
        assertNotNull(parsed);
        assertEquals("verify-full", parsed.getProperty("sslmode"));
    }

    @Test
    @DisplayName("Fora do perfil prod, o validador não executa")
    void ignoresNonProdProfiles() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("local");
        assertDoesNotThrow(() -> ProductionEnvironmentValidator.validate(env));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "rewit.security.jwt-secret",
            "rewit.security.password-pepper",
            "rewit.security.refresh-token-secret",
            "rewit.security.moderation-audit-secret",
            "rewit.security.moderation-audit-key-id",
            "rewit.rate-limit.key-secret"
    })
    @DisplayName("7. Ausência de cada segredo obrigatório falha")
    void rejectsMissingRequiredSecret(String property) {
        MockEnvironment env = validProdEnvironment();
        env.setProperty(property, "");

        assertThrows(ProductionEnvironmentValidator.InvalidConfigurationException.class,
                () -> ProductionEnvironmentValidator.validate(env));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "change-me", "changeme", "rewit_local_secret", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
    @DisplayName("8. Valores vazios, placeholders inseguros e baixa entropia são rejeitados")
    void rejectsInsecureSecretValues(String badSecret) {
        MockEnvironment env = validProdEnvironment();
        env.setProperty("rewit.security.jwt-secret", badSecret);

        assertThrows(ProductionEnvironmentValidator.InvalidConfigurationException.class,
                () -> ProductionEnvironmentValidator.validate(env));
    }

    @Test
    @DisplayName("9. Segredos reutilizados entre propósitos ou iguais a senhas de banco são rejeitados")
    void rejectsReusedSecrets() {
        MockEnvironment reusedBetweenSecrets = validProdEnvironment();
        reusedBetweenSecrets.setProperty("rewit.security.refresh-token-secret", JWT_SECRET);
        assertThrows(ProductionEnvironmentValidator.InvalidConfigurationException.class,
                () -> ProductionEnvironmentValidator.validate(reusedBetweenSecrets));

        MockEnvironment reusedWithEmailRes = validProdEnvironment();
        reusedWithEmailRes.setProperty("rewit.account.email-reservation-secret", RATE_LIMIT_SECRET);
        assertThrows(ProductionEnvironmentValidator.InvalidConfigurationException.class,
                () -> ProductionEnvironmentValidator.validate(reusedWithEmailRes));

        MockEnvironment reusedWithDbPass = validProdEnvironment();
        reusedWithDbPass.setProperty("spring.datasource.password", MOD_AUDIT_SECRET);
        assertThrows(ProductionEnvironmentValidator.InvalidConfigurationException.class,
                () -> ProductionEnvironmentValidator.validate(reusedWithDbPass));
    }

    @Test
    @DisplayName("9b. Chaves de rotação incompletas ou com key ID duplicado são rejeitadas")
    void validatesRotationPairs() {
        MockEnvironment missingPrevKeyId = validProdEnvironment();
        missingPrevKeyId.setProperty("rewit.security.moderation-audit-secret-previous",
                "prod-prev-mod-audit-secret-csprng-Xy1!Za2@Bc3#4d");
        assertThrows(ProductionEnvironmentValidator.InvalidConfigurationException.class,
                () -> ProductionEnvironmentValidator.validate(missingPrevKeyId));

        MockEnvironment sameKeyId = validProdEnvironment();
        sameKeyId.setProperty("rewit.security.moderation-audit-secret-previous",
                "prod-prev-mod-audit-secret-csprng-Xy1!Za2@Bc3#4d");
        sameKeyId.setProperty("rewit.security.moderation-audit-previous-key-id", "v1");
        assertThrows(ProductionEnvironmentValidator.InvalidConfigurationException.class,
                () -> ProductionEnvironmentValidator.validate(sameKeyId));

        MockEnvironment validRotation = validProdEnvironment();
        validRotation.setProperty("rewit.security.moderation-audit-secret-previous",
                "prod-prev-mod-audit-secret-csprng-Xy1!Za2@Bc3#4d");
        validRotation.setProperty("rewit.security.moderation-audit-previous-key-id", "v0");
        assertDoesNotThrow(() -> ProductionEnvironmentValidator.validate(validRotation));
    }

    @Test
    @DisplayName("10 & 11. ACCOUNT_EMAIL_RESERVATION_SECRET obrigatório com purge ligado e opcional se desligado")
    void validatesEmailReservationSecretConditionallyOnPurge() {
        MockEnvironment enabledMissing = validProdEnvironment();
        enabledMissing.setProperty("rewit.account.purge.enabled", "true");
        enabledMissing.setProperty("rewit.account.email-reservation-secret", "");
        ProductionEnvironmentValidator.InvalidConfigurationException ex = assertThrows(
                ProductionEnvironmentValidator.InvalidConfigurationException.class,
                () -> ProductionEnvironmentValidator.validate(enabledMissing)
        );
        assertTrue(ex.getMessage().contains("ACCOUNT_EMAIL_RESERVATION_SECRET"));

        MockEnvironment disabledMissing = validProdEnvironment();
        disabledMissing.setProperty("rewit.account.purge.enabled", "false");
        disabledMissing.setProperty("rewit.account.email-reservation-secret", "");
        assertDoesNotThrow(() -> ProductionEnvironmentValidator.validate(disabledMissing));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "jdbc:postgresql://db.rewit.internal:5432/rewit_prod",
            "jdbc:postgresql://db.rewit.internal:5432/rewit_prod?sslmode=disable",
            "jdbc:postgresql://db.rewit.internal:5432/rewit_prod?sslmode=prefer",
            "jdbc:postgresql://db.rewit.internal:5432/rewit_prod?sslmode=require",
            "jdbc:postgresql://db.rewit.internal:5432/rewit_prod?sslmode=verify-ca",
            "jdbc:postgresql://db.rewit.internal:5432/rewit_prod?sslMode=verify-full",
            "jdbc:postgresql://db.rewit.internal:5432/rewit_prod?SSLMODE=verify-full",
            "jdbc:postgresql://db.rewit.internal:5432/rewit_prod?sslmode=disable&sslMode=verify-full",
            "jdbc:postgresql://db.rewit.internal:5432/rewit_prod?sslmode=verify-full&sslmode=disable",
            "jdbc:postgresql://db.rewit.internal:5432/rewit_prod?%73slmode=verify-full",
            "jdbc:postgresql://db.rewit.internal:5432/rewit_prod?sslmode=verify-full&%73slmode=verify-full",
            "jdbc:postgresql://db.rewit.internal:5432/rewit_prod?sslmode=verify-full&ssl=false",
            "jdbc:postgresql://db.rewit.internal:5432/rewit_prod?ssl=false&sslmode=verify-full",
            "jdbc:postgresql://db.rewit.internal:5432/rewit_prod?SSL=false&sslmode=verify-full",
            "jdbc:postgresql://db.rewit.internal:5432/rewit_prod?sslmode=",
            "jdbc:postgresql://user:pass@db.rewit.internal:5432/rewit_prod?sslmode=verify-full",
            "jdbc:postgresql://localhost:5432/rewit_prod?sslmode=verify-full",
            "jdbc:postgresql://[::1]:5432/rewit_prod?sslmode=verify-full"
    })
    @DisplayName("AUDIT-03: Rejeita qualquer variação insegura, case-insensitive, duplicada ou downgrade de sslmode no PostgreSQL")
    void rejectsAllAdversarialPostgresTlsUrls(String badUrl) {
        MockEnvironment env = validProdEnvironment();
        env.setProperty("spring.datasource.url", badUrl);
        assertThrows(ProductionEnvironmentValidator.InvalidConfigurationException.class,
                () -> ProductionEnvironmentValidator.validate(env),
                "URL deve ser rejeitada: " + badUrl);
    }

    @Test
    @DisplayName("AUDIT-02: Validação independente de spring.flyway.url (TLS, host, database, userinfo e coerência com datasource)")
    void validatesFlywayUrlIndependentlyAndAgainstDatasourceTarget() {
        // Mesma destinação lógica (válida) -> aceita
        MockEnvironment sameTarget = validProdEnvironment();
        sameTarget.setProperty("spring.flyway.url", "jdbc:postgresql://db.rewit.internal:5432/rewit_prod?sslmode=verify-full");
        assertDoesNotThrow(() -> ProductionEnvironmentValidator.validate(sameTarget));

        // Ausente -> usa spring.datasource.url já validada -> aceita
        MockEnvironment absentFlywayUrl = validProdEnvironment();
        assertDoesNotThrow(() -> ProductionEnvironmentValidator.validate(absentFlywayUrl));

        // Cenários adversariais -> todos rejeitados
        for (String badFlywayUrl : new String[]{
                "jdbc:postgresql://localhost:5432/rewit_prod?sslmode=disable",
                "jdbc:postgresql://other-db.rewit.internal:5432/rewit_prod?sslmode=verify-full",
                "jdbc:postgresql://db.rewit.internal:5433/rewit_prod?sslmode=verify-full",
                "jdbc:postgresql://db.rewit.internal:5432/other_db?sslmode=verify-full",
                "jdbc:postgresql://db.rewit.internal:5432/rewit_prod?sslmode=require",
                "jdbc:postgresql://db.rewit.internal:5432/rewit_prod",
                "jdbc:postgresql://migrator:secret@db.rewit.internal:5432/rewit_prod?sslmode=verify-full"
        }) {
            MockEnvironment env = validProdEnvironment();
            env.setProperty("spring.flyway.url", badFlywayUrl);
            assertThrows(ProductionEnvironmentValidator.InvalidConfigurationException.class,
                    () -> ProductionEnvironmentValidator.validate(env),
                    "spring.flyway.url deve ser rejeitada: " + badFlywayUrl);
        }
    }

    @Test
    @DisplayName("AUDIT-04: Redis standalone TLS, rediss:// com placeholders, conflito TLS e rejeição de cluster/sentinel")
    void validatesRedisAdversarialCases() {
        // Válido via host/port/password/ssl
        assertDoesNotThrow(() -> ProductionEnvironmentValidator.validate(validProdEnvironment()));

        // Válido via rediss:// consistente
        MockEnvironment validRediss = validProdEnvironment();
        validRediss.setProperty("spring.data.redis.url", "rediss://default:" + REDIS_PASS + "@redis.rewit.internal:6380");
        assertDoesNotThrow(() -> ProductionEnvironmentValidator.validate(validRediss));

        // Placeholder na URL rediss:// -> rejeitado
        MockEnvironment placeholderUrl = validProdEnvironment();
        placeholderUrl.setProperty("spring.data.redis.url", "rediss://:change-me@redis.rewit.internal:6380");
        assertThrows(ProductionEnvironmentValidator.InvalidConfigurationException.class,
                () -> ProductionEnvironmentValidator.validate(placeholderUrl));

        // Userinfo malformado (sem ':') -> rejeitado
        MockEnvironment malformedUserInfo = validProdEnvironment();
        malformedUserInfo.setProperty("spring.data.redis.url", "rediss://default@redis.rewit.internal:6380");
        assertThrows(ProductionEnvironmentValidator.InvalidConfigurationException.class,
                () -> ProductionEnvironmentValidator.validate(malformedUserInfo));

        // rediss:// combinado com ssl.enabled=false -> rejeitado
        MockEnvironment conflictingSsl = validProdEnvironment();
        conflictingSsl.setProperty("spring.data.redis.url", "rediss://default:" + REDIS_PASS + "@redis.rewit.internal:6380");
        conflictingSsl.setProperty("spring.data.redis.ssl.enabled", "false");
        assertThrows(ProductionEnvironmentValidator.InvalidConfigurationException.class,
                () -> ProductionEnvironmentValidator.validate(conflictingSsl));

        // URL redis:// sem TLS -> rejeitado
        MockEnvironment plainUrl = validProdEnvironment();
        plainUrl.setProperty("spring.data.redis.url", "redis://:" + REDIS_PASS + "@redis.rewit.internal:6380");
        assertThrows(ProductionEnvironmentValidator.InvalidConfigurationException.class,
                () -> ProductionEnvironmentValidator.validate(plainUrl));

        // Host conflitante entre url e host individual -> rejeitado
        MockEnvironment conflictingHost = validProdEnvironment();
        conflictingHost.setProperty("spring.data.redis.url", "rediss://default:" + REDIS_PASS + "@other-redis.rewit.internal:6380");
        assertThrows(ProductionEnvironmentValidator.InvalidConfigurationException.class,
                () -> ProductionEnvironmentValidator.validate(conflictingHost));

        // Cluster e Sentinel locais ou configurados -> rejeitados em prod
        MockEnvironment clusterNodes = validProdEnvironment();
        clusterNodes.setProperty("spring.data.redis.cluster.nodes", "localhost:6379");
        assertThrows(ProductionEnvironmentValidator.InvalidConfigurationException.class,
                () -> ProductionEnvironmentValidator.validate(clusterNodes));

        MockEnvironment sentinelNodes = validProdEnvironment();
        sentinelNodes.setProperty("spring.data.redis.sentinel.nodes", "localhost:26379");
        assertThrows(ProductionEnvironmentValidator.InvalidConfigurationException.class,
                () -> ProductionEnvironmentValidator.validate(sentinelNodes));

        // Host localhost / senha ausente / ssl=false -> rejeitados
        MockEnvironment localHost = validProdEnvironment();
        localHost.setProperty("spring.data.redis.host", "localhost");
        assertThrows(ProductionEnvironmentValidator.InvalidConfigurationException.class,
                () -> ProductionEnvironmentValidator.validate(localHost));

        MockEnvironment missingPass = validProdEnvironment();
        missingPass.setProperty("spring.data.redis.password", "");
        assertThrows(ProductionEnvironmentValidator.InvalidConfigurationException.class,
                () -> ProductionEnvironmentValidator.validate(missingPass));

        MockEnvironment sslFalse = validProdEnvironment();
        sslFalse.setProperty("spring.data.redis.ssl.enabled", "false");
        assertThrows(ProductionEnvironmentValidator.InvalidConfigurationException.class,
                () -> ProductionEnvironmentValidator.validate(sslFalse));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://s3.us-east-1.amazonaws.com",
            "https://localhost:8333",
            "https://sub.localhost:8333",
            "https://127.0.0.1:8333",
            "https://127.0.0.2:8333",
            "https://[::1]:8333",
            "https://[0:0:0:0:0:0:0:1]:8333",
            "https://[::ffff:127.0.0.1]:8333",
            "https://[::ffff:7f00:1]:8333",
            "https://0.0.0.0:8333",
            "https://[::]:8333",
            "https://seaweedfs:8333",
            "https://minio.local:9000",
            "https://user:pass@s3.us-east-1.amazonaws.com",
            "https://s3.us-east-1.amazonaws.com?foo=bar",
            "https://s3.us-east-1.amazonaws.com#frag"
    })
    @DisplayName("AUDIT-06: Endpoints S3 HTTP, loopback IPv4/IPv6/mapped, 0.0.0.0/::, userinfo, query e fragment são rejeitados")
    void rejectsAdversarialStorageEndpoints(String endpoint) {
        MockEnvironment env = validProdEnvironment();
        env.setProperty("rewit.minio.endpoint", endpoint);

        ProductionEnvironmentValidator.InvalidConfigurationException ex = assertThrows(
                ProductionEnvironmentValidator.InvalidConfigurationException.class,
                () -> ProductionEnvironmentValidator.validate(env),
                "Endpoint deve ser rejeitado: " + endpoint
        );
        assertTrue(ex.getMessage().contains("rewit.minio.endpoint"));
    }

    @Test
    @DisplayName("AUDIT-06: Endpoint S3 HTTPS em rede privada RFC1918 é permitido")
    void allowsPrivateRfc1918HttpsStorageEndpoint() {
        MockEnvironment env = validProdEnvironment();
        env.setProperty("rewit.minio.endpoint", "https://10.20.30.40:9000");
        assertDoesNotThrow(() -> ProductionEnvironmentValidator.validate(env));
    }

    @Test
    @DisplayName("18, 19 & 21. Storage valida chaves, bucket e auto-create-bucket=false")
    void validatesStorageKeysBucketAndAutoCreate() {
        MockEnvironment emptyAccess = validProdEnvironment();
        emptyAccess.setProperty("rewit.minio.access-key", "");
        assertThrows(ProductionEnvironmentValidator.InvalidConfigurationException.class,
                () -> ProductionEnvironmentValidator.validate(emptyAccess));

        MockEnvironment placeholderSecret = validProdEnvironment();
        placeholderSecret.setProperty("rewit.minio.secret-key", "change-me");
        assertThrows(ProductionEnvironmentValidator.InvalidConfigurationException.class,
                () -> ProductionEnvironmentValidator.validate(placeholderSecret));

        MockEnvironment missingBucket = validProdEnvironment();
        missingBucket.setProperty("rewit.minio.bucket-name", "");
        assertThrows(ProductionEnvironmentValidator.InvalidConfigurationException.class,
                () -> ProductionEnvironmentValidator.validate(missingBucket));

        MockEnvironment autoCreate = validProdEnvironment();
        autoCreate.setProperty("rewit.minio.auto-create-bucket", "true");
        assertThrows(ProductionEnvironmentValidator.InvalidConfigurationException.class,
                () -> ProductionEnvironmentValidator.validate(autoCreate));
    }

    @Test
    @DisplayName("AUDIT-05 & AUDIT-07: Hardening HTTP (Swagger, stacktrace, exception, CORS HTTPS puro e proxy)")
    void validatesHttpHardeningCorsAndStacktrace() {
        MockEnvironment validCors = validProdEnvironment();
        validCors.setProperty("rewit.security.cors.allowed-origins", "https://admin.rewit.app,https://staging-admin.rewit.app");
        assertDoesNotThrow(() -> ProductionEnvironmentValidator.validate(validCors));

        for (String badCors : new String[]{"*", "https://*.rewit.app", "http://admin.rewit.app",
                "https://admin.rewit.app/path", "https://admin.rewit.app?x=1", "https://u:p@admin.rewit.app"}) {
            MockEnvironment env = validProdEnvironment();
            env.setProperty("rewit.security.cors.allowed-origins", badCors);
            assertThrows(ProductionEnvironmentValidator.InvalidConfigurationException.class,
                    () -> ProductionEnvironmentValidator.validate(env),
                    "CORS deve ser rejeitado: " + badCors);
        }

        MockEnvironment stacktraceOn = validProdEnvironment();
        stacktraceOn.setProperty("spring.web.error.include-stacktrace", "always");
        assertThrows(ProductionEnvironmentValidator.InvalidConfigurationException.class,
                () -> ProductionEnvironmentValidator.validate(stacktraceOn));

        MockEnvironment exceptionOn = validProdEnvironment();
        exceptionOn.setProperty("spring.web.error.include-exception", "true");
        assertThrows(ProductionEnvironmentValidator.InvalidConfigurationException.class,
                () -> ProductionEnvironmentValidator.validate(exceptionOn));

        MockEnvironment swaggerOn = validProdEnvironment();
        swaggerOn.setProperty("springdoc.swagger-ui.enabled", "true");
        assertThrows(ProductionEnvironmentValidator.InvalidConfigurationException.class,
                () -> ProductionEnvironmentValidator.validate(swaggerOn));

        MockEnvironment proxyWildcard = validProdEnvironment();
        proxyWildcard.setProperty("server.tomcat.remoteip.internal-proxies", ".*");
        assertThrows(ProductionEnvironmentValidator.InvalidConfigurationException.class,
                () -> ProductionEnvironmentValidator.validate(proxyWildcard));
    }

    @Test
    @DisplayName("Integrações opcionais desabilitadas não bloqueiam startup; habilitadas são validadas")
    void validatesOptionalIntegrationsConditionally() {
        assertDoesNotThrow(() -> ProductionEnvironmentValidator.validate(validProdEnvironment()));

        MockEnvironment badPlaces = validProdEnvironment();
        badPlaces.setProperty("rewit.google.places.api-key", "change-me");
        assertThrows(ProductionEnvironmentValidator.InvalidConfigurationException.class,
                () -> ProductionEnvironmentValidator.validate(badPlaces));

        MockEnvironment badOtlp = validProdEnvironment();
        badOtlp.setProperty("management.opentelemetry.tracing.export.otlp.endpoint", "not-a-url");
        assertThrows(ProductionEnvironmentValidator.InvalidConfigurationException.class,
                () -> ProductionEnvironmentValidator.validate(badOtlp));

        MockEnvironment validOptional = validProdEnvironment();
        validOptional.setProperty("rewit.social-auth.google.client-ids", "123456.apps.googleusercontent.com");
        validOptional.setProperty("management.opentelemetry.tracing.export.otlp.endpoint", "https://otel.rewit.internal:4318/v1/traces");
        assertDoesNotThrow(() -> ProductionEnvironmentValidator.validate(validOptional));
    }

    @Test
    @DisplayName("FailureAnalyzer e mensagens de erro nunca expõem valores reais, URLs ou segredos")
    void neverExposesSecretsInErrorsOrFailureAnalysis() {
        MockEnvironment env = validProdEnvironment();
        env.setProperty("rewit.security.jwt-secret", "change-me");
        env.setProperty("spring.data.redis.password", "");
        env.setProperty("spring.flyway.url", "jdbc:postgresql://secret-flyway-host:5432/other_db?sslmode=disable");

        ProductionEnvironmentValidator.InvalidConfigurationException ex = assertThrows(
                ProductionEnvironmentValidator.InvalidConfigurationException.class,
                () -> ProductionEnvironmentValidator.validate(env)
        );
        FailureAnalysis analysis = new ProductionConfigurationFailureAnalyzer().analyze(ex);
        assertNotNull(analysis);
        String combined = ex.getMessage() + " " + analysis.getDescription();
        assertFalse(combined.contains(APP_DB_PASS));
        assertFalse(combined.contains(STORAGE_SECRET));
        assertFalse(combined.contains(PEPPER_SECRET));
        assertFalse(combined.contains("secret-flyway-host"));
        assertFalse(combined.contains("change-me"));
    }

    @Test
    @DisplayName("R1-01 & R1-02: Redis masterreplica e variáveis indexadas relaxed-binding (cluster/sentinel/masterreplica) são detectadas via Binder e rejeitadas")
    void rejectsRedisMasterReplicaAndIndexedRelaxedBindingTopologies() {
        // 1. masterreplica.nodes direto
        MockEnvironment masterReplicaDirect = validProdEnvironment();
        masterReplicaDirect.setProperty("spring.data.redis.masterreplica.nodes", "localhost:6379");
        DataRedisProperties boundMr = Binder.get(masterReplicaDirect)
                .bind("spring.data.redis", DataRedisProperties.class).get();
        assertTrue(boundMr.getMasterreplica() != null && boundMr.getMasterreplica().getNodes().contains("localhost:6379"));
        assertThrows(ProductionEnvironmentValidator.InvalidConfigurationException.class,
                () -> ProductionEnvironmentValidator.validate(masterReplicaDirect));

        // 2. Variáveis de ambiente indexadas (SystemEnvironmentPropertySource real)
        for (Map.Entry<String, String> envCase : Map.of(
                "SPRING_DATA_REDIS_MASTERREPLICA_NODES_0", "localhost:6379",
                "SPRING_DATA_REDIS_CLUSTER_NODES_0", "localhost:6379",
                "SPRING_DATA_REDIS_SENTINEL_NODES_0", "localhost:26379"
        ).entrySet()) {
            MockEnvironment env = validProdEnvironment();
            env.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                    "systemEnvironment", Map.of(envCase.getKey(), envCase.getValue())));
            DataRedisProperties bound = Binder.get(env)
                    .bind("spring.data.redis", DataRedisProperties.class).get();
            boolean hasTopology = (bound.getCluster() != null && !bound.getCluster().getNodes().isEmpty())
                    || (bound.getSentinel() != null && !bound.getSentinel().getNodes().isEmpty())
                    || (bound.getMasterreplica() != null && !bound.getMasterreplica().getNodes().isEmpty());
            assertTrue(hasTopology, "Binder deve vincular topologia para " + envCase.getKey());
            assertThrows(ProductionEnvironmentValidator.InvalidConfigurationException.class,
                    () -> ProductionEnvironmentValidator.validate(env),
                    "Validador deve rejeitar " + envCase.getKey());
        }

        // 3. Índices [0], [1], [2] e variáveis indexadas subsequentes
        MockEnvironment multiCluster = validProdEnvironment();
        multiCluster.setProperty("spring.data.redis.cluster.nodes[0]", "redis-1.internal:6379");
        multiCluster.setProperty("spring.data.redis.cluster.nodes[1]", "redis-2.internal:6379");
        multiCluster.setProperty("spring.data.redis.cluster.nodes[2]", "redis-3.internal:6379");
        assertThrows(ProductionEnvironmentValidator.InvalidConfigurationException.class,
                () -> ProductionEnvironmentValidator.validate(multiCluster));

        MockEnvironment gapIndexCluster = validProdEnvironment();
        gapIndexCluster.setProperty("spring.data.redis.cluster.nodes[1]", "localhost:6379");
        assertThrows(ProductionEnvironmentValidator.InvalidConfigurationException.class,
                () -> ProductionEnvironmentValidator.validate(gapIndexCluster));

        MockEnvironment gapIndexMr = validProdEnvironment();
        gapIndexMr.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                "systemEnvironment", Map.of("SPRING_DATA_REDIS_MASTERREPLICA_NODES_2", "localhost:6379")));
        assertThrows(ProductionEnvironmentValidator.InvalidConfigurationException.class,
                () -> ProductionEnvironmentValidator.validate(gapIndexMr));

        // 4. Objetos/listas vazios NÃO devem gerar falso positivo em standalone válido
        MockEnvironment emptyTopologies = validProdEnvironment();
        emptyTopologies.setProperty("spring.data.redis.cluster.nodes", "");
        emptyTopologies.setProperty("spring.data.redis.sentinel.nodes", "");
        emptyTopologies.setProperty("spring.data.redis.masterreplica.nodes", "");
        assertDoesNotThrow(() -> ProductionEnvironmentValidator.validate(emptyTopologies));
    }

    @Test
    @DisplayName("R1-03: Bloqueia sslfactory, sslfactoryarg e sslhostnameverifier em datasource e Flyway; preserva sslrootcert com verify-full")
    void blocksPostgresTlsFactoryAndHostnameVerifierOverridesWhileAllowingSslRootCert() {
        // Válido com sslrootcert + verify-full (verificado também via Driver.parseURL real)
        MockEnvironment withCa = validProdEnvironment();
        String validCaUrl = "jdbc:postgresql://db.rewit.internal:5432/rewit_prod?sslmode=verify-full&sslrootcert=/etc/ssl/certs/rds-ca.pem";
        withCa.setProperty("spring.datasource.url", validCaUrl);
        withCa.setProperty("spring.flyway.url", validCaUrl);
        assertDoesNotThrow(() -> ProductionEnvironmentValidator.validate(withCa));

        Properties parsed = Driver.parseURL(validCaUrl, new Properties());
        assertNotNull(parsed);
        assertEquals("verify-full", parsed.getProperty("sslmode"));
        assertEquals("/etc/ssl/certs/rds-ca.pem", parsed.getProperty("sslrootcert"));
        assertEquals(null, parsed.getProperty("sslfactory"));
        assertEquals(null, parsed.getProperty("sslhostnameverifier"));

        String[] adversarialUrls = new String[]{
                "jdbc:postgresql://db.rewit.internal:5432/rewit_prod?sslmode=verify-full&sslfactory=org.postgresql.ssl.NonValidatingFactory",
                "jdbc:postgresql://db.rewit.internal:5432/rewit_prod?sslmode=verify-full&sslFactory=org.postgresql.ssl.NonValidatingFactory",
                "jdbc:postgresql://db.rewit.internal:5432/rewit_prod?sslmode=verify-full&SSLFACTORY=org.postgresql.ssl.NonValidatingFactory",
                "jdbc:postgresql://db.rewit.internal:5432/rewit_prod?sslmode=verify-full&%73slfactory=org.postgresql.ssl.NonValidatingFactory",
                "jdbc:postgresql://db.rewit.internal:5432/rewit_prod?sslmode=verify-full&sslfactoryarg=custom",
                "jdbc:postgresql://db.rewit.internal:5432/rewit_prod?sslmode=verify-full&SSLFactoryArg=custom",
                "jdbc:postgresql://db.rewit.internal:5432/rewit_prod?sslmode=verify-full&%73slfactoryarg=custom",
                "jdbc:postgresql://db.rewit.internal:5432/rewit_prod?sslmode=verify-full&sslhostnameverifier=com.example.AllowAllVerifier",
                "jdbc:postgresql://db.rewit.internal:5432/rewit_prod?sslmode=verify-full&sslHostnameVerifier=com.example.AllowAllVerifier",
                "jdbc:postgresql://db.rewit.internal:5432/rewit_prod?sslmode=verify-full&%73slhostnameverifier=com.example.AllowAllVerifier"
        };

        for (String badUrl : adversarialUrls) {
            MockEnvironment dsEnv = validProdEnvironment();
            dsEnv.setProperty("spring.datasource.url", badUrl);
            assertThrows(ProductionEnvironmentValidator.InvalidConfigurationException.class,
                    () -> ProductionEnvironmentValidator.validate(dsEnv),
                    "spring.datasource.url deve rejeitar: " + badUrl);

            MockEnvironment fwEnv = validProdEnvironment();
            fwEnv.setProperty("spring.flyway.url", badUrl);
            assertThrows(ProductionEnvironmentValidator.InvalidConfigurationException.class,
                    () -> ProductionEnvironmentValidator.validate(fwEnv),
                    "spring.flyway.url deve rejeitar: " + badUrl);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "localhost.",
            "LOCALHOST.",
            "sub.localhost.",
            "Sub.LocalHost.",
            "0177.0.0.1",
            "000.000.000.000",
            "2130706433",
            "0x7f000001",
            "127.1",
            "127.0.1",
            "[::127.0.0.1]",
            "[::ffff:127.0.0.1]",
            "[0:0:0:0:0:0:0:1]",
            "[::]",
            "0.0.0.0",
            "127.0.0.1"
    })
    @DisplayName("R1-04: Rejeita hostnames locais, trailing dot, IPv4 octal/hex/decimal/abreviado e IPv6 loopback/compatível nos 3 domínios (PG, Redis, S3)")
    void rejectsAmbiguousAndLocalHostsAcrossAllThreeDomains(String badHost) {
        // 1. PostgreSQL
        MockEnvironment pgEnv = validProdEnvironment();
        pgEnv.setProperty("spring.datasource.url", "jdbc:postgresql://" + badHost + ":5432/rewit_prod?sslmode=verify-full");
        assertThrows(ProductionEnvironmentValidator.InvalidConfigurationException.class,
                () -> ProductionEnvironmentValidator.validate(pgEnv),
                "PostgreSQL deve rejeitar host: " + badHost);

        // 2. Redis (host individual e rediss://)
        MockEnvironment redisHostEnv = validProdEnvironment();
        redisHostEnv.setProperty("spring.data.redis.host", badHost);
        assertThrows(ProductionEnvironmentValidator.InvalidConfigurationException.class,
                () -> ProductionEnvironmentValidator.validate(redisHostEnv),
                "Redis host deve rejeitar: " + badHost);

        MockEnvironment redisUrlEnv = validProdEnvironment();
        redisUrlEnv.setProperty("spring.data.redis.url", "rediss://default:" + REDIS_PASS + "@" + badHost + ":6380");
        assertThrows(ProductionEnvironmentValidator.InvalidConfigurationException.class,
                () -> ProductionEnvironmentValidator.validate(redisUrlEnv),
                "Redis URL deve rejeitar: " + badHost);

        // 3. Object Storage S3
        MockEnvironment s3Env = validProdEnvironment();
        s3Env.setProperty("rewit.minio.endpoint", "https://" + badHost + ":8333");
        assertThrows(ProductionEnvironmentValidator.InvalidConfigurationException.class,
                () -> ProductionEnvironmentValidator.validate(s3Env),
                "S3 endpoint deve rejeitar: " + badHost);
    }

    @Test
    @DisplayName("R1-04: Rejeita portas inválidas (:0, :99999, :-1, vazia) em PG, Flyway, Redis e S3; aceita RFC1918 e HTTPS público")
    void validatesPortsAndPreservesLegitimatePublicAndPrivateEndpoints() {
        // Portas inválidas S3
        for (String badS3 : new String[]{
                "https://s3.us-east-1.amazonaws.com:0",
                "https://s3.us-east-1.amazonaws.com:99999",
                "https://s3.us-east-1.amazonaws.com:-1",
                "https://s3.us-east-1.amazonaws.com:"
        }) {
            MockEnvironment env = validProdEnvironment();
            env.setProperty("rewit.minio.endpoint", badS3);
            assertThrows(ProductionEnvironmentValidator.InvalidConfigurationException.class,
                    () -> ProductionEnvironmentValidator.validate(env),
                    "S3 deve rejeitar porta inválida: " + badS3);
        }

        // Portas inválidas PostgreSQL e Flyway
        for (String badPort : new String[]{"0", "99999", "-1", ""}) {
            MockEnvironment pgEnv = validProdEnvironment();
            pgEnv.setProperty("spring.datasource.url",
                    "jdbc:postgresql://db.rewit.internal:" + badPort + "/rewit_prod?sslmode=verify-full");
            assertThrows(ProductionEnvironmentValidator.InvalidConfigurationException.class,
                    () -> ProductionEnvironmentValidator.validate(pgEnv));

            MockEnvironment fwEnv = validProdEnvironment();
            fwEnv.setProperty("spring.flyway.url",
                    "jdbc:postgresql://db.rewit.internal:" + badPort + "/rewit_prod?sslmode=verify-full");
            assertThrows(ProductionEnvironmentValidator.InvalidConfigurationException.class,
                    () -> ProductionEnvironmentValidator.validate(fwEnv));
        }

        // Portas inválidas Redis (propriedade e URL)
        for (String badPort : new String[]{"0", "99999", "-1", "abc"}) {
            MockEnvironment redisEnv = validProdEnvironment();
            redisEnv.setProperty("spring.data.redis.port", badPort);
            assertThrows(ProductionEnvironmentValidator.InvalidConfigurationException.class,
                    () -> ProductionEnvironmentValidator.validate(redisEnv));

            MockEnvironment redisUrlEnv = validProdEnvironment();
            redisUrlEnv.setProperty("spring.data.redis.url",
                    "rediss://default:" + REDIS_PASS + "@redis.rewit.internal:" + badPort);
            assertThrows(ProductionEnvironmentValidator.InvalidConfigurationException.class,
                    () -> ProductionEnvironmentValidator.validate(redisUrlEnv));
        }

        // Endereços privados RFC1918 e HTTPS público legítimos continuam aceitos
        MockEnvironment rfc1918Env = validProdEnvironment();
        rfc1918Env.setProperty("spring.datasource.url", "jdbc:postgresql://10.20.30.41:5432/rewit_prod?sslmode=verify-full");
        rfc1918Env.setProperty("spring.data.redis.host", "192.168.10.20");
        rfc1918Env.setProperty("rewit.minio.endpoint", "https://172.16.5.10:9000");
        assertDoesNotThrow(() -> ProductionEnvironmentValidator.validate(rfc1918Env));

        MockEnvironment publicHttpsWithPort = validProdEnvironment();
        publicHttpsWithPort.setProperty("rewit.minio.endpoint", "https://s3.us-east-1.amazonaws.com:443");
        assertDoesNotThrow(() -> ProductionEnvironmentValidator.validate(publicHttpsWithPort));
    }

    @Test
    @DisplayName("R1-05: ErrorProperties via Binder rejeita sobrescritas camelCase, ON_PARAM e variáveis de ambiente")
    void validatesErrorPropertiesViaBinderAcrossRelaxedBindingSources() {
        // Configuração válida vincula NEVER/false em ErrorProperties
        MockEnvironment valid = validProdEnvironment();
        assertDoesNotThrow(() -> ProductionEnvironmentValidator.validate(valid));
        ErrorProperties boundValid = Binder.get(valid).bind("spring.web.error", ErrorProperties.class).get();
        assertEquals(ErrorProperties.IncludeAttribute.NEVER, boundValid.getIncludeMessage());
        assertEquals(ErrorProperties.IncludeAttribute.NEVER, boundValid.getIncludeBindingErrors());
        assertEquals(ErrorProperties.IncludeAttribute.NEVER, boundValid.getIncludeStacktrace());
        assertFalse(boundValid.isIncludeException());

        // Sobrescritas camelCase com precedência superior (CLI/JSON)
        for (Map.Entry<String, String> override : Map.of(
                "spring.web.error.includeStacktrace", "always",
                "spring.web.error.includeException", "true",
                "spring.web.error.includeMessage", "always",
                "spring.web.error.includeBindingErrors", "always"
        ).entrySet()) {
            MockEnvironment env = validProdEnvironment();
            env.getPropertySources().addFirst(new MapPropertySource("cli", Map.of(override.getKey(), override.getValue())));
            assertThrows(ProductionEnvironmentValidator.InvalidConfigurationException.class,
                    () -> ProductionEnvironmentValidator.validate(env),
                    "Deve rejeitar sobrescrita camelCase: " + override.getKey());
        }

        // ON_PARAM também deve ser rejeitado
        MockEnvironment onParam = validProdEnvironment();
        onParam.getPropertySources().addFirst(new MapPropertySource("cli",
                Map.of("spring.web.error.includeStacktrace", "on_param")));
        assertThrows(ProductionEnvironmentValidator.InvalidConfigurationException.class,
                () -> ProductionEnvironmentValidator.validate(onParam));

        // Variáveis de ambiente relaxed binding (SystemEnvironmentPropertySource real)
        for (Map.Entry<String, String> envOverride : Map.of(
                "SPRING_WEB_ERROR_INCLUDE_STACKTRACE", "always",
                "SPRING_WEB_ERROR_INCLUDE_EXCEPTION", "true",
                "SPRING_WEB_ERROR_INCLUDE_MESSAGE", "on_param",
                "SPRING_WEB_ERROR_INCLUDE_BINDING_ERRORS", "always"
        ).entrySet()) {
            MockEnvironment env = validProdEnvironment();
            env.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                    "systemEnvironment", Map.of(envOverride.getKey(), envOverride.getValue())));
            assertThrows(ProductionEnvironmentValidator.InvalidConfigurationException.class,
                    () -> ProductionEnvironmentValidator.validate(env),
                    "Deve rejeitar env var: " + envOverride.getKey());
        }
    }

    @Test
    @DisplayName("R1-06: Validação de porta explícita em CORS_ALLOWED_ORIGINS")
    void validatesCorsAllowedOriginPorts() {
        MockEnvironment validWithPort = validProdEnvironment();
        validWithPort.setProperty("rewit.security.cors.allowed-origins",
                "https://admin.rewit.app:443,https://staging-admin.rewit.app:8443");
        assertDoesNotThrow(() -> ProductionEnvironmentValidator.validate(validWithPort));

        for (String badPortOrigin : new String[]{
                "https://admin.rewit.app:0",
                "https://admin.rewit.app:99999",
                "https://admin.rewit.app:-1",
                "https://admin.rewit.app:"
        }) {
            MockEnvironment env = validProdEnvironment();
            env.setProperty("rewit.security.cors.allowed-origins", badPortOrigin);
            assertThrows(ProductionEnvironmentValidator.InvalidConfigurationException.class,
                    () -> ProductionEnvironmentValidator.validate(env),
                    "CORS deve rejeitar porta inválida: " + badPortOrigin);
        }
    }
}
