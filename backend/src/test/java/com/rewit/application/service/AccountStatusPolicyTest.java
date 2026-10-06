package com.rewit.application.service;

import com.rewit.application.port.UserRepository;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.AuthProvider;
import com.rewit.domain.enums.Role;
import com.rewit.domain.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("AccountStatusPolicy: estado atual da conta nas mutações")
class AccountStatusPolicyTest {

    private UserRepository userRepository;
    private AccountStatusPolicy policy;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        policy = new AccountStatusPolicy(userRepository);
    }

    @Test
    @DisplayName("Conta ativa e não excluída pode operar")
    void activeAccountIsAllowed() {
        UUID id = UUID.randomUUID();
        when(userRepository.findById(id)).thenReturn(Optional.of(user(id, true, null)));

        assertDoesNotThrow(() -> policy.requireOperational(id));
        verify(userRepository).findById(id);
    }

    @Test
    @DisplayName("Conta inativa, excluída ou inexistente recebe o mesmo 401 ACCOUNT_DISABLED")
    void nonOperationalAccountsAreRejectedIndistinguishably() {
        UUID inactive = UUID.randomUUID();
        UUID deleted = UUID.randomUUID();
        UUID missing = UUID.randomUUID();
        when(userRepository.findById(inactive)).thenReturn(Optional.of(user(inactive, false, null)));
        when(userRepository.findById(deleted)).thenReturn(Optional.of(user(deleted, false, Instant.now())));
        when(userRepository.findById(missing)).thenReturn(Optional.empty());

        for (UUID id : new UUID[]{inactive, deleted, missing}) {
            BusinessException ex = assertThrows(BusinessException.class, () -> policy.requireOperational(id));
            assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus());
            assertEquals("ACCOUNT_DISABLED", ex.getErrorCode());
            assertEquals("Conta de usuário inativa ou inexistente", ex.getMessage());
        }
    }

    @Test
    @DisplayName("Estado inconsistente (ativa e excluída) também é rejeitado, independentemente da constraint")
    void inconsistentStateIsRejected() {
        UUID id = UUID.randomUUID();
        when(userRepository.findById(id)).thenReturn(Optional.of(user(id, true, Instant.now())));

        BusinessException ex = assertThrows(BusinessException.class, () -> policy.requireOperational(id));
        assertEquals("ACCOUNT_DISABLED", ex.getErrorCode());
    }

    @Test
    @DisplayName("User.isOperational exige ativa e sem deleted_at; softDelete torna a conta não operacional")
    void userOperationalPredicate() {
        assertTrue(user(UUID.randomUUID(), true, null).isOperational());
        assertFalse(user(UUID.randomUUID(), false, null).isOperational());
        assertFalse(user(UUID.randomUUID(), false, Instant.now()).isOperational());
        assertFalse(user(UUID.randomUUID(), true, Instant.now()).isOperational());

        User softDeleted = new User(null, "conta@rewit.com", "hash", AuthProvider.LOCAL, null);
        assertTrue(softDeleted.isOperational());
        softDeleted.softDelete();
        assertFalse(softDeleted.isOperational());
    }

    @Test
    @DisplayName("Identificador ausente é erro de programação, não uma conta rejeitada")
    void nullActorIsRejectedAsProgrammingError() {
        assertThrows(NullPointerException.class, () -> policy.requireOperational(null));
    }

    private static User user(UUID id, boolean active, Instant deletedAt) {
        return User.rehydrate(id, "u" + id.toString().substring(0, 8) + "@rewit.com", "hash", AuthProvider.LOCAL,
                null, active, false, Role.USER, deletedAt, Instant.now(), Instant.now());
    }
}
