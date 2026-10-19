package com.rewit.application.service;

import com.rewit.application.dto.reputation.ReputationDtos.ReputationView;
import com.rewit.application.port.ReputationRepository;
import com.rewit.application.port.UserRepository;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.AuthProvider;
import com.rewit.domain.enums.Role;
import com.rewit.domain.model.User;
import com.rewit.domain.model.UserReputation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Testes Unitários: ReputationService e Tratamento de Autores Inativos/Desativados")
class ReputationServiceUnitTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private ReputationRepository reputationRepository;

    @Mock
    private ReputationCalculator calculator;

    private ReputationService reputationService;

    private final UUID activeUserId = UUID.randomUUID();
    private final UUID inactiveUserId = UUID.randomUUID();
    private final UUID softDeletedUserId = UUID.randomUUID();
    private final UUID unknownUserId = UUID.randomUUID();

    private User activeUser;
    private User inactiveUser;
    private User softDeletedUser;

    @BeforeEach
    void setUp() {
        reputationService = new ReputationService(userRepository, reputationRepository, calculator);

        activeUser = new User(activeUserId, "active@rewit.com", "hash", AuthProvider.LOCAL, null);

        inactiveUser = User.rehydrate(
                inactiveUserId, "inactive@rewit.com", "hash", AuthProvider.LOCAL, null,
                false, false, Role.USER, null, Instant.now(), Instant.now()
        );

        softDeletedUser = User.rehydrate(
                softDeletedUserId, "deleted@rewit.com", "hash", AuthProvider.LOCAL, null,
                false, false, Role.USER, Instant.now(), Instant.now(), Instant.now()
        );
    }

    // =========================================================================
    // recalculateAndSave
    // =========================================================================

    @Test
    @DisplayName("recalculateAndSave: Usuário inexistente deve lançar 404 USER_NOT_FOUND")
    void recalculateAndSave_whenUserNotFound_throwsBusinessExceptionNotFound() {
        when(userRepository.findByIdIncludingDeleted(unknownUserId)).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class, () ->
                reputationService.recalculateAndSave(unknownUserId));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        assertEquals("USER_NOT_FOUND", ex.getErrorCode());

        verify(reputationRepository, never()).insertInitialRowIfNotExists(any());
        verify(reputationRepository, never()).findByUserIdForUpdate(any());
        verify(calculator, never()).calculate(any());
        verify(reputationRepository, never()).save(any());
    }

    @Test
    @DisplayName("recalculateAndSave: Usuário soft-deleted deve retornar null sem lançar erro nem adquirir lock")
    void recalculateAndSave_whenUserIsSoftDeleted_returnsNullAndDoesNotLockOrSave() {
        when(userRepository.findByIdIncludingDeleted(softDeletedUserId)).thenReturn(Optional.of(softDeletedUser));

        UserReputation result = reputationService.recalculateAndSave(softDeletedUserId);

        assertNull(result, "Deve retornar null para autor excluído sem bloquear a operação chamadora");
        verify(reputationRepository, never()).insertInitialRowIfNotExists(any());
        verify(reputationRepository, never()).findByUserIdForUpdate(any());
        verify(calculator, never()).calculate(any());
        verify(reputationRepository, never()).save(any());
    }

    @Test
    @DisplayName("recalculateAndSave: Usuário inativo (isActive=false) deve retornar null sem lançar erro nem adquirir lock")
    void recalculateAndSave_whenUserIsInactive_returnsNullAndDoesNotLockOrSave() {
        when(userRepository.findByIdIncludingDeleted(inactiveUserId)).thenReturn(Optional.of(inactiveUser));

        UserReputation result = reputationService.recalculateAndSave(inactiveUserId);

        assertNull(result, "Deve retornar null para autor inativo sem bloquear a operação chamadora");
        verify(reputationRepository, never()).insertInitialRowIfNotExists(any());
        verify(reputationRepository, never()).findByUserIdForUpdate(any());
        verify(calculator, never()).calculate(any());
        verify(reputationRepository, never()).save(any());
    }

    @Test
    @DisplayName("recalculateAndSave: Usuário ativo com mudança nos sinais deve calcular, adquirir lock e persistir o snapshot")
    void recalculateAndSave_whenUserIsActive_locksCalculatesAndSaves() {
        UserReputation previousSnapshot = new UserReputation(activeUserId, 1, 2, 1, 4, 2, Instant.parse("2026-10-10T10:00:00Z"));
        UserReputation snapshot = new UserReputation(activeUserId, 1, 3, 2, 5, 2, Instant.parse("2026-10-10T11:00:00Z"));
        when(userRepository.findByIdIncludingDeleted(activeUserId)).thenReturn(Optional.of(activeUser));
        when(reputationRepository.findByUserIdForUpdate(activeUserId)).thenReturn(Optional.of(previousSnapshot));
        when(calculator.calculate(activeUserId)).thenReturn(snapshot);
        when(reputationRepository.save(snapshot)).thenReturn(snapshot);

        UserReputation result = reputationService.recalculateAndSave(activeUserId);

        assertNotNull(result);
        assertEquals(3, result.getActiveReviews());
        assertEquals(5, result.getHelpfulVotesReceived());
        assertEquals(snapshot.getCalculatedAt(), result.getCalculatedAt());

        verify(reputationRepository).insertInitialRowIfNotExists(activeUserId);
        verify(reputationRepository).findByUserIdForUpdate(activeUserId);
        verify(calculator).calculate(activeUserId);
        verify(reputationRepository).save(snapshot);
    }

    @Test
    @DisplayName("recalculateAndSave: Quando os sinais e a versão não mudam, deve preservar calculatedAt existente sem sobrescrever")
    void recalculateAndSave_whenSignalsUnchanged_preservesExistingCalculatedAtWithoutSaving() {
        Instant initialCalculatedAt = Instant.parse("2026-10-10T10:00:00Z");
        Instant laterCalculatedAt = Instant.parse("2026-10-10T12:30:00Z");
        UserReputation existingSnapshot = new UserReputation(activeUserId, 1, 3, 2, 5, 2, initialCalculatedAt);
        UserReputation recalculatedWithSameSignals = new UserReputation(activeUserId, 1, 3, 2, 5, 2, laterCalculatedAt);

        when(userRepository.findByIdIncludingDeleted(activeUserId)).thenReturn(Optional.of(activeUser));
        when(reputationRepository.findByUserIdForUpdate(activeUserId)).thenReturn(Optional.of(existingSnapshot));
        when(calculator.calculate(activeUserId)).thenReturn(recalculatedWithSameSignals);

        UserReputation result = reputationService.recalculateAndSave(activeUserId);

        assertNotNull(result);
        assertEquals(initialCalculatedAt, result.getCalculatedAt(), "Deve preservar o calculatedAt original quando os sinais não mudam");
        assertEquals(3, result.getActiveReviews());
        assertEquals(2, result.getVerifiedReviews());
        assertEquals(5, result.getHelpfulVotesReceived());
        assertEquals(2, result.getDistinctTargetsReviewed());

        verify(reputationRepository).insertInitialRowIfNotExists(activeUserId);
        verify(reputationRepository).findByUserIdForUpdate(activeUserId);
        verify(calculator).calculate(activeUserId);
        verify(reputationRepository, never()).save(any());
    }

    // =========================================================================
    // getReputation
    // =========================================================================

    @Test
    @DisplayName("getReputation: Usuário inexistente deve lançar 404 USER_NOT_FOUND")
    void getReputation_whenUserNotFound_throwsBusinessExceptionNotFound() {
        when(userRepository.findByIdIncludingDeleted(unknownUserId)).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class, () ->
                reputationService.getReputation(unknownUserId));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        assertEquals("USER_NOT_FOUND", ex.getErrorCode());
    }

    @Test
    @DisplayName("getReputation: Usuário soft-deleted deve lançar 404 USER_NOT_FOUND")
    void getReputation_whenUserIsSoftDeleted_throwsBusinessExceptionNotFound() {
        when(userRepository.findByIdIncludingDeleted(softDeletedUserId)).thenReturn(Optional.of(softDeletedUser));

        BusinessException ex = assertThrows(BusinessException.class, () ->
                reputationService.getReputation(softDeletedUserId));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        assertEquals("USER_NOT_FOUND", ex.getErrorCode());
    }

    @Test
    @DisplayName("getReputation: Usuário inativo deve lançar 404 USER_NOT_FOUND")
    void getReputation_whenUserIsInactive_throwsBusinessExceptionNotFound() {
        when(userRepository.findByIdIncludingDeleted(inactiveUserId)).thenReturn(Optional.of(inactiveUser));

        BusinessException ex = assertThrows(BusinessException.class, () ->
                reputationService.getReputation(inactiveUserId));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        assertEquals("USER_NOT_FOUND", ex.getErrorCode());
    }

    @Test
    @DisplayName("getReputation: Usuário ativo com snapshot existente deve retornar diretamente em O(1)")
    void getReputation_whenUserIsActiveAndSnapshotExists_returnsViewDirectly() {
        UserReputation snapshot = new UserReputation(activeUserId, 1, 4, 3, 10, 3, Instant.now());
        when(userRepository.findByIdIncludingDeleted(activeUserId)).thenReturn(Optional.of(activeUser));
        when(reputationRepository.findByUserId(activeUserId)).thenReturn(Optional.of(snapshot));

        ReputationView view = reputationService.getReputation(activeUserId);

        assertNotNull(view);
        assertEquals(activeUserId, view.userId());
        assertEquals(4, view.signals().activeReviews());
        assertEquals(10, view.signals().helpfulVotesReceived());

        verify(reputationRepository, never()).insertInitialRowIfNotExists(any());
        verify(calculator, never()).calculate(any());
    }
}
