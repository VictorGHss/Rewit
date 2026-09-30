package com.rewit.config;

import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuração do cliente S3 MinIO para desenvolvimento local e upload de mídias.
 */
@Configuration
public class MinioConfig {

    @Value("${rewit.minio.endpoint:${OBJECT_STORAGE_ENDPOINT:http://localhost:8333}}")
    private String endpoint;

    @Value("${rewit.minio.access-key:${OBJECT_STORAGE_ACCESS_KEY:change-me}}")
    private String accessKey;

    @Value("${rewit.minio.secret-key:${OBJECT_STORAGE_SECRET_KEY:change-me}}")
    private String secretKey;

    @Bean
    public MinioClient minioClient() {
        return MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
    }
}
