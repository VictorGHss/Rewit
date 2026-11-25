package com.rewit.infrastructure.observability;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveBucketArgs;
import io.minio.RemoveObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.ErrorResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.Mockito;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.data.redis.health.DataRedisHealthIndicator;
import org.springframework.boot.data.redis.health.DataRedisReactiveHealthIndicator;
import org.springframework.boot.health.contributor.CompositeHealthContributor;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthContributor;
import org.springframework.boot.health.contributor.HealthContributors;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.ReactiveHealthContributor;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.ReactiveRedisClusterConnection;
import org.springframework.data.redis.connection.ReactiveRedisConnection;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.connection.RedisClusterConnection;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisSentinelConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes de integração ponta a ponta de Liveness e Readiness (F2-DEPLOY-03.4):
 * sobe o servidor HTTP real em portas distintas (API e management), atravessando a
 * {@code SecurityFilterChain} efetiva e os indicadores reais de PostgreSQL, Redis e Object Storage S3.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "management.server.port=0",
                "management.endpoints.web.exposure.include=health,info,prometheus",
                "management.endpoint.health.access=read_only",
                "management.endpoint.health.show-details=never",
                "management.endpoint.health.show-components=never",
                "management.endpoint.health.probes.enabled=true",
                "management.endpoint.health.probes.add-additional-paths=true",
                "management.endpoint.health.group.liveness.include=livenessState",
                "management.endpoint.health.group.liveness.show-details=never",
                "management.endpoint.health.group.liveness.show-components=never",
                "management.endpoint.health.group.readiness.include=readinessState,db,redis,objectStorage",
                "management.endpoint.health.group.readiness.show-details=never",
                "management.endpoint.health.group.readiness.show-components=never",
                "management.endpoint.health.group.readiness.status.order=DOWN,OUT_OF_SERVICE,UNKNOWN,UP",
                "management.endpoint.health.group.readiness.status.http-mapping.DOWN=503",
                "management.endpoint.health.group.readiness.status.http-mapping.OUT_OF_SERVICE=503",
                "management.endpoint.health.group.readiness.status.http-mapping.UNKNOWN=503",
                "management.health.defaults.enabled=true",
                "management.health.db.enabled=true",
                "management.health.db.ignore-routing-data-sources=true",
                "management.health.redis.enabled=true",
                "management.health.object-storage.enabled=true",
                "rewit.minio.health.cache-ttl=0s",
                "server.tomcat.basedir=target/tomcat-health-probes-test"
        }
)
@Import(HealthProbesIntegrationTest.FaultInjectionConfig.class)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Testes de Integração: Liveness, Readiness, /livez, /readyz e isolamento de falhas (F2-DEPLOY-03.4)")
class HealthProbesIntegrationTest {

    private static final AtomicReference<String> FAILING_DB_POOL = new AtomicReference<>(null);
    private static final AtomicReference<String> UNKNOWN_DB_POOL = new AtomicReference<>(null);
    private static final AtomicReference<Set<String>> OBSERVED_DB_POOLS = new AtomicReference<>(Set.of());
    private static final AtomicBoolean SIMULATE_REDIS_DOWN = new AtomicBoolean(false);

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    private int apiPort;

    @LocalManagementPort
    private int managementPort;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("dbHealthContributor")
    private HealthContributor dbHealthContributor;

    @Value("${rewit.minio.bucket-name}")
    private String configuredBucket;

    @MockitoSpyBean(name = "objectStorageHealthMinioClient")
    private MinioClient objectStorageHealthMinioClient;

    @BeforeAll
    void ensureDisposableTestBucketExistsBeforeProbes() throws Exception {
        // Apenas garante que o bucket descartável de teste esteja pronto no SeaweedFS antes das asserções UP
        if (!objectStorageHealthMinioClient.bucketExists(BucketExistsArgs.builder().bucket(configuredBucket).build())) {
            objectStorageHealthMinioClient.makeBucket(MakeBucketArgs.builder().bucket(configuredBucket).build());
        }
        Mockito.clearInvocations(objectStorageHealthMinioClient);
    }

