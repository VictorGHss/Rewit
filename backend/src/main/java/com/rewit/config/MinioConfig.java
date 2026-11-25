package com.rewit.config;

import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuração do cliente S3 compatível (MinIO/SeaweedFS/S3/R2).
 *
 * <p>Sem fallbacks inseguros no código: endpoint, credenciais e região (opcional) devem ser
 * fornecidos explicitamente pelo profile ativo (ex.: {@code application-local.yml},
 * {@code application-external-test.yml} ou {@code application-prod.yml}).
 */
@Configuration
public class MinioConfig {

    private final String endpoint;
    private final String accessKey;
    private final String secretKey;
    private final String region;

    @Autowired
    public MinioConfig(
            @Value("${rewit.minio.endpoint}") String endpoint,
            @Value("${rewit.minio.access-key}") String accessKey,
            @Value("${rewit.minio.secret-key}") String secretKey,
            @Value("${rewit.minio.region:}") String region
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
    }

    @Bean
    public MinioClient minioClient() {
        MinioClient.Builder builder = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey);
        if (region != null) {
            builder.region(region);
        }
        return builder.build();
    }

    public String getRegion() {
        return region;
    }
}
