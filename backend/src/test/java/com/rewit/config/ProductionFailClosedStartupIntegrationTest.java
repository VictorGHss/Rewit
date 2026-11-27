package com.rewit.config;

import com.rewit.RewitApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testes de inicialização fail-closed com {@link SpringApplication} real (F2-DEPLOY-03.3 / R1):
 * comprovam que falhas de seleção de profile (inclusive via CLI e SPRING_APPLICATION_JSON) e de
 * validação de produção abortam o boot ANTES da criação de DataSources, ANTES do Flyway executar
 * e ANTES da abertura da porta HTTP.
 */
@DisplayName("Inicialização fail-closed antes do Flyway e da porta HTTP (F2-DEPLOY-03.3 / R1)")
class ProductionFailClosedStartupIntegrationTest {

    @Test
    @DisplayName("26 & 27. Ausência de SPRING_PROFILES_ACTIVE aborta antes de Flyway e sem abrir porta HTTP")
    void missingProfileAbortsBeforeFlywayAndHttpPort() throws Exception {
        int port = findFreePort();
        AtomicBoolean contextRefreshed = new AtomicBoolean(false);

        SpringApplication app = new SpringApplication(RewitApplication.class);
        app.setWebApplicationType(WebApplicationType.SERVLET);
        app.addListeners(event -> {
            if (event instanceof org.springframework.context.event.ContextRefreshedEvent) {
                contextRefreshed.set(true);
            }
        });

        RuntimeProfileEnvironmentValidator.InvalidProfileConfigurationException ex = assertThrows(
                RuntimeProfileEnvironmentValidator.InvalidProfileConfigurationException.class,
                () -> {
                    try (ConfigurableApplicationContext ignored = app.run("--server.port=" + port)) {
                        // Nunca deve subir
                    }
                }
        );

        assertTrue(ex.getMessage().contains("SPRING_PROFILES_ACTIVE"));
        assertFalse(contextRefreshed.get());
        assertPortNotListening(port);
    }

    @Test
    @DisplayName("AUDIT-01. Sobrescrita CLI --spring.profiles.active=production (typo) aborta antes do Flyway e HTTP")
    void cliTypoProfileAbortsBeforeFlywayAndHttpPort() throws Exception {
        int port = findFreePort();
        AtomicBoolean beanFactoryPostProcessed = new AtomicBoolean(false);

        SpringApplication app = new SpringApplication(RewitApplication.class);
        app.setWebApplicationType(WebApplicationType.SERVLET);
        app.addInitializers(ctx -> ctx.addBeanFactoryPostProcessor(bf -> beanFactoryPostProcessed.set(true)));

        RuntimeProfileEnvironmentValidator.InvalidProfileConfigurationException ex = assertThrows(
                RuntimeProfileEnvironmentValidator.InvalidProfileConfigurationException.class,
                () -> {
                    try (ConfigurableApplicationContext ignored = app.run(
                            "--server.port=" + port,
                            "--spring.profiles.active=production"
                    )) {
                        // Nunca deve subir
                    }
                }
        );

        assertTrue(ex.getMessage().contains("production"));
        assertFalse(beanFactoryPostProcessed.get());
        assertPortNotListening(port);
    }

    @Test
    @DisplayName("AUDIT-01. Sobrescrita via spring.application.json com profile desconhecido aborta antes do Flyway e HTTP")
    void springApplicationJsonUnknownProfileAbortsBeforeFlywayAndHttpPort() throws Exception {
        int port = findFreePort();
        AtomicBoolean beanFactoryPostProcessed = new AtomicBoolean(false);

        SpringApplication app = new SpringApplication(RewitApplication.class);
        app.setWebApplicationType(WebApplicationType.SERVLET);
        app.addInitializers(ctx -> ctx.addBeanFactoryPostProcessor(bf -> beanFactoryPostProcessed.set(true)));

        RuntimeProfileEnvironmentValidator.InvalidProfileConfigurationException ex = assertThrows(
                RuntimeProfileEnvironmentValidator.InvalidProfileConfigurationException.class,
                () -> {
                    try (ConfigurableApplicationContext ignored = app.run(
                            "--server.port=" + port,
                            "--spring.application.json={\"spring\":{\"profiles\":{\"active\":\"production\"}}}"
                    )) {
                        // Nunca deve subir
                    }
                }
        );

        assertTrue(ex.getMessage().contains("production"));
        assertFalse(beanFactoryPostProcessed.get());
        assertPortNotListening(port);
    }