    @AfterEach
    void restoreHealthyState() {
        FAILING_DB_POOL.set(null);
        UNKNOWN_DB_POOL.set(null);
        SIMULATE_REDIS_DOWN.set(false);
        Mockito.reset(objectStorageHealthMinioClient);
        AvailabilityChangeEvent.publish(eventPublisher, this, ReadinessState.ACCEPTING_TRAFFIC);
    }

    @Test
    @DisplayName("8.1 & 8.2: Com todas as dependências saudáveis, liveness e readiness respondem 200 UP sem detalhes sensíveis")
    void livenessAndReadinessAreUpWithoutSensitiveDetailsWhenDependenciesAreHealthy() throws Exception {
        assertNotEquals(apiPort, managementPort);

        for (String url : new String[]{
                management("/actuator/health/liveness"),
                management("/actuator/health/readiness"),
                api("/livez"),
                api("/readyz")
        }) {
            HttpResponse<String> response = get(url);
            assertEquals(200, response.statusCode(), "Esperado 200 em " + url);
            assertEquals("{\"status\":\"UP\"}", response.body().trim(), "Resposta deve ocultar detalhes em " + url);
            assertNoSensitiveLeak(response.body());
        }

        // Garante que a probe S3 executou apenas bucketExists (somente leitura)
        verify(objectStorageHealthMinioClient, never()).makeBucket(any(MakeBucketArgs.class));
        verify(objectStorageHealthMinioClient, never()).putObject(any(PutObjectArgs.class));
        verify(objectStorageHealthMinioClient, never()).removeObject(any(RemoveObjectArgs.class));
        verify(objectStorageHealthMinioClient, never()).removeBucket(any(RemoveBucketArgs.class));
    }

    @Test
    @DisplayName("8.1 & 8.2: Redis indisponível derruba readiness (503 DOWN), mas mantém liveness 200 UP e processo vivo")
    void redisFailureMakesReadinessDownWhileKeepingLivenessUp() throws Exception {
        SIMULATE_REDIS_DOWN.set(true);

        HttpResponse<String> readinessMgmt = get(management("/actuator/health/readiness"));
        HttpResponse<String> readyzApi = get(api("/readyz"));
        HttpResponse<String> livenessMgmt = get(management("/actuator/health/liveness"));
        HttpResponse<String> livezApi = get(api("/livez"));
        HttpResponse<String> infoMgmt = get(management("/actuator/info"));

        assertEquals(503, readinessMgmt.statusCode());
        assertEquals("{\"status\":\"DOWN\"}", readinessMgmt.body().trim());
        assertNoSensitiveLeak(readinessMgmt.body());

        assertEquals(503, readyzApi.statusCode());
        assertEquals("{\"status\":\"DOWN\"}", readyzApi.body().trim());
        assertNoSensitiveLeak(readyzApi.body());

        assertEquals(200, livenessMgmt.statusCode());
        assertEquals("{\"status\":\"UP\"}", livenessMgmt.body().trim());
        assertEquals(200, livezApi.statusCode());
        assertEquals("{\"status\":\"UP\"}", livezApi.body().trim());
        assertEquals(200, infoMgmt.statusCode(), "Aplicação permanece viva durante indisponibilidade do Redis");
    }

