package com.rewit.application.usecase;

import com.rewit.application.port.AuthSessionRepository;
import com.rewit.application.usecase.CleanupAuthSessionsUseCase.AuthSessionCleanupResult;
import com.rewit.domain.model.AuthSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Testes Unitários: CleanupAuthSessionsUseCase (Step 29.3)")
class CleanupAuthSessionsUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-10-03T03:00:00Z");

    private final RecordingRepository repository = new RecordingRepository();

    @Test
    @DisplayName("Limpa metadados antes do purge, repassando o instante e o tamanho do lote")
    void metadataPhaseRunsBeforePurge() {
        repository.metadataResults.add(3);
        repository.purgeResults.add(2);

        AuthSessionCleanupResult result = new CleanupAuthSessionsUseCase(repository, 10, 5).cleanup(NOW);

        assertEquals(List.of("metadata", "purge"), repository.calls);
        assertEquals(List.of(NOW, NOW), repository.nows);
        assertEquals(List.of(10, 10), repository.limits);
        assertEquals(3, result.metadataCleared());
        assertEquals(2, result.sessionsPurged());
        assertFalse(result.metadataLimitReached());
        assertFalse(result.purgeLimitReached());
    }

    @Test
    @DisplayName("Cada fase continua enquanto os lotes vêm cheios e para no primeiro lote incompleto")
    void phaseStopsOnPartialBatch() {
        repository.metadataResults.addAll(List.of(10, 10, 4));
        repository.purgeResults.addAll(List.of(10, 0));

        AuthSessionCleanupResult result = new CleanupAuthSessionsUseCase(repository, 10, 5).cleanup(NOW);

        assertEquals(24, result.metadataCleared());
        assertEquals(3, result.metadataBatches());
        assertEquals(10, result.sessionsPurged());
        assertEquals(2, result.purgeBatches());
    }

    @Test
    @DisplayName("max-batches-per-run limita cada fase e sinaliza que pode haver trabalho restante")
    void maxBatchesPerRunIsRespected() {
        repository.alwaysFull = true;

        AuthSessionCleanupResult result = new CleanupAuthSessionsUseCase(repository, 10, 3).cleanup(NOW);

        assertEquals(3, result.metadataBatches());
        assertEquals(3, result.purgeBatches());
        assertEquals(30, result.sessionsPurged());
        assertTrue(result.metadataLimitReached());
        assertTrue(result.purgeLimitReached());
        assertEquals(6, repository.calls.size());
    }

    @Test
    @DisplayName("Falha em um lote é propagada e interrompe a passada; os lotes anteriores já foram feitos")
    void batchFailurePropagates() {
        repository.metadataResults.add(1);
        repository.purgeFailure = new IllegalStateException("deadlock detectado");

        CleanupAuthSessionsUseCase useCase = new CleanupAuthSessionsUseCase(repository, 10, 5);

        assertThrows(IllegalStateException.class, () -> useCase.cleanup(NOW));
        assertEquals(List.of("metadata", "purge"), repository.calls);
    }

    @Test
    @DisplayName("Configuração inválida é rejeitada")
    void invalidConfigurationIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new CleanupAuthSessionsUseCase(repository, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new CleanupAuthSessionsUseCase(repository, 1, 0));
        assertThrows(NullPointerException.class, () -> new CleanupAuthSessionsUseCase(null, 1, 1));
        assertThrows(NullPointerException.class, () -> new CleanupAuthSessionsUseCase(repository, 1, 1).cleanup(null));
    }

    /** Registra as chamadas de cleanup; os demais métodos da porta não são usados pelo use case. */
    private static final class RecordingRepository implements AuthSessionRepository {

        private final List<String> calls = new ArrayList<>();
        private final List<Instant> nows = new ArrayList<>();
        private final List<Integer> limits = new ArrayList<>();
        private final Deque<Integer> metadataResults = new ArrayDeque<>();
        private final Deque<Integer> purgeResults = new ArrayDeque<>();
        private boolean alwaysFull;
        private RuntimeException purgeFailure;

        @Override
        public int clearInactiveMetadata(Instant now, int limit) {
            record("metadata", now, limit);
            return alwaysFull ? limit : metadataResults.isEmpty() ? 0 : metadataResults.removeFirst();
        }

        @Override
        public int purgeExpiredWithoutSuccessor(Instant now, int limit) {
            record("purge", now, limit);
            if (purgeFailure != null) {
                throw purgeFailure;
            }
            return alwaysFull ? limit : purgeResults.isEmpty() ? 0 : purgeResults.removeFirst();
        }

        private void record(String call, Instant now, int limit) {
            calls.add(call);
            nows.add(now);
            limits.add(limit);
        }

        @Override
        public AuthSession save(AuthSession session) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<AuthSession> findById(UUID id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<AuthSession> findByTokenHash(String tokenHash) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<AuthSession> findByTokenHashForUpdate(String tokenHash) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void revokeAllByUserId(UUID userId) {
            throw new UnsupportedOperationException();
        }
    }
}