    @Test
    @DisplayName("26 & 27. Profile local com ENVIRONMENT=production aborta antes de Flyway e sem abrir porta HTTP")
    void localProfileInProductionAbortsBeforeFlywayAndHttpPort() throws Exception {
        int port = findFreePort();
        AtomicBoolean contextRefreshed = new AtomicBoolean(false);

        SpringApplication app = new SpringApplication(RewitApplication.class);
        app.setWebApplicationType(WebApplicationType.SERVLET);
        app.setAdditionalProfiles("local");
        app.addListeners(event -> {
            if (event instanceof org.springframework.context.event.ContextRefreshedEvent) {
                contextRefreshed.set(true);
            }
        });

        RuntimeProfileEnvironmentValidator.InvalidProfileConfigurationException ex = assertThrows(
                RuntimeProfileEnvironmentValidator.InvalidProfileConfigurationException.class,
                () -> {
                    try (ConfigurableApplicationContext ignored = app.run(
                            "--server.port=" + port,
                            "--rewit.environment=production"
                    )) {
                        // Nunca deve subir
                    }
                }
        );

        assertTrue(ex.getMessage().contains("local"));
        assertFalse(contextRefreshed.get());
        assertPortNotListening(port);
    }

    @Test
    @DisplayName("AUDIT-02/03/04/07. Sobrescritas CLI e JSON em prod (flyway.url, sslMode, redis cluster, include-stacktrace) abortam antes de Flyway e HTTP")
    void invalidProdConfigAndCliJsonOverridesAbortBeforeFlywayAndHttpPort() throws Exception {
        int port = findFreePort();
        AtomicBoolean beanFactoryPostProcessed = new AtomicBoolean(false);

        SpringApplication app = new SpringApplication(RewitApplication.class);
        app.setWebApplicationType(WebApplicationType.SERVLET);
        app.setAdditionalProfiles("prod");
        app.addInitializers(ctx -> ctx.addBeanFactoryPostProcessor(bf -> beanFactoryPostProcessed.set(true)));

        List<String> args = new ArrayList<>();
        args.add("--server.port=" + port);
        args.add("--rewit.environment=production");
        args.add("--spring.datasource.url=jdbc:postgresql://db.rewit.internal:5432/rewit_prod?sslMode=verify-full");
        args.add("--spring.flyway.url=jdbc:postgresql://localhost:5432/rewit_prod?sslmode=disable");
        args.add("--spring.datasource.username=rewit_app");
        args.add("--spring.datasource.password=app-prod-pass-Xq7#kP2v!9z");
        args.add("--rewit.datasource.media.username=rewit_media");
        args.add("--rewit.datasource.media.password=media-prod-pass-Kp4!xV7s#3w");
        args.add("--rewit.datasource.moderation.username=rewit_mod");
        args.add("--rewit.datasource.moderation.password=mod-prod-pass-Yr8#mN4q!6t");
        args.add("--spring.flyway.user=rewit_migrator");
        args.add("--spring.flyway.password=flyway-prod-pass-Lw3!rT9d#5y");
        args.add("--spring.data.redis.host=redis.rewit.internal");
        args.add("--spring.data.redis.port=6380");
        args.add("--spring.data.redis.password=redis-prod-pass-Bn2Hj8k4m");
        args.add("--spring.data.redis.ssl.enabled=false");
        args.add("--spring.data.redis.cluster.nodes=localhost:6379");
        args.add("--spring.application.json={\"spring\":{\"web\":{\"error\":{\"include-stacktrace\":\"always\",\"include-exception\":true}}}}");
        args.add("--rewit.minio.endpoint=https://[0:0:0:0:0:0:0:1]:8333");
        args.add("--rewit.minio.access-key=change-me");
        args.add("--rewit.minio.secret-key=change-me");
        args.add("--rewit.minio.bucket-name=rewit-local");
        args.add("--rewit.security.jwt-secret=prod-jwt-secret-entropia-csprng-Zp4!Qw8@Lk2#9x");
        args.add("--rewit.security.password-pepper=prod-pepper-secret-entropia-csprng-Rm7!Vx2@Np9#");
        args.add("--rewit.security.refresh-token-secret=prod-refresh-secret-entropia-csprng-Tk5!Bj8@Wd3#");
        args.add("--rewit.security.moderation-audit-secret=prod-mod-audit-secret-entropia-csprng-Kp9!Zx4@Qc1#");
        args.add("--rewit.rate-limit.key-secret=prod-rate-limit-secret-entropia-Hy6$Vb3%Nm1&8z");
        args.add("--rewit.account.purge.enabled=true");
        args.add("--rewit.account.email-reservation-secret=");

        ProductionEnvironmentValidator.InvalidConfigurationException ex = assertThrows(
                ProductionEnvironmentValidator.InvalidConfigurationException.class,
                () -> {
                    try (ConfigurableApplicationContext ignored = app.run(args.toArray(String[]::new))) {
                        // Nunca deve subir
                    }
                }
        );

        assertTrue(ex.getMessage().contains("spring.datasource.url"));
        assertTrue(ex.getMessage().contains("spring.flyway.url"));
        assertTrue(ex.getMessage().contains("REDIS_SSL_ENABLED"));
        assertTrue(ex.getMessage().contains("spring.data.redis.cluster.nodes"));
        assertTrue(ex.getMessage().contains("spring.web.error.include-stacktrace"));
        assertTrue(ex.getMessage().contains("spring.web.error.include-exception"));
        assertTrue(ex.getMessage().contains("rewit.minio.endpoint"));
        assertTrue(ex.getMessage().contains("ACCOUNT_EMAIL_RESERVATION_SECRET"));
        assertFalse(beanFactoryPostProcessed.get(),
                "Validação deve falhar na fase de ApplicationContextInitializer, antes do refresh/Flyway/DataSource");
        assertPortNotListening(port);
    }