    @Test
    @DisplayName("R304-04: dbHealthContributor expõe exatamente os 3 pools físicos (sem duplicação do RoutingDataSource) e falha isolada de cada pool derruba readiness mantendo liveness UP")
    void postgresCompositeCoversAllThreePhysicalPoolsAndEachIsolatedPoolFailureMakesReadinessDown() throws Exception {
        assertTrue(dbHealthContributor instanceof CompositeHealthContributor,
                "dbHealthContributor deve ser CompositeHealthContributor cobrindo os 3 pools físicos");

        Set<String> expectedPools = Set.of("appDataSource", "mediaLifecycleDataSource", "moderationDataSource");
        assertEquals(expectedPools, OBSERVED_DB_POOLS.get(),
                "dbHealthContributor deve conter exatamente os 3 pools físicos, sem duplicar ModerationRoutingDataSource ('dataSource')");
        assertFalse(OBSERVED_DB_POOLS.get().contains("dataSource"),
                "ModerationRoutingDataSource não deve duplicar nem ocultar os pools físicos");

        for (String poolName : new String[]{"appDataSource", "mediaLifecycleDataSource", "moderationDataSource"}) {
            FAILING_DB_POOL.set(poolName);

            HttpResponse<String> readinessMgmt = get(management("/actuator/health/readiness"));
            HttpResponse<String> readyzApi = get(api("/readyz"));
            HttpResponse<String> livenessMgmt = get(management("/actuator/health/liveness"));
            HttpResponse<String> livezApi = get(api("/livez"));

            assertEquals(503, readinessMgmt.statusCode(), "Falha isolada em " + poolName + " deve derrubar /actuator/health/readiness");
            assertEquals("{\"status\":\"DOWN\"}", readinessMgmt.body().trim());
            assertNoSensitiveLeak(readinessMgmt.body());

            assertEquals(503, readyzApi.statusCode(), "Falha isolada em " + poolName + " deve derrubar /readyz");
            assertEquals("{\"status\":\"DOWN\"}", readyzApi.body().trim());
            assertNoSensitiveLeak(readyzApi.body());

            assertEquals(200, livenessMgmt.statusCode(), "Liveness deve permanecer UP quando " + poolName + " falha");
            assertEquals("{\"status\":\"UP\"}", livenessMgmt.body().trim());
            assertEquals(200, livezApi.statusCode(), "/livez deve permanecer UP quando " + poolName + " falha");
            assertEquals("{\"status\":\"UP\"}", livezApi.body().trim());

            FAILING_DB_POOL.set(null);
            assertEquals(200, get(management("/actuator/health/readiness")).statusCode(),
                    "Readiness deve recuperar após restauração de " + poolName);
        }
    }

    @Test
    @DisplayName("R304-R1-01: Status real UNKNOWN em dependência obrigatória da readiness retorna HTTP 503 (nunca 200) mantendo liveness 200 UP")
    void unknownStatusOnMandatoryReadinessDependencyReturnsHttp503WhileLivenessStaysUp() throws Exception {
        UNKNOWN_DB_POOL.set("appDataSource");

        HttpResponse<String> readinessMgmt = get(management("/actuator/health/readiness"));
        HttpResponse<String> readyzApi = get(api("/readyz"));
        HttpResponse<String> livenessMgmt = get(management("/actuator/health/liveness"));
        HttpResponse<String> livezApi = get(api("/livez"));

        assertEquals(503, readinessMgmt.statusCode(), "UNKNOWN na readiness deve responder HTTP 503");
        assertEquals("{\"status\":\"UNKNOWN\"}", readinessMgmt.body().trim());
        assertNoSensitiveLeak(readinessMgmt.body());

        assertEquals(503, readyzApi.statusCode(), "UNKNOWN em /readyz deve responder HTTP 503");
        assertEquals("{\"status\":\"UNKNOWN\"}", readyzApi.body().trim());
        assertNoSensitiveLeak(readyzApi.body());

        assertEquals(200, livenessMgmt.statusCode());
        assertEquals("{\"status\":\"UP\"}", livenessMgmt.body().trim());
        assertEquals(200, livezApi.statusCode());
        assertEquals("{\"status\":\"UP\"}", livezApi.body().trim());
    }

