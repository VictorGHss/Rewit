package com.rewit.application.usecase;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fronteiras do contrato de reconciliação verificadas sobre o código-fonte (Step 28.1).
 */
@DisplayName("Testes de Fronteira: reconciliação de storage (Step 28.1)")
class StorageReconciliationBoundaryTest {

    private static final Path MAIN_SOURCES = Path.of("src/main/java/com/rewit");

    @Test
    @DisplayName("Domínio e aplicação não dependem do SDK do MinIO")
    void domainAndApplicationDoNotImportMinioSdk() throws IOException {
        List<Path> offenders = javaFilesUnder(MAIN_SOURCES.resolve("domain"), MAIN_SOURCES.resolve("application"))
                .filter(file -> read(file).contains("import io.minio."))
                .toList();

        assertTrue(offenders.isEmpty(), "imports do SDK do MinIO fora da infraestrutura: " + offenders);
    }

    @Test
    @DisplayName("O fluxo de reconciliação não contém nenhuma chamada de remoção")
    void reconciliationSourcesNeverDelete() {
        for (Path file : List.of(
                MAIN_SOURCES.resolve("application/usecase/ReconcileReviewMediaStorageUseCase.java"),
                MAIN_SOURCES.resolve("application/port/ObjectStorageListingPort.java"))) {
            assertFalse(read(file).contains(".delete("), file.toString());
        }
    }

    private static Stream<Path> javaFilesUnder(Path... roots) throws IOException {
        Stream<Path> all = Stream.empty();
        for (Path root : roots) {
            assertTrue(Files.isDirectory(root), "diretório de fontes não encontrado: " + root.toAbsolutePath());
            all = Stream.concat(all, Files.walk(root).filter(file -> file.toString().endsWith(".java")).toList().stream());
        }
        return all;
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new IllegalStateException("não foi possível ler " + file, e);
        }
    }
}