    @Test
    @DisplayName("R1-01..R1-05. Sobrescritas CLI/JSON em camelCase, masterreplica, sslfactory e hostnames ambíguos abortam antes de Flyway e HTTP")
    void r1AdversarialCliAndJsonOverridesAbortBeforeFlywayAndHttpPort() throws Exception {
        int port = findFreePort();
        AtomicBoolean beanFactoryPostProcessed = new AtomicBoolean(false);

        SpringApplication app = new SpringApplication(RewitApplication.class);
        app.setWebApplicationType(WebApplicationType.SERVLET);
        app.setAdditionalProfiles("prod");
        app.addInitializers(ctx -> ctx.addBeanFactoryPostProcessor(bf -> beanFactoryPostProcessed.set(true)));

        List<String> args = new ArrayList<>();
        args.add("--server.port=" + port);
        args.add("--rewit.environment=production");
        args.add("--spring.datasource.url=jdbc:postgresql://db.rewit.internal:5432/rewit_prod?sslmode=verify-full&sslfactory=org.postgresql.ssl.NonValidatingFactory");
        args.add("--spring.flyway.url=jdbc:postgresql://db.rewit.internal:5432/rewit_prod?sslmode=verify-full&sslhostnameverifier=com.example.Noop");
        args.add("--spring.datasource.username=rewit_app");
        args.add("--spring.datasource.password=app-prod-pass-Xq7#kP2v!9z");
        args.add("--rewit.datasource.media.username=rewit_media");
        args.add("--rewit.datasource.media.password=media-prod-pass-Kp4!xV7s#3w");
        args.add("--rewit.datasource.moderation.username=rewit_mod");
        args.add("--rewit.datasource.moderation.password=mod-prod-pass-Yr8#mN4q!6t");
        args.add("--spring.flyway.user=rewit_migrator");
        args.add("--spring.flyway.password=flyway-prod-pass-Lw3!rT9d#5y");
        args.add("--spring.data.redis.host=redis.rewit.internal");
        args.add("--spring.data.redis.port=6380");
        args.add("--spring.data.redis.password=redis-prod-pass-Bn2Hj8k4m");
        args.add("--spring.data.redis.ssl.enabled=true");
        args.add("--spring.data.redis.masterreplica.nodes=localhost:6379");
        args.add("--spring.web.error.includeStacktrace=always");
        args.add("--spring.application.json={\"spring\":{\"web\":{\"error\":{\"includeException\":true,\"includeMessage\":\"always\"}},\"data\":{\"redis\":{\"sentinel\":{\"nodes\":[\"localhost:26379\"]}}}}}");
        args.add("--rewit.minio.endpoint=https://localhost.:8333");
        args.add("--rewit.minio.access-key=s3-prod-access-key-fixture-001");
        args.add("--rewit.minio.secret-key=storage-prod-secret-9fT2mQ8z!7p");
        args.add("--rewit.minio.bucket-name=rewit-prod-media");
        args.add("--rewit.security.jwt-secret=prod-jwt-secret-entropia-csprng-Zp4!Qw8@Lk2#9x");
        args.add("--rewit.security.password-pepper=prod-pepper-secret-entropia-csprng-Rm7!Vx2@Np9#");
        args.add("--rewit.security.refresh-token-secret=prod-refresh-secret-entropia-csprng-Tk5!Bj8@Wd3#");
        args.add("--rewit.security.moderation-audit-secret=prod-mod-audit-secret-entropia-csprng-Kp9!Zx4@Qc1#");
        args.add("--rewit.rate-limit.key-secret=prod-rate-limit-secret-entropia-Hy6$Vb3%Nm1&8z");
        args.add("--rewit.account.purge.enabled=true");
        args.add("--rewit.account.email-reservation-secret=prod-email-reservation-secret-csprng-Gf2!Rt7@Jk4#");

        ProductionEnvironmentValidator.InvalidConfigurationException ex = assertThrows(
                ProductionEnvironmentValidator.InvalidConfigurationException.class,
                () -> {
                    try (ConfigurableApplicationContext ignored = app.run(args.toArray(String[]::new))) {
                        // Nunca deve subir
                    }
                }
        );

        assertTrue(ex.getMessage().contains("sslfactory"));
        assertTrue(ex.getMessage().contains("spring.flyway.url"));
        assertTrue(ex.getMessage().contains("spring.data.redis.masterreplica.nodes"));
        assertTrue(ex.getMessage().contains("spring.data.redis.sentinel.*"));
        assertTrue(ex.getMessage().contains("spring.web.error.include-stacktrace"));
        assertTrue(ex.getMessage().contains("spring.web.error.include-exception"));
        assertTrue(ex.getMessage().contains("spring.web.error.include-message"));
        assertTrue(ex.getMessage().contains("rewit.minio.endpoint"));
        assertFalse(beanFactoryPostProcessed.get(),
                "Validação R1 deve falhar antes do refresh/Flyway/DataSource");
        assertPortNotListening(port);
    }