    @Test
    @DisplayName("8.2 & 8.3: Bucket S3 inexistente derruba readiness (503 DOWN), mantém liveness 200 UP e não cria bucket")
    void missingS3BucketMakesReadinessDownWithoutAutoCreatingBucket() throws Exception {
        doReturn(false).when(objectStorageHealthMinioClient).bucketExists(any(BucketExistsArgs.class));

        HttpResponse<String> readinessMgmt = get(management("/actuator/health/readiness"));
        HttpResponse<String> readyzApi = get(api("/readyz"));
        HttpResponse<String> livenessMgmt = get(management("/actuator/health/liveness"));
        HttpResponse<String> livezApi = get(api("/livez"));

        assertEquals(503, readinessMgmt.statusCode());
        assertEquals("{\"status\":\"DOWN\"}", readinessMgmt.body().trim());
        assertNoSensitiveLeak(readinessMgmt.body());

        assertEquals(503, readyzApi.statusCode());
        assertEquals("{\"status\":\"DOWN\"}", readyzApi.body().trim());

        assertEquals(200, livenessMgmt.statusCode());
        assertEquals(200, livezApi.statusCode());

        verify(objectStorageHealthMinioClient, never()).makeBucket(any(MakeBucketArgs.class));
        verify(objectStorageHealthMinioClient, never()).putObject(any(PutObjectArgs.class));
        verify(objectStorageHealthMinioClient, never()).removeObject(any(RemoveObjectArgs.class));
    }

    @Test
    @DisplayName("8.2 & 8.3: Endpoint S3 indisponível/timeout e AccessDenied derrubam readiness sem vazar detalhes e sem afetar liveness")
    void unreachableOrDeniedS3MakesReadinessDownWhileKeepingLivenessUp() throws Exception {
        // 1. Timeout / endpoint indisponível
        doThrow(new SocketTimeoutException("timeout contacting https://internal-s3.prod:9000"))
                .when(objectStorageHealthMinioClient).bucketExists(any(BucketExistsArgs.class));

        HttpResponse<String> timeoutReadiness = get(management("/actuator/health/readiness"));
        assertEquals(503, timeoutReadiness.statusCode());
        assertEquals("{\"status\":\"DOWN\"}", timeoutReadiness.body().trim());
        assertNoSensitiveLeak(timeoutReadiness.body());
        assertEquals(200, get(management("/actuator/health/liveness")).statusCode());
        assertEquals(200, get(api("/livez")).statusCode());

        // 2. Credencial sem permissão (AccessDenied)
        ErrorResponse deniedResp = new ErrorResponse(
                "AccessDenied", "Access Denied for AKIASECRET", configuredBucket, null, "/" + configuredBucket, "r1", "h1");
        ErrorResponseException accessDenied = mock(ErrorResponseException.class);
        when(accessDenied.errorResponse()).thenReturn(deniedResp);
        doThrow(accessDenied).when(objectStorageHealthMinioClient).bucketExists(any(BucketExistsArgs.class));

        HttpResponse<String> deniedReadiness = get(api("/readyz"));
        assertEquals(503, deniedReadiness.statusCode());
        assertEquals("{\"status\":\"DOWN\"}", deniedReadiness.body().trim());
        assertNoSensitiveLeak(deniedReadiness.body());
        assertEquals(200, get(api("/livez")).statusCode());

        verify(objectStorageHealthMinioClient, never()).makeBucket(any(MakeBucketArgs.class));
        verify(objectStorageHealthMinioClient, never()).putObject(any(PutObjectArgs.class));
    }

