package com.rewit.infrastructure.storage;

import com.rewit.application.dto.storage.ObjectDeletionResult;
import com.rewit.application.dto.storage.StorageReconciliationDtos.StoredObject;
import com.rewit.application.dto.storage.StorageReconciliationDtos.StoredObjectPage;
import com.rewit.application.port.ObjectStorageListingPort;
import com.rewit.application.port.ObjectStoragePort;
import com.rewit.common.exception.BusinessException;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.errors.InsufficientDataException;
import io.minio.errors.InternalException;
import io.minio.errors.InvalidResponseException;
import io.minio.errors.MinioException;
import io.minio.errors.ServerException;
import io.minio.errors.XmlParserException;
import io.minio.messages.Item;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Adaptador de infraestrutura para armazenamento de objetos utilizando MinIO/SeaweedFS (S3 API).
 * Implementa a porta ObjectStoragePort isolando o restante da aplicação do SDK do MinIO.
 */
@Component
public class MinioStorageAdapter implements ObjectStoragePort, ObjectStorageListingPort {

    private static final Logger log = LoggerFactory.getLogger(MinioStorageAdapter.class);

    private static final Set<String> NOT_FOUND_ERROR_CODES = Set.of("NoSuchKey", "ResourceNotFound");

    // Erros S3 de credencial/configuração: repetir não resolve. Qualquer outro erro é tratado como transitório.
    private static final Set<String> PERMANENT_ERROR_CODES = Set.of(
            "AccessDenied", "InvalidAccessKeyId", "SignatureDoesNotMatch", "NoSuchBucket");

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
            } catch (MinioException | IOException | GeneralSecurityException | RuntimeException e) {
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
        } catch (MinioException | IOException | GeneralSecurityException | RuntimeException e) {
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
        } catch (MinioException | IOException | GeneralSecurityException | RuntimeException e) {
            log.error("Erro inesperado ao obter objeto '{}' do storage: {}", key, e.getMessage());
            throw new BusinessException("Falha ao ler arquivo do armazenamento",
                    HttpStatus.INTERNAL_SERVER_ERROR, "STORAGE_READ_FAILED");
        }
    }

    @Override
    public ObjectDeletionResult delete(String key) {
        if (key == null || key.isBlank()) {
            return ObjectDeletionResult.NOT_FOUND;
        }
        try {
            // DeleteObject do S3 responde sucesso também para chave ausente: o stat é o que distingue NOT_FOUND
            minioClient.statObject(StatObjectArgs.builder().bucket(bucketName).object(key).build());
            minioClient.removeObject(RemoveObjectArgs.builder().bucket(bucketName).object(key).build());
            return ObjectDeletionResult.DELETED;
        } catch (ErrorResponseException e) {
            String code = e.errorResponse() != null ? e.errorResponse().code() : null;
            if (code != null && NOT_FOUND_ERROR_CODES.contains(code)) {
                return ObjectDeletionResult.NOT_FOUND;
            }
            boolean permanent = code != null && PERMANENT_ERROR_CODES.contains(code);
            return deletionFailure(permanent ? ObjectDeletionResult.PERMANENT_FAILURE : ObjectDeletionResult.TRANSIENT_FAILURE,
                    code != null ? code : "ErrorResponseException");
        } catch (ServerException | InsufficientDataException | InternalException | InvalidResponseException
                 | XmlParserException | IOException e) {
            return deletionFailure(ObjectDeletionResult.TRANSIENT_FAILURE, e.getClass().getSimpleName());
        } catch (InvalidKeyException | NoSuchAlgorithmException | IllegalArgumentException e) {
            return deletionFailure(ObjectDeletionResult.PERMANENT_FAILURE, e.getClass().getSimpleName());
        }
    }

    // Sem chave, endpoint ou mensagem do SDK no log: a mensagem pode conter o caminho do recurso
    private static ObjectDeletionResult deletionFailure(ObjectDeletionResult result, String reason) {
        log.warn("Falha ao remover objeto do storage: resultado={}, motivo={}", result, reason);
        return result;
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
        } catch (MinioException | IOException | GeneralSecurityException | RuntimeException e) {
            return false;
        }
    }

    @Override
    public StoredObjectPage listObjects(String prefix, String startAfter, int maxKeys) {
        Objects.requireNonNull(prefix, "Storage prefix cannot be null");
        if (maxKeys <= 0) {
            throw new IllegalArgumentException("maxKeys must be positive");
        }
        // Somente leitura: não chama ensureBucketExists(), que criaria o bucket como efeito colateral
        ListObjectsArgs.Builder args = ListObjectsArgs.builder()
                .bucket(bucketName)
                .prefix(prefix)
                .recursive(true)
                .maxKeys(maxKeys);
        if (startAfter != null) {
            args.startAfter(startAfter);
        }

        List<StoredObject> objects = new ArrayList<>(maxKeys);
        try {
            // O Iterable do SDK busca páginas sob demanda: parar em maxKeys evita ler o restante do bucket
            Iterator<Result<Item>> results = minioClient.listObjects(args.build()).iterator();
            while (objects.size() < maxKeys && results.hasNext()) {
                Item item = results.next().get();
                if (item.isDir()) {
                    continue;
                }
                objects.add(new StoredObject(
                        item.objectName(),
                        item.size(),
                        item.lastModified() != null ? item.lastModified().toInstant() : null
                ));
            }
        } catch (MinioException | IOException | GeneralSecurityException | RuntimeException e) {
            log.error("Erro ao listar objetos do bucket '{}' sob o prefixo '{}': {}", bucketName, prefix, e.getMessage());
            throw new BusinessException("Falha ao listar objetos do armazenamento",
                    HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_LIST_FAILED");
        }

        // Página cheia: pode haver mais objetos; a última chave lida é o marcador da próxima página
        String nextStartAfter = objects.size() == maxKeys ? objects.get(objects.size() - 1).key() : null;
        return new StoredObjectPage(objects, nextStartAfter);
    }
}
