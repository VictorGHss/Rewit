package com.rewit.infrastructure.storage;

import com.rewit.config.MinioConfig;
import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.ErrorResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@DisplayName("ObjectStorageHealthIndicator: verificação estritamente somente leitura do bucket S3 (F2-DEPLOY-03.4)")
class ObjectStorageHealthIndicatorTest {

    @Test
    @DisplayName("Bucket existente e acessível retorna UP, chama somente bucketExists e não expõe detalhes")
    void returnsUpWhenBucketExistsUsingOnlyReadOnlyCheck() throws Exception {
        MinioClient client = mock(MinioClient.class);
        when(client.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);

        ObjectStorageHealthIndicator indicator = new ObjectStorageHealthIndicator(
                client, "rewit-prod-media", Duration.ZERO, Clock.systemUTC());

        Health health = indicator.health();

        assertEquals(Status.UP, health.getStatus());
        assertTrue(health.getDetails().isEmpty(), "Health público não deve conter detalhes sensíveis");
        verify(client, times(1)).bucketExists(any(BucketExistsArgs.class));
        verifyNoMoreInteractions(client);
    }

    @Test
    @DisplayName("Bucket ausente retorna DOWN e NUNCA executa makeBucket, putObject, removeObject ou qualquer escrita")
    void returnsDownWhenBucketDoesNotExistWithoutMutatingStorage() throws Exception {
        MinioClient client = mock(MinioClient.class);
        when(client.bucketExists(any(BucketExistsArgs.class))).thenReturn(false);

        ObjectStorageHealthIndicator indicator = new ObjectStorageHealthIndicator(
                client, "rewit-missing-bucket", Duration.ZERO, Clock.systemUTC());

        Health health = indicator.health();

        assertEquals(Status.DOWN, health.getStatus());
        assertTrue(health.getDetails().isEmpty());
        verify(client, times(1)).bucketExists(any(BucketExistsArgs.class));
        verifyNoMoreInteractions(client);
    }

    @Test
    @DisplayName("Acesso negado (AccessDenied) retorna DOWN sem vazar credenciais, bucket ou mensagem do SDK")
    void returnsDownOnAccessDeniedWithoutLeakingDetails() throws Exception {
        MinioClient client = mock(MinioClient.class);
        ErrorResponse errorResponse = new ErrorResponse(
                "AccessDenied",
                "Access Denied for AKIASECRETKEY on https://internal-s3.local/rewit-prod-media",
                "rewit-prod-media",
                null,
                "/rewit-prod-media",
                "req-123",
                "host-123"
        );
        ErrorResponseException accessDenied = mock(ErrorResponseException.class);
        when(accessDenied.errorResponse()).thenReturn(errorResponse);
        when(client.bucketExists(any(BucketExistsArgs.class))).thenThrow(accessDenied);

        ObjectStorageHealthIndicator indicator = new ObjectStorageHealthIndicator(
                client, "rewit-prod-media", Duration.ZERO, Clock.systemUTC());

        Health health = indicator.health();

        assertEquals(Status.DOWN, health.getStatus());
        assertTrue(health.getDetails().isEmpty());
        verify(client, times(1)).bucketExists(any(BucketExistsArgs.class));
        verifyNoMoreInteractions(client);
    }

    @Test
    @DisplayName("Endpoint indisponível ou timeout retorna DOWN sem expor detalhes")
    void returnsDownOnIoOrTimeoutFailure() throws Exception {
        MinioClient client = mock(MinioClient.class);
        when(client.bucketExists(any(BucketExistsArgs.class)))
                .thenThrow(new SocketTimeoutException("connect timed out to https://secret-s3.internal:9000"));

        ObjectStorageHealthIndicator indicator = new ObjectStorageHealthIndicator(
                client, "rewit-prod-media", Duration.ZERO, Clock.systemUTC());

        Health health = indicator.health();

        assertEquals(Status.DOWN, health.getStatus());
        assertTrue(health.getDetails().isEmpty());
        verify(client, times(1)).bucketExists(any(BucketExistsArgs.class));
        verifyNoMoreInteractions(client);
    }