    @Test
    @DisplayName("8.2: Transição de ReadinessState (REFUSING_TRAFFIC) derruba readiness para 503 mantendo liveness 200 UP")
    void readinessStateTransitionAffectsReadinessOnly() throws Exception {
        AvailabilityChangeEvent.publish(eventPublisher, this, ReadinessState.REFUSING_TRAFFIC);

        HttpResponse<String> readinessMgmt = get(management("/actuator/health/readiness"));
        HttpResponse<String> readyzApi = get(api("/readyz"));
        HttpResponse<String> livenessMgmt = get(management("/actuator/health/liveness"));
        HttpResponse<String> livezApi = get(api("/livez"));

        assertEquals(503, readinessMgmt.statusCode());
        assertEquals("{\"status\":\"OUT_OF_SERVICE\"}", readinessMgmt.body().trim());
        assertEquals(503, readyzApi.statusCode());
        assertEquals("{\"status\":\"OUT_OF_SERVICE\"}", readyzApi.body().trim());
        assertEquals(200, livenessMgmt.statusCode());
        assertEquals(200, livezApi.statusCode());
    }

    @Test
    @DisplayName("8.4: Segurança HTTP na SecurityFilterChain real — probes públicas sem auth; endpoints administrativos bloqueados")
    void securityFilterChainAllowsOnlyProbesAndBlocksAdminEndpoints() throws Exception {
        // Probes públicas sem autenticação
        assertEquals(200, get(management("/actuator/health/liveness")).statusCode());
        assertEquals(200, get(management("/actuator/health/readiness")).statusCode());
        assertEquals(200, get(api("/livez")).statusCode());
        assertEquals(200, get(api("/readyz")).statusCode());

        // Endpoints administrativos continuam bloqueados na porta de management e na porta da API
        for (String adminPath : new String[]{
                "/actuator/env", "/actuator/beans", "/actuator/configprops",
                "/actuator/heapdump", "/actuator/threaddump", "/actuator/mappings",
                "/actuator/loggers", "/actuator/metrics"
        }) {
            assertNotEquals(200, get(management(adminPath)).statusCode(), "Management não deve expor " + adminPath);
            assertEquals(401, get(api(adminPath)).statusCode(), "API não deve liberar " + adminPath);
        }

        // /actuator/health não é servido na porta principal da API (somente /livez e /readyz)
        assertEquals(401, get(api("/actuator/health")).statusCode());
        assertEquals(401, get(api("/actuator/prometheus")).statusCode());
    }

    private void assertNoSensitiveLeak(String body) {
        assertFalse(body.contains("components"));
        assertFalse(body.contains("details"));
        assertFalse(body.contains(configuredBucket));
        assertFalse(body.contains("redis"));
        assertFalse(body.contains("postgres"));
        assertFalse(body.contains("Exception"));
        assertFalse(body.contains("AKIA"));
        assertFalse(body.contains("http://"));
        assertFalse(body.contains("https://"));
    }

    private HttpResponse<String> get(String url) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private String api(String path) {
        return "http://127.0.0.1:" + apiPort + path;
    }

    private String management(String path) {
        return "http://127.0.0.1:" + managementPort + path;
    }

    @TestConfiguration
    static class FaultInjectionConfig {

