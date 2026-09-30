package com.rewit.infrastructure.storage;

import com.rewit.application.port.ObjectStoragePort;
import com.rewit.common.exception.BusinessException;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Adaptador de infraestrutura para armazenamento de objetos utilizando MinIO/SeaweedFS (S3 API).
 * Implementa a porta ObjectStoragePort isolando o restante da aplicação do SDK do MinIO.
 */
@Component
public class MinioStorageAdapter implements ObjectStoragePort {

    private static final Logger log = LoggerFactory.getLogger(MinioStorageAdapter.class);

    private final MinioClient minioClient;
    private final String bucketName;
    private final AtomicBoolean bucketChecked = new AtomicBoolean(false);

    public MinioStorageAdapter(MinioClient minioClient,
                               @Value("${rewit.minio.bucket-name:rewit-local}") String bucketName) {
        this.minioClient = Objects.requireNonNull(minioClient, "MinioClient must not be null");
        this.bucketName = Objects.requireNonNull(bucketName, "Bucket name must not be null");
    }

    private void ensureBucketExists() {
        if (bucketChecked.get()) {
            return;
        }
        synchronized (bucketChecked) {
            if (bucketChecked.get()) {
                return;
            }
            try {
                boolean found = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build());
                if (!found) {
                    minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
                    log.info("Bucket '{}' criado com sucesso no Object Storage.", bucketName);
                }
                bucketChecked.set(true);
            } catch (Exception e) {
                log.error("Erro ao verificar/criar bucket '{}' no Object Storage: {}", bucketName, e.getMessage());
                throw new BusinessException("Falha na inicialização do serviço de armazenamento",
                        HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE");
            }
        }
    }

    @Override
    public void put(String key, String contentType, byte[] data) {
        Objects.requireNonNull(key, "Storage key cannot be null");
        Objects.requireNonNull(data, "Object data cannot be null");
        ensureBucketExists();

        try (InputStream inputStream = new ByteArrayInputStream(data)) {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucketName)
                            .object(key)
                            .stream(inputStream, data.length, -1)
                            .contentType(contentType != null ? contentType : "application/octet-stream")
                            .build()
            );
        } catch (Exception e) {
            log.error("Erro ao gravar objeto '{}' no bucket '{}': {}", key, bucketName, e.getMessage());
            throw new BusinessException("Falha ao persistir arquivo no armazenamento de objetos",
                    HttpStatus.INTERNAL_SERVER_ERROR, "STORAGE_WRITE_FAILED");
        }
    }

    @Override
    public byte[] get(String key) {
        Objects.requireNonNull(key, "Storage key cannot be null");
        ensureBucketExists();

        try (InputStream stream = minioClient.getObject(
                GetObjectArgs.builder()
                        .bucket(bucketName)
                        .object(key)
                        .build())) {
            return stream.readAllBytes();
        } catch (ErrorResponseException e) {
            if ("NoSuchKey".equals(e.errorResponse().code()) || "ResourceNotFound".equals(e.errorResponse().code())) {
                throw new BusinessException("Arquivo de mídia não encontrado no armazenamento",
                        HttpStatus.NOT_FOUND, "MEDIA_OBJECT_NOT_FOUND");
            }
            log.error("Erro ao obter objeto '{}' do storage: {}", key, e.getMessage());
            throw new BusinessException("Falha ao ler arquivo do armazenamento",
                    HttpStatus.INTERNAL_SERVER_ERROR, "STORAGE_READ_FAILED");
        } catch (Exception e) {
            log.error("Erro inesperado ao obter objeto '{}' do storage: {}", key, e.getMessage());
            throw new BusinessException("Falha ao ler arquivo do armazenamento",
                    HttpStatus.INTERNAL_SERVER_ERROR, "STORAGE_READ_FAILED");
        }
    }

    @Override
    public void delete(String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        try {
            minioClient.removeObject(
                    RemoveObjectArgs.builder()
                            .bucket(bucketName)
                            .object(key)
                            .build()
            );
        } catch (Exception e) {
            log.warn("Falha ao remover objeto '{}' do storage: {}", key, e.getMessage());
            // Exclusão é melhor esforço / idempotente
        }
    }

    @Override
    public boolean exists(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        ensureBucketExists();
        try {
            minioClient.statObject(
                    StatObjectArgs.builder()
                            .bucket(bucketName)
                            .object(key)
                            .build()
            );
            return true;
        } catch (ErrorResponseException e) {
            if ("NoSuchKey".equals(e.errorResponse().code()) || "ResourceNotFound".equals(e.errorResponse().code())) {
                return false;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }
}
