package com.rewit.application.usecase;

import com.rewit.application.port.AccountPurgeRepository.PurgeCounts;
import com.rewit.application.port.EmailReservation;
import com.rewit.application.port.UserRepository;
import com.rewit.application.usecase.PurgeDeletedAccountUseCase.Outcome;
import com.rewit.application.usecase.PurgeDeletedAccountUseCase.PurgeResult;
import com.rewit.application.usecase.PurgeEligibleAccountsUseCase.AccountPurgeRunResult;
import com.rewit.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Testes Unitários: passada do purge de contas (PurgeEligibleAccountsUseCase)")
class PurgeEligibleAccountsUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-06-01T00:00:00Z");
    private static final PurgeResult PURGED = new PurgeResult(Outcome.PURGED, true, PurgeCounts.none());
    private static final PurgeResult UNCHANGED = new PurgeResult(Outcome.PURGED, false, PurgeCounts.none());

    private final UserRepository userRepository = mock(UserRepository.class);
    private final PurgeDeletedAccountUseCase purge = mock(PurgeDeletedAccountUseCase.class);
    private final EmailReservation reservation = mock(EmailReservation.class);

    @BeforeEach
    void setUp() {
        when(reservation.isConfigured()).thenReturn(true);
    }

    @Test
    @DisplayName("Busca candidatas pelo corte de 30 dias e repassa o mesmo instante ao caso de uso")
    void usesGracePeriodCutoffAndDelegates() {
        UUID a = UUID.randomUUID();
        when(userRepository.findDeletedUserIdsPendingPurge(any(), anyInt())).thenReturn(List.of(a)).thenReturn(List.of());
        when(purge.execute(a, NOW)).thenReturn(PURGED);

        AccountPurgeRunResult result = useCase(100, 10).run(NOW);

        verify(userRepository).findDeletedUserIdsPendingPurge(eq(NOW.minus(Duration.ofDays(30))), eq(100));
        assertEquals(1, result.purged());
        assertEquals(1, result.candidates());
    }

    @Test
    @DisplayName("Falha recuperável (negócio ou acesso a dados) é contada pela classe e a passada segue")
    void recoverableFailuresContinue() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        UUID d = UUID.randomUUID();
        when(userRepository.findDeletedUserIdsPendingPurge(any(), anyInt())).thenReturn(List.of(a, b, c, d)).thenReturn(List.of());
        when(purge.execute(a, NOW)).thenReturn(PURGED);
        when(purge.execute(b, NOW)).thenThrow(new CannotAcquireLockException("lock"));
        when(purge.execute(c, NOW)).thenThrow(new BusinessException("x", HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));
        when(purge.execute(d, NOW)).thenReturn(UNCHANGED);

        AccountPurgeRunResult result = useCase(100, 10).run(NOW);

        assertEquals(1, result.purged());
        assertEquals(1, result.unchanged());
        assertEquals(2, result.failed());
        assertEquals(1, result.failuresByType().get("CannotAcquireLockException"));
        assertEquals(1, result.failuresByType().get("BusinessException"));
    }

    @Test
    @DisplayName("Erro de programação interrompe a passada e é propagado; as contas anteriores já foram purgadas")
    void programmingErrorStopsRun() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        when(userRepository.findDeletedUserIdsPendingPurge(any(), anyInt())).thenReturn(List.of(a, b, c));
        when(purge.execute(a, NOW)).thenReturn(PURGED);
        when(purge.execute(b, NOW)).thenThrow(new NullPointerException("bug"));

        assertThrows(NullPointerException.class, () -> useCase(100, 10).run(NOW));
        verify(purge).execute(a, NOW);
        verify(purge, never()).execute(c, NOW);
    }

    @Test
    @DisplayName("Conta que falhou não volta no lote seguinte da mesma passada; limite de lotes respeitado")
    void failedAccountsAreNotRetriedWithinRunAndBatchesAreBounded() {
        UUID failing = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        // A conta que falhou continua pendente e volta no topo da consulta
        when(userRepository.findDeletedUserIdsPendingPurge(any(), anyInt()))
                .thenReturn(List.of(failing)).thenReturn(List.of(failing, second)).thenReturn(List.of(failing, third));
        when(purge.execute(failing, NOW)).thenThrow(new CannotAcquireLockException("lock"));
        when(purge.execute(second, NOW)).thenReturn(PURGED);
        when(purge.execute(third, NOW)).thenReturn(PURGED);

        AccountPurgeRunResult result = useCase(1, 2).run(NOW);

        verify(purge, times(1)).execute(failing, NOW);
        verify(purge, never()).execute(third, NOW);
        assertEquals(1, result.failed());
        assertEquals(1, result.purged());
        assertTrue(result.limitReached());
    }

    @Test
    @DisplayName("Sem segredo: com candidatas nada é tentado; sem candidatas não há falha")
    void missingSecret() {
        when(reservation.isConfigured()).thenReturn(false);
        when(userRepository.findDeletedUserIdsPendingPurge(any(), anyInt())).thenReturn(List.of(UUID.randomUUID()));

        AccountPurgeRunResult blocked = useCase(100, 10).run(NOW);
        assertTrue(blocked.secretMissing());
        verify(purge, never()).execute(any(), any());

        when(userRepository.findDeletedUserIdsPendingPurge(any(), anyInt())).thenReturn(List.of());
        AccountPurgeRunResult empty = useCase(100, 10).run(NOW);
        assertFalse(empty.secretMissing());
        assertEquals(0, empty.failed());
    }

    private PurgeEligibleAccountsUseCase useCase(int batchSize, int maxBatches) {
        return new PurgeEligibleAccountsUseCase(userRepository, purge, reservation, batchSize, maxBatches);
    }
}