        @Bean
        static BeanPostProcessor healthFaultInjector(BeanFactory beanFactory) {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
                    if ("dbHealthContributor".equals(beanName) && bean instanceof CompositeHealthContributor composite) {
                        Map<String, HealthContributor> wrappedChildren = new LinkedHashMap<>();
                        for (HealthContributors.Entry entry : composite) {
                            String poolName = entry.name();
                            HealthContributor child = entry.contributor();
                            wrappedChildren.put(poolName, (HealthIndicator) () -> {
                                if (poolName.equals(FAILING_DB_POOL.get())) {
                                    return Health.down().build();
                                }
                                if (poolName.equals(UNKNOWN_DB_POOL.get())) {
                                    return Health.unknown().build();
                                }
                                return evaluateRealContributor(child);
                            });
                        }
                        OBSERVED_DB_POOLS.set(Set.copyOf(wrappedChildren.keySet()));
                        return new CompositeHealthContributor() {
                            @Override
                            public HealthContributor getContributor(String name) {
                                return wrappedChildren.get(name);
                            }

                            @Override
                            public Stream<HealthContributors.Entry> stream() {
                                return wrappedChildren.entrySet().stream()
                                        .map(e -> new HealthContributors.Entry(e.getKey(), e.getValue()));
                            }
                        };
                    }
                    if ("redisHealthContributor".equals(beanName)) {
                        if (bean instanceof ReactiveHealthContributor) {
                            ReactiveRedisConnectionFactory realReactive =
                                    beanFactory.getBean(ReactiveRedisConnectionFactory.class);
                            // Exercita o DataRedisReactiveHealthIndicator nativo real (usado pelo Lettuce + Reactor)
                            return new DataRedisReactiveHealthIndicator(
                                    new FaultInjectableReactiveRedisConnectionFactory(realReactive));
                        }
                        if (bean instanceof HealthContributor) {
                            RedisConnectionFactory realFactory = beanFactory.getBean(RedisConnectionFactory.class);
                            return new DataRedisHealthIndicator(new FaultInjectableRedisConnectionFactory(realFactory));
                        }
                    }
                    return bean;
                }
            };
        }

        private static final class FaultInjectableReactiveRedisConnectionFactory implements ReactiveRedisConnectionFactory {
            private final ReactiveRedisConnectionFactory delegate;

            FaultInjectableReactiveRedisConnectionFactory(ReactiveRedisConnectionFactory delegate) {
                this.delegate = delegate;
            }

            @Override
            public ReactiveRedisConnection getReactiveConnection() {
                if (SIMULATE_REDIS_DOWN.get()) {
                    throw new RedisConnectionFailureException("Cannot connect to redis://secret-redis:6379");
                }
                return delegate.getReactiveConnection();
            }

            @Override
            public ReactiveRedisClusterConnection getReactiveClusterConnection() {
                if (SIMULATE_REDIS_DOWN.get()) {
                    throw new RedisConnectionFailureException("Cannot connect to redis://secret-redis:6379");
                }
                return delegate.getReactiveClusterConnection();
            }

            @Override
            public DataAccessException translateExceptionIfPossible(RuntimeException ex) {
                return delegate.translateExceptionIfPossible(ex);
            }
        }

        private static final class FaultInjectableRedisConnectionFactory implements RedisConnectionFactory {
            private final RedisConnectionFactory delegate;

            FaultInjectableRedisConnectionFactory(RedisConnectionFactory delegate) {
                this.delegate = delegate;
            }

            @Override
            public RedisConnection getConnection() {
                if (SIMULATE_REDIS_DOWN.get()) {
                    throw new RedisConnectionFailureException("Cannot connect to redis://secret-redis:6379");
                }
                return delegate.getConnection();
            }

            @Override
            public RedisClusterConnection getClusterConnection() {
                if (SIMULATE_REDIS_DOWN.get()) {
                    throw new RedisConnectionFailureException("Cannot connect to redis://secret-redis:6379");
                }
                return delegate.getClusterConnection();
            }

            @Override
            public boolean getConvertPipelineAndTxResults() {
                return delegate.getConvertPipelineAndTxResults();
            }

            @Override
            public RedisSentinelConnection getSentinelConnection() {
                return delegate.getSentinelConnection();
            }

            @Override
            public DataAccessException translateExceptionIfPossible(RuntimeException ex) {
                return delegate.translateExceptionIfPossible(ex);
            }
        }

        private static Health evaluateRealContributor(HealthContributor contributor) {
            if (contributor instanceof HealthIndicator indicator) {
                return indicator.health();
            }
            if (contributor instanceof CompositeHealthContributor composite) {
                for (HealthContributors.Entry entry : composite) {
                    Health child = evaluateRealContributor(entry.contributor());
                    if (!Status.UP.equals(child.getStatus())) {
                        return child;
                    }
                }
                return Health.up().build();
            }
            return Health.unknown().build();
        }
    }
}
