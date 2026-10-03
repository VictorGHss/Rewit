package com.rewit.infrastructure.storage;

import io.minio.MinioClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
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
}
