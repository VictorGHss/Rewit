package com.rewit.infrastructure.storage;

import com.rewit.application.dto.storage.StorageReconciliationDtos.StoredObjectPage;
import io.minio.MinioClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Testes de Integração de Storage: SeaweedFS Real (Step 21.0 - Requisito 28)")
class SeaweedStorageIntegrationTest {

    // JUnit cria uma instância por teste, então o adaptador continua isolado por método
    private final MinioStorageAdapter storageAdapter = new MinioStorageAdapter(
            MinioClient.builder()
                    .endpoint("http://localhost:8333")
                    .credentials("change-me", "change-me")
                    .build(),
            "rewit-local");

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
