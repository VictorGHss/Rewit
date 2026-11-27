package com.rewit.config;

import io.minio.MinioClient;
import okhttp3.OkHttpClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Duration;

/**
 * Configuração do cliente S3 compatível (MinIO/SeaweedFS/S3/R2).
 *
 * <p>Sem fallbacks inseguros no código: endpoint, credenciais e região (opcional) devem ser
 * fornecidos explicitamente pelo profile ativo (ex.: {@code application-local.yml},
 * {@code application-external-test.yml} ou {@code application-prod.yml}).
 */
@Configuration
public class MinioConfig {

    static final Duration DEFAULT_HEALTH_TIMEOUT = Duration.ofSeconds(3);

    private final String endpoint;
    private final String accessKey;
    private final String secretKey;
    private final String region;
    private final Duration healthTimeout;

    @Autowired
    public MinioConfig(
            @Value("${rewit.minio.endpoint}") String endpoint,
            @Value("${rewit.minio.access-key}") String accessKey,
            @Value("${rewit.minio.secret-key}") String secretKey,
            @Value("${rewit.minio.region:}") String region,
            @Value("${rewit.minio.health.timeout:3s}") Duration healthTimeout
    ) {
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalStateException("rewit.minio.endpoint (OBJECT_STORAGE_ENDPOINT) é obrigatório.");
        }
        if (accessKey == null || accessKey.isBlank()) {
            throw new IllegalStateException("rewit.minio.access-key (OBJECT_STORAGE_ACCESS_KEY) é obrigatório.");
        }
        if (secretKey == null || secretKey.isBlank()) {
            throw new IllegalStateException("rewit.minio.secret-key (OBJECT_STORAGE_SECRET_KEY) é obrigatório.");
        }
        this.endpoint = endpoint.trim();
        this.accessKey = accessKey.trim();
        this.secretKey = secretKey.trim();
        this.region = (region != null && !region.isBlank()) ? region.trim() : null;
        this.healthTimeout = (healthTimeout == null || healthTimeout.isZero() || healthTimeout.isNegative())
                ? DEFAULT_HEALTH_TIMEOUT
                : healthTimeout;
    }

    public MinioConfig(String endpoint, String accessKey, String secretKey, String region) {
        this(endpoint, accessKey, secretKey, region, DEFAULT_HEALTH_TIMEOUT);
    }

    @Bean
    @Primary
    public MinioClient minioClient() {
        return baseClientBuilder().build();
    }

    /**
     * Cliente S3 dedicado exclusivamente às probes somente leitura de readiness, reutilizando o mesmo
     * endpoint, região e credenciais, mas com timeouts HTTP curtos e sem retries automáticos de conexão.
     */
    @Bean(name = "objectStorageHealthMinioClient")
    public MinioClient objectStorageHealthMinioClient() {
        Duration connectAndIo = healthTimeout.compareTo(Duration.ofSeconds(2)) > 0
                ? Duration.ofSeconds(2)
                : healthTimeout;
        OkHttpClient healthHttpClient = new OkHttpClient.Builder()
                .connectTimeout(connectAndIo)
                .readTimeout(connectAndIo)
                .writeTimeout(connectAndIo)
                .callTimeout(healthTimeout)
                .retryOnConnectionFailure(false)
                .build();
        return baseClientBuilder()
                .httpClient(healthHttpClient, true)
                .build();
    }

    private MinioClient.Builder baseClientBuilder() {
        MinioClient.Builder builder = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey);
        if (region != null) {
            builder.region(region);
        }
        return builder;
    }

    public String getRegion() {
        return region;
    }

    public Duration getHealthTimeout() {
        return healthTimeout;
    }
}
