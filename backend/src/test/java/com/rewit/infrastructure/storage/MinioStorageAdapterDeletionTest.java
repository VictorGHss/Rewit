package com.rewit.infrastructure.storage;

import com.rewit.application.dto.storage.ObjectDeletionResult;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.errors.ServerException;
import io.minio.messages.ErrorResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.net.ConnectException;
import java.security.InvalidKeyException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("Testes Unitários: exclusão classificada do MinioStorageAdapter (Step 28.4)")
class MinioStorageAdapterDeletionTest {

    private static final String KEY = "reviews/k/image.jpg";

    private final MinioClient minioClient = mock(MinioClient.class);
    private final MinioStorageAdapter adapter = new MinioStorageAdapter(minioClient, "rewit-local");

    @Test
    @DisplayName("Objeto existente: stat e remoção no bucket configurado resultam em DELETED")
    void existingObjectIsDeleted() throws Exception {
        assertEquals(ObjectDeletionResult.DELETED, adapter.delete(KEY));

        ArgumentCaptor<RemoveObjectArgs> args = ArgumentCaptor.forClass(RemoveObjectArgs.class);
        verify(minioClient).removeObject(args.capture());
        assertEquals("rewit-local", args.getValue().bucket());
        assertEquals(KEY, args.getValue().object());
    }

    @Test
    @DisplayName("NoSuchKey no stat: NOT_FOUND sem chamar a remoção (o DeleteObject do S3 não distingue ausência)")
    void missingObjectIsNotFound() throws Exception {
        when(minioClient.statObject(any(StatObjectArgs.class))).thenThrow(errorResponse("NoSuchKey"));

        assertEquals(ObjectDeletionResult.NOT_FOUND, adapter.delete(KEY));
        verify(minioClient, never()).removeObject(any(RemoveObjectArgs.class));
    }

    @Test
    @DisplayName("Objeto removido entre o stat e a remoção: NoSuchKey na remoção também é NOT_FOUND")
    void missingOnRemovalIsNotFound() throws Exception {
        doThrow(errorResponse("NoSuchKey")).when(minioClient).removeObject(any(RemoveObjectArgs.class));

        assertEquals(ObjectDeletionResult.NOT_FOUND, adapter.delete(KEY));
    }

    @ParameterizedTest
    @ValueSource(strings = {"AccessDenied", "InvalidAccessKeyId", "SignatureDoesNotMatch", "NoSuchBucket"})
    @DisplayName("Erros S3 de credencial/configuração são PERMANENT_FAILURE")
    void credentialAndConfigurationErrorsArePermanent(String code) throws Exception {
        when(minioClient.statObject(any(StatObjectArgs.class))).thenThrow(errorResponse(code));

        assertEquals(ObjectDeletionResult.PERMANENT_FAILURE, adapter.delete(KEY));
        verify(minioClient, never()).removeObject(any(RemoveObjectArgs.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"InternalError", "SlowDown", "ServiceUnavailable", "CodigoDesconhecido"})
    @DisplayName("Demais erros S3 são TRANSIENT_FAILURE")
    void otherErrorResponsesAreTransient(String code) throws Exception {
        doThrow(errorResponse(code)).when(minioClient).removeObject(any(RemoveObjectArgs.class));

        assertEquals(ObjectDeletionResult.TRANSIENT_FAILURE, adapter.delete(KEY));
    }

    @Test
    @DisplayName("Falha de conexão e erro HTTP do servidor são TRANSIENT_FAILURE")
    void networkAndServerErrorsAreTransient() throws Exception {
        when(minioClient.statObject(any(StatObjectArgs.class))).thenThrow(new ConnectException("Connection refused"));
        assertEquals(ObjectDeletionResult.TRANSIENT_FAILURE, adapter.delete(KEY));

        reset(minioClient);
        doThrow(new ServerException("bad gateway", 502, null)).when(minioClient).removeObject(any(RemoveObjectArgs.class));
        assertEquals(ObjectDeletionResult.TRANSIENT_FAILURE, adapter.delete(KEY));
    }

    @Test
    @DisplayName("Falha de assinatura/criptografia local é PERMANENT_FAILURE")
    void signingFailureIsPermanent() throws Exception {
        when(minioClient.statObject(any(StatObjectArgs.class))).thenThrow(new InvalidKeyException("invalid secret"));

        assertEquals(ObjectDeletionResult.PERMANENT_FAILURE, adapter.delete(KEY));
    }

    @Test
    @DisplayName("Erro inesperado não é engolido nem convertido numa categoria conveniente")
    void unexpectedRuntimeErrorPropagates() throws Exception {
        when(minioClient.statObject(any(StatObjectArgs.class))).thenThrow(new IllegalStateException("bug"));

        assertThrows(IllegalStateException.class, () -> adapter.delete(KEY));
    }

    @Test
    @DisplayName("Chave nula ou vazia não toca o storage")
    void blankKeyDoesNotTouchStorage() {
        assertEquals(ObjectDeletionResult.NOT_FOUND, adapter.delete(null));
        assertEquals(ObjectDeletionResult.NOT_FOUND, adapter.delete("  "));
        verifyNoInteractions(minioClient);
    }

    private static ErrorResponseException errorResponse(String code) {
        return new ErrorResponseException(
                new ErrorResponse(code, "message", "rewit-local", KEY, "/rewit-local/" + KEY, "req", "host"), null, null);
    }
}
