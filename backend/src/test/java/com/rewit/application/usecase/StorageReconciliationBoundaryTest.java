package com.rewit.application.usecase;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
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
    @DisplayName("Os fluxos de reconciliação e rechecagem não contêm nenhuma chamada de remoção")
    void reconciliationSourcesNeverDelete() {
        for (Path file : List.of(
                MAIN_SOURCES.resolve("application/usecase/ReconcileReviewMediaStorageUseCase.java"),
                MAIN_SOURCES.resolve("application/port/ObjectStorageListingPort.java"),
                MAIN_SOURCES.resolve("application/usecase/RecheckQuarantinedStorageObjectUseCase.java"),
                MAIN_SOURCES.resolve("application/port/StorageQuarantineRepository.java"),
                MAIN_SOURCES.resolve("application/storage/StorageQuarantineGracePolicy.java"),
                MAIN_SOURCES.resolve("application/storage/FixedStorageQuarantineGracePolicy.java"))) {
            assertFalse(read(file).contains(".delete("), file.toString());
        }
    }

    @Test
    @DisplayName("Exclusão no storage só ocorre nos fluxos autorizados (Step 28.4)")
    void storageDeletionOnlyInAuthorizedFlows() throws IOException {
        Pattern storageDelete = Pattern.compile("\\b(objectStoragePort|deletionPort)\\.delete\\(");
        Set<String> deletingFiles = javaFilesUnder(MAIN_SOURCES)
                .filter(file -> storageDelete.matcher(read(file)).find())
                .map(file -> file.getFileName().toString())
                .collect(Collectors.toSet());
        assertEquals(Set.of("ReviewMediaService.java", "PurgeConfirmedOrphanStorageObjectUseCase.java"), deletingFiles);

        Set<String> sdkRemovals = javaFilesUnder(MAIN_SOURCES)
                .filter(file -> read(file).contains("removeObject("))
                .map(file -> file.getFileName().toString())
                .collect(Collectors.toSet());
        assertEquals(Set.of("MinioStorageAdapter.java"), sdkRemovals);
    }

    @Test
    @DisplayName("O adapter da quarentena não depende do storage")
    void quarantineAdapterDoesNotTouchStorage() {
        String adapter = read(MAIN_SOURCES.resolve(
                "infrastructure/persistence/adapter/StorageQuarantineRepositoryAdapter.java"));
        assertFalse(adapter.contains("ObjectStoragePort"));
        assertFalse(adapter.contains("io.minio"));
        assertFalse(adapter.contains("MinioClient"));
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
