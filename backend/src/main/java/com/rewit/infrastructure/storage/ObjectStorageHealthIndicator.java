package com.rewit.infrastructure.storage;

import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import io.minio.errors.ErrorResponseException;
import io.minio.errors.MinioException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.autoconfigure.contributor.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

/**
 * Indicador de saúde estritamente somente leitura para o bucket configurado no Object Storage S3/R2/MinIO
 * (F2-DEPLOY-03.4).
 *
 * <p>Executa exclusivamente {@code bucketExists} (requisição HTTP {@code HEAD} no bucket), sem criar buckets,
 * gravar objetos, alterar políticas/ACLs ou executar qualquer mutação. Utiliza um cliente MinIO dedicado com
 * timeouts curtos reutilizando as mesmas credenciais/endpoint de {@link com.rewit.config.MinioConfig} e cache
 * concorrente seguro de TTL curto para evitar rajadas de chamadas externas em probes simultâneas.
 *
 * <p>Nunca expõe nomes de buckets, endpoints, credenciais ou mensagens brutas do SDK no objeto {@link Health}
 * nem nos logs.
 */
@Component("objectStorageHealthIndicator")
@ConditionalOnEnabledHealthIndicator("object-storage")
public class ObjectStorageHealthIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(ObjectStorageHealthIndicator.class);

    static final Duration DEFAULT_CACHE_TTL = Duration.ofSeconds(5);

    private final MinioClient minioClient;
    private final String bucketName;
    private final Duration cacheTtl;
    private final Clock clock;

    private final Object lock = new Object();
    private volatile CachedHealth cached;

    @Autowired
    public ObjectStorageHealthIndicator(
            @Qualifier("objectStorageHealthMinioClient") MinioClient minioClient,
            @Value("${rewit.minio.bucket-name}") String bucketName,
            @Value("${rewit.minio.health.cache-ttl:5s}") Duration cacheTtl
    ) {
        this(minioClient, bucketName, cacheTtl, Clock.systemUTC());
    }

    public ObjectStorageHealthIndicator(
            MinioClient minioClient,
            String bucketName,
            Duration cacheTtl,
            Clock clock
    ) {
        this.minioClient = Objects.requireNonNull(minioClient, "MinioClient must not be null");
        if (bucketName == null || bucketName.isBlank()) {
            throw new IllegalStateException("rewit.minio.bucket-name (OBJECT_STORAGE_BUCKET) é obrigatório.");
        }
        this.bucketName = bucketName.trim();
        this.cacheTtl = (cacheTtl == null || cacheTtl.isNegative()) ? Duration.ZERO : cacheTtl;
        this.clock = Objects.requireNonNull(clock, "Clock must not be null");
    }

    @Override
    public Health health() {
        long now = clock.millis();
        long ttlMillis = cacheTtl.toMillis();
        if (ttlMillis > 0) {
            CachedHealth snapshot = cached;
            if (snapshot != null && now < snapshot.expiresAtMillis()) {
                return snapshot.health();
            }
            synchronized (lock) {
                snapshot = cached;
                now = clock.millis();
                if (snapshot != null && now < snapshot.expiresAtMillis()) {
                    return snapshot.health();
                }
                Health fresh = checkBucketReadOnly();
                cached = new CachedHealth(fresh, clock.millis() + ttlMillis);
                return fresh;
            }
        }
        return checkBucketReadOnly();
    }

    private Health checkBucketReadOnly() {
        try {
            boolean exists = minioClient.bucketExists(
                    BucketExistsArgs.builder().bucket(bucketName).build()
            );
            if (exists) {
                return Health.up().build();
            }
            log.warn("Object Storage readiness indisponível: bucket configurado não existe.");
            return Health.down().build();
        } catch (ErrorResponseException e) {
            String code = (e.errorResponse() != null && e.errorResponse().code() != null)
                    ? e.errorResponse().code()
                    : "ErrorResponseException";
            log.warn("Object Storage readiness indisponível (erro={}, codigo={})",
                    e.getClass().getSimpleName(), code);
            return Health.down().build();
        } catch (MinioException | IOException | GeneralSecurityException | RuntimeException e) {
            log.warn("Object Storage readiness indisponível (erro={})", e.getClass().getSimpleName());
            return Health.down().build();
        }
    }

    private record CachedHealth(Health health, long expiresAtMillis) {
    }
}