    @Test
    @DisplayName("R304-01 & R304-02. Sobrescritas CLI e SPRING_APPLICATION_JSON de rate-limit, probes e Actuator abortam antes do Flyway e HTTP")
    void r304RateLimitAndHealthProbeOverridesViaCliAndJsonAbortBeforeStartup() throws Exception {
        int port = findFreePort();
        AtomicBoolean beanFactoryPostProcessed = new AtomicBoolean(false);

        SpringApplication app = new SpringApplication(RewitApplication.class);
        app.setWebApplicationType(WebApplicationType.SERVLET);
        app.setAdditionalProfiles("prod");
        app.addInitializers(ctx -> ctx.addBeanFactoryPostProcessor(bf -> beanFactoryPostProcessed.set(true)));

        List<String> args = new ArrayList<>();
        args.add("--server.port=" + port);
        args.add("--rewit.environment=production");
        args.add("--spring.datasource.url=jdbc:postgresql://db.rewit.internal:5432/rewit_prod?sslmode=verify-full");
        args.add("--spring.datasource.username=rewit_app");
        args.add("--spring.datasource.password=app-prod-pass-Xq7#kP2v!9z");
        args.add("--rewit.datasource.media.username=rewit_media");
        args.add("--rewit.datasource.media.password=media-prod-pass-Kp4!xV7s#3w");
        args.add("--rewit.datasource.moderation.username=rewit_mod");
        args.add("--rewit.datasource.moderation.password=mod-prod-pass-Yr8#mN4q!6t");
        args.add("--spring.flyway.user=rewit_migrator");
        args.add("--spring.flyway.password=flyway-prod-pass-Lw3!rT9d#5y");
        args.add("--spring.data.redis.host=redis.rewit.internal");
        args.add("--spring.data.redis.port=6380");
        args.add("--spring.data.redis.password=redis-prod-pass-Bn2Hj8k4m");
        args.add("--spring.data.redis.ssl.enabled=true");
        args.add("--rewit.minio.endpoint=https://s3.us-east-1.amazonaws.com");
        args.add("--rewit.minio.access-key=s3-prod-access-key-fixture-001");
        args.add("--rewit.minio.secret-key=storage-prod-secret-9fT2mQ8z!7p");
        args.add("--rewit.minio.bucket-name=rewit-prod-media");
        args.add("--rewit.security.jwt-secret=prod-jwt-secret-entropia-csprng-Zp4!Qw8@Lk2#9x");
        args.add("--rewit.security.password-pepper=prod-pepper-secret-entropia-csprng-Rm7!Vx2@Np9#");
        args.add("--rewit.security.refresh-token-secret=prod-refresh-secret-entropia-csprng-Tk5!Bj8@Wd3#");
        args.add("--rewit.security.moderation-audit-secret=prod-mod-audit-secret-entropia-csprng-Kp9!Zx4@Qc1#");
        args.add("--rewit.rate-limit.key-secret=prod-rate-limit-secret-entropia-Hy6$Vb3%Nm1&8z");
        args.add("--rewit.account.purge.enabled=true");
        args.add("--rewit.account.email-reservation-secret=prod-email-reservation-secret-csprng-Gf2!Rt7@Jk4#");
        // R304-01 & R304-02 sobrescritas adversariais via CLI + SPRING_APPLICATION_JSON
        args.add("--rewit.rate-limit.enabled=false");
        args.add("--rewit.rate-limit.backend.failure-mode=ALLOW");
        args.add("--management.endpoint.health.group.readiness.exclude=redis");
        args.add("--management.endpoint.health.group.readiness.status.http-mapping.DOWN=200");
        args.add("--management.endpoint.health.group.liveness.include=livenessState,*");
        args.add("--management.endpoints.web.exposure.include=health,info,prometheus,scheduledtasks");
        args.add("--spring.application.json={\"management\":{\"health\":{\"livenessstate\":{\"enabled\":false},\"readinessstate\":{\"enabled\":false}}}}");

        ProductionEnvironmentValidator.InvalidConfigurationException ex = assertThrows(
                ProductionEnvironmentValidator.InvalidConfigurationException.class,
                () -> {
                    try (ConfigurableApplicationContext ignored = app.run(args.toArray(String[]::new))) {
                        // Nunca deve subir
                    }
                }
        );

        assertTrue(ex.getMessage().contains("rewit.rate-limit.enabled"));
        assertTrue(ex.getMessage().contains("rewit.rate-limit.backend.failure-mode"));
        assertTrue(ex.getMessage().contains("management.endpoint.health.group.readiness.exclude"));
        assertTrue(ex.getMessage().contains("management.endpoint.health.group.readiness.status.http-mapping"));
        assertTrue(ex.getMessage().contains("management.endpoint.health.group.liveness.include"));
        assertTrue(ex.getMessage().contains("management.endpoints.web.exposure.include"));
        assertTrue(ex.getMessage().contains("management.health.livenessstate.enabled"));
        assertTrue(ex.getMessage().contains("management.health.readinessstate.enabled"));
        assertFalse(beanFactoryPostProcessed.get(),
                "Validação R304 deve falhar antes do refresh/Flyway/DataSource");
        assertPortNotListening(port);
    }

