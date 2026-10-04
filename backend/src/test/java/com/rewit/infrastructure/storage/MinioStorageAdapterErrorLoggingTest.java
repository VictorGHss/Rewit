package com.rewit.infrastructure.storage;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.rewit.common.exception.BusinessException;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("Testes Unitários: falhas do MinIO logadas sem a mensagem do SDK (Observabilidade V1)")
class MinioStorageAdapterErrorLoggingTest {

    private static final String SDK_SECRET = "X-Amz-Credential=segredo-do-sdk";

    private final MinioClient minioClient = mock(MinioClient.class);
    private final MinioStorageAdapter adapter = new MinioStorageAdapter(minioClient, "rewit-local");

    @Test
    @DisplayName("Falha ao gravar: log com a classe do erro, sem a mensagem do SDK; resposta inalterada")
    void putFailureLogsOnlyErrorClass() throws Exception {
        when(minioClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);
        when(minioClient.putObject(any(PutObjectArgs.class))).thenThrow(new IllegalStateException(SDK_SECRET));

        String message = capture(adapter -> assertEquals("STORAGE_WRITE_FAILED",
                assertThrows(BusinessException.class, () -> adapter.put("reviews/a/b/image.jpg", "image/jpeg", new byte[]{1}))
                        .getErrorCode()));

        assertTrue(message.contains("erro=IllegalStateException"));
        assertFalse(message.contains(SDK_SECRET));
    }

    @Test
    @DisplayName("Falha ao ler: log com a classe do erro, sem a mensagem do SDK; resposta inalterada")
    void getFailureLogsOnlyErrorClass() throws Exception {
        when(minioClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);
        when(minioClient.getObject(any(GetObjectArgs.class))).thenThrow(new IllegalStateException(SDK_SECRET));

        String message = capture(adapter -> assertEquals("STORAGE_READ_FAILED",
                assertThrows(BusinessException.class, () -> adapter.get("reviews/a/b/image.jpg")).getErrorCode()));

        assertTrue(message.contains("erro=IllegalStateException"));
        assertFalse(message.contains(SDK_SECRET));
    }

    private String capture(Consumer<MinioStorageAdapter> action) {
        Logger logger = (Logger) LoggerFactory.getLogger(MinioStorageAdapter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            action.accept(adapter);
        } finally {
            logger.detachAppender(appender);
        }
        assertEquals(1, appender.list.size());
        assertNull(appender.list.getFirst().getThrowableProxy());
        return appender.list.getFirst().getFormattedMessage();
    }
}