    @Test
    @DisplayName("Erro inesperado (IOException ou RuntimeException) retorna DOWN sem vazar dados")
    void returnsDownOnUnexpectedException() throws Exception {
        MinioClient ioClient = mock(MinioClient.class);
        when(ioClient.bucketExists(any(BucketExistsArgs.class))).thenThrow(new IOException("connection refused"));
        ObjectStorageHealthIndicator ioIndicator = new ObjectStorageHealthIndicator(
                ioClient, "rewit-prod-media", Duration.ZERO, Clock.systemUTC());
        assertEquals(Status.DOWN, ioIndicator.health().getStatus());
        assertTrue(ioIndicator.health().getDetails().isEmpty());

        MinioClient rtClient = mock(MinioClient.class);
        when(rtClient.bucketExists(any(BucketExistsArgs.class))).thenThrow(new RuntimeException("unexpected sdk error"));
        ObjectStorageHealthIndicator rtIndicator = new ObjectStorageHealthIndicator(
                rtClient, "rewit-prod-media", Duration.ZERO, Clock.systemUTC());
        assertEquals(Status.DOWN, rtIndicator.health().getStatus());
        assertTrue(rtIndicator.health().getDetails().isEmpty());
    }

    @Test
    @DisplayName("Cache de TTL curto evita rajadas concorrentes e revalida após expiração (UP -> DOWN -> UP)")
    void shortTtlCacheIsThreadSafeAndRevalidatesAfterExpiry() throws Exception {
        MinioClient client = mock(MinioClient.class);
        when(client.bucketExists(any(BucketExistsArgs.class)))
                .thenReturn(true)
                .thenReturn(false)
                .thenReturn(true);

        MutableClock clock = new MutableClock(1_000_000L);
        ObjectStorageHealthIndicator indicator = new ObjectStorageHealthIndicator(
                client, "rewit-prod-media", Duration.ofSeconds(5), clock);

        // Rajada concorrente dentro do TTL -> exatamente 1 chamada bucketExists
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Health>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return indicator.health();
                }));
            }
            start.countDown();
            for (Future<Health> f : futures) {
                assertEquals(Status.UP, f.get().getStatus());
            }
        }
        verify(client, times(1)).bucketExists(any(BucketExistsArgs.class));

        // Ainda dentro do TTL (+4999ms) -> mantém cache
        clock.advanceMillis(4_999L);
        assertEquals(Status.UP, indicator.health().getStatus());
        verify(client, times(1)).bucketExists(any(BucketExistsArgs.class));

        // Após expiração do TTL (+1ms -> 5000ms) -> reconsulta e detecta DOWN
        clock.advanceMillis(1L);
        assertEquals(Status.DOWN, indicator.health().getStatus());
        verify(client, times(2)).bucketExists(any(BucketExistsArgs.class));

        // Após novo TTL -> recupera para UP
        clock.advanceMillis(5_000L);
        assertEquals(Status.UP, indicator.health().getStatus());
        verify(client, times(3)).bucketExists(any(BucketExistsArgs.class));
        verifyNoMoreInteractions(client);
    }

    @Test
    @DisplayName("Bucket vazio é recusado fail-closed e MinioConfig expõe cliente dedicado com timeout curto")
    void rejectsBlankBucketAndMinioConfigProvidesDedicatedHealthClient() {
        MinioClient mockClient = mock(MinioClient.class);
        assertThrows(IllegalStateException.class,
                () -> new ObjectStorageHealthIndicator(mockClient, "   ", Duration.ZERO, Clock.systemUTC()));

        MinioConfig config = new MinioConfig(
                "https://s3.us-east-1.amazonaws.com",
                "s3-prod-access-key-fixture-001",
                "super-secret-prod-storage-key",
                "us-east-1",
                Duration.ofSeconds(2)
        );
        assertEquals(Duration.ofSeconds(2), config.getHealthTimeout());
        MinioClient primary = config.minioClient();
        MinioClient health = config.objectStorageHealthMinioClient();
        assertNotNull(primary);
        assertNotNull(health);
        assertNotSame(primary, health);
    }

    private static final class MutableClock extends Clock {
        private final AtomicLong millis;

        MutableClock(long initialMillis) {
            this.millis = new AtomicLong(initialMillis);
        }

        void advanceMillis(long delta) {
            millis.addAndGet(delta);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis.get());
        }

        @Override
        public long millis() {
            return millis.get();
        }
    }
}
