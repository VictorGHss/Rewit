package com.rewit.application.service;

import com.rewit.application.port.UserRepository;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.AccountStatus;
import com.rewit.domain.enums.AuthProvider;
import com.rewit.domain.enums.Role;
import com.rewit.domain.model.User;
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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ModeratorRolePolicy: conta operacional e cargo atual MODERATOR/ADMIN")
class ModeratorRolePolicyTest {

    @Mock private AccountStatusPolicy accountStatusPolicy;
    @Mock private UserRepository userRepository;

    private ModeratorRolePolicy policy;
    private final UUID actor = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        policy = new ModeratorRolePolicy(accountStatusPolicy, userRepository);
    }

    private void currentRole(Role role) {
        Instant now = Instant.parse("2026-10-01T00:00:00Z");
        when(userRepository.findById(actor)).thenReturn(Optional.of(User.rehydrate(actor, "ator@rewit.test", "hash",
                AuthProvider.LOCAL, null, AccountStatus.ACTIVE, true, role, null, now, now)));
    }

    @Test
    @DisplayName("MODERATOR e ADMIN atuais passam")
    void moderatorAndAdminPass() {
        currentRole(Role.MODERATOR);
        assertDoesNotThrow(() -> policy.requireCurrentModerator(actor));
        currentRole(Role.ADMIN);
        assertDoesNotThrow(() -> policy.requireCurrentModerator(actor));
    }

    @Test
    @DisplayName("Cargo atual USER, ou usuário não encontrado: 403 FORBIDDEN")
    void userOrMissingIsForbidden() {
        currentRole(Role.USER);
        BusinessException demoted = assertThrows(BusinessException.class, () -> policy.requireCurrentModerator(actor));
        assertEquals("FORBIDDEN", demoted.getErrorCode());
        assertEquals(HttpStatus.FORBIDDEN, demoted.getStatus());

        when(userRepository.findById(actor)).thenReturn(Optional.empty());
        assertEquals("FORBIDDEN",
                assertThrows(BusinessException.class, () -> policy.requireCurrentModerator(actor)).getErrorCode());
    }

    @Test
    @DisplayName("Conta inoperante: 401 ACCOUNT_DISABLED antes de consultar o cargo")
    void disabledAccountFirst() {
        doThrow(AccountStatusPolicy.accountDisabled()).when(accountStatusPolicy).requireOperational(actor);

        BusinessException ex = assertThrows(BusinessException.class, () -> policy.requireCurrentModerator(actor));

        assertEquals("ACCOUNT_DISABLED", ex.getErrorCode());
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus());
        verifyNoInteractions(userRepository);
    }

    @Test
    @DisplayName("Ator ausente: 401 UNAUTHORIZED sem consultar conta nem cargo")
    void missingActor() {
        BusinessException ex = assertThrows(BusinessException.class, () -> policy.requireCurrentModerator(null));

        assertEquals("UNAUTHORIZED", ex.getErrorCode());
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus());
        verifyNoInteractions(accountStatusPolicy, userRepository);
    }
}
