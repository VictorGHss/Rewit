package com.rewit.infrastructure.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Benchmark de Desempenho e Consumo: Argon2id no Ambiente Local Real (Step 4.1)")
class Argon2BenchmarkTest {

    @Test
    @DisplayName("Executar benchmark do Argon2id (m=19456, t=2, p=1) e verificar adequação para autenticação interativa")
    void executeArgon2Benchmark() {
        int memoryKiB = 19456;
        int iterations = 2;
        int parallelism = 1;
        int saltLength = 16;
        int hashLength = 32;

        Argon2PasswordEncoder encoder = new Argon2PasswordEncoder(saltLength, hashLength, parallelism, memoryKiB, iterations);
        String testPassword = "BenchmarkPassword@2026";

        // Aquecimento JVM (Warm-up)
        for (int i = 0; i < 2; i++) {
            String warmHash = encoder.encode(testPassword);
            encoder.matches(testPassword, warmHash);
        }

        int runs = 5;
        long[] hashTimes = new long[runs];
        long[] matchTimes = new long[runs];

        System.gc();
        Runtime runtime = Runtime.getRuntime();
        long memBefore = runtime.totalMemory() - runtime.freeMemory();

        for (int i = 0; i < runs; i++) {
            long startHash = System.nanoTime();
            String hash = encoder.encode(testPassword);
            long endHash = System.nanoTime();
            hashTimes[i] = (endHash - startHash) / 1_000_000;

            long startMatch = System.nanoTime();
            boolean matches = encoder.matches(testPassword, hash);
            long endMatch = System.nanoTime();
            matchTimes[i] = (endMatch - startMatch) / 1_000_000;

            assertTrue(matches, "A senha gerada deve ser verificada com sucesso");
            assertNotNull(hash);
        }

        long memAfter = runtime.totalMemory() - runtime.freeMemory();
        long approxMemDeltaBytes = Math.max(0, memAfter - memBefore);

        long totalHashTime = 0L;
        for (long t : hashTimes) {
            totalHashTime += t;
        }
        double avgHashTimeMs = runs > 0 ? (double) totalHashTime / runs : 0.0;

        long totalMatchTime = 0L;
        for (long t : matchTimes) {
            totalMatchTime += t;
        }
        double avgMatchTimeMs = runs > 0 ? (double) totalMatchTime / runs : 0.0;

        System.out.printf("=== ARGON2ID BENCHMARK REPORT ===%n");
        System.out.printf("Parameters: memory=%d KiB, iterations=%d, parallelism=%d, salt=%d, hash=%d%n",
                memoryKiB, iterations, parallelism, saltLength, hashLength);
        System.out.printf("Avg Hashing Time: %.2f ms%n", avgHashTimeMs);
        System.out.printf("Avg Verification (Match) Time: %.2f ms%n", avgMatchTimeMs);
        System.out.printf("Sample Hashing Times (ms): %s%n", Arrays.toString(hashTimes));
        System.out.printf("Sample Match Times (ms): %s%n", Arrays.toString(matchTimes));
        System.out.printf("Approx Memory Delta: %.2f MB%n", approxMemDeltaBytes / (1024.0 * 1024.0));
        System.out.printf("=================================%n");

        // Critérios de segurança e usabilidade interativa:
        // 1. O tempo de verificação deve ser > 10ms (garantindo que não é trivial)
        assertTrue(avgMatchTimeMs > 10.0, "O hashing do Argon2id deve ter custo suficiente (>10ms)");
        // 2. O tempo de verificação deve ser razoável para autenticação interativa (< 1500ms)
        assertTrue(avgMatchTimeMs < 1500.0, "O tempo de verificação deve ser interativo (<1500ms)");
    }
}