    @Test
    @DisplayName("R304-R1-01 & R304-R1-02. Sobrescritas CLI e SPRING_APPLICATION_JSON de status.order, UNKNOWN=200, health.access=none e add-additional-paths=false abortam antes de Flyway e HTTP")
    void r304R1StatusOrderHealthAccessAndAdditionalPathsOverridesAbortBeforeStartup() throws Exception {
        int port = findFreePort();
        AtomicBoolean beanFactoryPostProcessed = new AtomicBoolean(false);

        SpringApplication app = new SpringApplication(RewitApplication.class);
        app.setWebApplicationType(WebApplicationType.SERVLET);
        app.setAdditionalProfiles("prod");
        app.addInitializers(ctx -> ctx.addBeanFactoryPostProcessor(bf -> beanFactoryPostProcessed.set(true)));

        List<String> args = new ArrayList<>();
        args.add("--server.port=" + port);
        args.add("--rewit.environment=production");
        args.add("--spring.datasource.url=jdbc:postgresql://db.rewit.internal:5432/rewit_prod?sslmode=verify-full");
        args.add("--spring.datasource.username=rewit_app");
        args.add("--spring.datasource.password=app-prod-pass-Xq7#kP2v!9z");
        args.add("--rewit.datasource.media.username=rewit_media");
        args.add("--rewit.datasource.media.password=media-prod-pass-Kp4!xV7s#3w");
        args.add("--rewit.datasource.moderation.username=rewit_mod");
        args.add("--rewit.datasource.moderation.password=mod-prod-pass-Yr8#mN4q!6t");
        args.add("--spring.flyway.user=rewit_migrator");
        args.add("--spring.flyway.password=flyway-prod-pass-Lw3!rT9d#5y");
        args.add("--spring.data.redis.host=redis.rewit.internal");
        args.add("--spring.data.redis.port=6380");
        args.add("--spring.data.redis.password=redis-prod-pass-Bn2Hj8k4m");
        args.add("--spring.data.redis.ssl.enabled=true");
        args.add("--rewit.minio.endpoint=https://s3.us-east-1.amazonaws.com");
        args.add("--rewit.minio.access-key=s3-prod-access-key-fixture-001");
        args.add("--rewit.minio.secret-key=storage-prod-secret-9fT2mQ8z!7p");
        args.add("--rewit.minio.bucket-name=rewit-prod-media");
        args.add("--rewit.security.jwt-secret=prod-jwt-secret-entropia-csprng-Zp4!Qw8@Lk2#9x");
        args.add("--rewit.security.password-pepper=prod-pepper-secret-entropia-csprng-Rm7!Vx2@Np9#");
        args.add("--rewit.security.refresh-token-secret=prod-refresh-secret-entropia-csprng-Tk5!Bj8@Wd3#");
        args.add("--rewit.security.moderation-audit-secret=prod-mod-audit-secret-entropia-csprng-Kp9!Zx4@Qc1#");
        args.add("--rewit.rate-limit.key-secret=prod-rate-limit-secret-entropia-Hy6$Vb3%Nm1&8z");
        args.add("--rewit.account.purge.enabled=true");
        args.add("--rewit.account.email-reservation-secret=prod-email-reservation-secret-csprng-Gf2!Rt7@Jk4#");
        // R304-R1-01 & R304-R1-02 via CLI + SPRING_APPLICATION_JSON
        args.add("--management.endpoint.health.status.order=UP,DOWN,OUT_OF_SERVICE,UNKNOWN");
        args.add("--management.endpoint.health.group.readiness.status.order=UP,DOWN");
        args.add("--management.endpoint.health.group.readiness.status.http-mapping.UNKNOWN=200");
        args.add("--management.endpoint.health.access=none");
        args.add("--spring.application.json={\"management\":{\"endpoint\":{\"health\":{\"probes\":{\"add-additional-paths\":false}}}}}");

        ProductionEnvironmentValidator.InvalidConfigurationException ex = assertThrows(
                ProductionEnvironmentValidator.InvalidConfigurationException.class,
                () -> {
                    try (ConfigurableApplicationContext ignored = app.run(args.toArray(String[]::new))) {
                        // Nunca deve subir
                    }
                }
        );

        assertTrue(ex.getMessage().contains("management.endpoint.health.status.order"));
        assertTrue(ex.getMessage().contains("management.endpoint.health.group.readiness.status.order"));
        assertTrue(ex.getMessage().contains("management.endpoint.health.group.readiness.status.http-mapping"));
        assertTrue(ex.getMessage().contains("endpoint 'health'"));
        assertTrue(ex.getMessage().contains("management.endpoint.health.probes.add-additional-paths"));
        assertFalse(beanFactoryPostProcessed.get(),
                "Validação R304-R1 deve falhar antes do refresh/Flyway/DataSource");
        assertPortNotListening(port);
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void assertPortNotListening(int port) {
        assertThrows(IOException.class, () -> {
            try (Socket ignored = new Socket("127.0.0.1", port)) {
                // Se conectar, a porta HTTP abriu indevidamente
            }
        }, "A porta HTTP " + port + " não pode ter sido aberta quando a validação falha");
    }
}
