package com.rewit.infrastructure.storage;

import com.rewit.application.dto.storage.StorageReconciliationDtos.StoredObjectPage;
import io.minio.MinioClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Testes de Integração de Storage: SeaweedFS Real (Step 21.0 - Requisito 28)")
class SeaweedStorageIntegrationTest {

    private static final String STORAGE_ENDPOINT = resolveStorageEndpoint();
    private static final String STORAGE_BUCKET = System.getProperty("TEST_STORAGE_BUCKET",
            System.getProperty("rewit.minio.bucket-name",
            System.getenv().getOrDefault("TEST_STORAGE_BUCKET", "rewit-local")));
    private static final String ACCESS_KEY = System.getProperty("TEST_STORAGE_ACCESS_KEY",
            System.getProperty("rewit.minio.access-key",
            System.getenv().getOrDefault("TEST_STORAGE_ACCESS_KEY", "change-me")));
    private static final String SECRET_KEY = System.getProperty("TEST_STORAGE_SECRET_KEY",
            System.getProperty("rewit.minio.secret-key",
            System.getenv().getOrDefault("TEST_STORAGE_SECRET_KEY", "change-me")));

    private static String resolveStorageEndpoint() {
        String testEp = System.getenv("TEST_STORAGE_ENDPOINT");
        if (testEp != null && !testEp.isBlank()) return testEp.trim();
        String sysProp = System.getProperty("TEST_STORAGE_ENDPOINT");
        if (sysProp != null && !sysProp.isBlank()) return sysProp.trim();
        sysProp = System.getProperty("rewit.minio.endpoint");
        if (sysProp != null && !sysProp.isBlank()) return sysProp.trim();
        return "";
    }

    private MinioStorageAdapter storageAdapter;

    @BeforeEach
    void setUpStorage() {
        if (STORAGE_ENDPOINT.isBlank()) {
            fail("Endpoint de SeaweedFS/S3 descartável não configurado. Forneça -Drewit.storage.seaweed.endpoint ou OBJECT_STORAGE_ENDPOINT.");
        }
        if (STORAGE_ENDPOINT.contains(":8333") || STORAGE_ENDPOINT.contains("localhost:8333") || STORAGE_ENDPOINT.contains("127.0.0.1:8333")) {
            throw new IllegalStateException("Uso acidental do SeaweedFS compartilhado (porta 8333) detectado em teste de integração! " +
                    "Use uma instância descartável (ex: porta 58333).");
        }
        try {
            storageAdapter = new MinioStorageAdapter(
                    MinioClient.builder()
                            .endpoint(STORAGE_ENDPOINT)
                            .credentials(ACCESS_KEY, SECRET_KEY)
                            .build(),
                    STORAGE_BUCKET);
            storageAdapter.exists("health-check-" + UUID.randomUUID());
        } catch (Exception e) {
            fail("SeaweedFS/S3 storage descartável não acessível em " + STORAGE_ENDPOINT + ": " + e.getMessage(), e);
        }
    }

    @Test
    @DisplayName("Ciclo completo de put, get, exists e delete contra SeaweedFS local")
    void testStorageLifecycleAgainstRealSeaweedFs() {
        String testKey = "integration-tests/test-" + UUID.randomUUID() + ".txt";
        byte[] payload = "Hello SeaweedFS Object Storage!".getBytes(StandardCharsets.UTF_8);

        // 1. Confirma que não existe antes
        assertFalse(storageAdapter.exists(testKey));

        // 2. Put
        storageAdapter.put(testKey, "text/plain", payload);

        // 3. Exists
        assertTrue(storageAdapter.exists(testKey));

        // 4. Get
        byte[] retrieved = storageAdapter.get(testKey);
        assertArrayEquals(payload, retrieved);

        // 5. Delete
        storageAdapter.delete(testKey);

        // 6. Confirma remoção
        assertFalse(storageAdapter.exists(testKey));
    }

    @Test
    @DisplayName("Listagem paginada por prefixo com start-after contra SeaweedFS local (Step 28.1)")
    void testPaginatedListingAgainstRealSeaweedFs() {
        // Prefixo exclusivo fora de reviews/: nunca participa da reconciliação. Remove somente os próprios objetos.
        String prefix = "integration-tests/listing-" + UUID.randomUUID() + "/";
        List<String> keys = List.of(prefix + "a.txt", prefix + "b.txt", prefix + "c.txt");
        try {
            for (String key : keys) {
                storageAdapter.put(key, "text/plain", key.getBytes(StandardCharsets.UTF_8));
            }

            StoredObjectPage first = storageAdapter.listObjects(prefix, null, 2);
            StoredObjectPage second = storageAdapter.listObjects(prefix, first.nextStartAfter(), 2);

            assertEquals(keys.subList(0, 2), first.objects().stream().map(object -> object.key()).toList());
            assertEquals(keys.get(1), first.nextStartAfter());
            assertEquals(keys.subList(2, 3), second.objects().stream().map(object -> object.key()).toList());
            assertFalse(second.hasMore());
            assertEquals(keys.get(2).length(), second.objects().get(0).sizeBytes());
            assertNotNull(second.objects().get(0).lastModified());
        } finally {
            keys.forEach(key -> storageAdapter.delete(key));
        }
        assertTrue(storageAdapter.listObjects(prefix, null, 10).objects().isEmpty());
    }
}
