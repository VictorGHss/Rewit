package com.rewit.domain.model;

import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.AccountStatus;
import com.rewit.domain.enums.AuthProvider;
import com.rewit.domain.enums.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Ciclo de vida da conta (C2): estados e transições do domínio")
class AccountLifecycleDomainTest {

    // ---- Transições válidas ----

    @Test
    @DisplayName("ACTIVE -> DEACTIVATED -> ACTIVE pelo próprio usuário")
    void deactivateAndReactivate() {
        User user = user(AccountStatus.ACTIVE);

        assertTrue(user.deactivate());
        assertState(user, AccountStatus.DEACTIVATED);

        assertTrue(user.reactivate());
        assertState(user, AccountStatus.ACTIVE);
    }

    @Test
    @DisplayName("ACTIVE -> SUSPENDED -> ACTIVE pela administração; DEACTIVATED também pode ser suspensa")
    void suspendAndReinstate() {
        User active = user(AccountStatus.ACTIVE);
        assertTrue(active.suspend());
        assertState(active, AccountStatus.SUSPENDED);
        assertTrue(active.reinstate());
        assertState(active, AccountStatus.ACTIVE);

        User deactivated = user(AccountStatus.DEACTIVATED);
        assertTrue(deactivated.suspend());
        assertState(deactivated, AccountStatus.SUSPENDED);
    }

    @Test
    @DisplayName("ACTIVE, DEACTIVATED e SUSPENDED -> DELETED, com deleted_at preenchido")
    void deleteFromEveryNonDeletedState() {
        for (AccountStatus from : new AccountStatus[]{AccountStatus.ACTIVE, AccountStatus.DEACTIVATED, AccountStatus.SUSPENDED}) {
            User user = user(from);
            assertTrue(user.softDelete(), from.name());
            assertState(user, AccountStatus.DELETED);
            assertEquals(user.getUpdatedAt(), user.getDeletedAt());
        }
    }

    // ---- Transições proibidas ----

    @Test
    @DisplayName("DELETED é definitivo: não volta a ACTIVE, DEACTIVATED nem SUSPENDED")
    void deletedIsFinal() {
        User deleted = user(AccountStatus.DELETED);

        assertDenied(deleted::reactivate);
        assertDenied(deleted::reinstate);
        assertDenied(deleted::deactivate);
        assertDenied(deleted::suspend);
        assertState(deleted, AccountStatus.DELETED);
    }

    @Test
    @DisplayName("SUSPENDED não é revertida nem desativada pelo usuário")
    void suspendedCannotBeUndoneByUser() {
        User suspended = user(AccountStatus.SUSPENDED);

        assertDenied(suspended::reactivate);
        assertDenied(suspended::deactivate);
        assertState(suspended, AccountStatus.SUSPENDED);
    }

    @Test
    @DisplayName("Reversão administrativa não sobrepõe a desativação do usuário; desativar exige conta ACTIVE")
    void reinstateOnlyFromSuspended() {
        assertDenied(user(AccountStatus.DEACTIVATED)::reinstate);
        assertDenied(user(AccountStatus.SUSPENDED)::deactivate);
    }

    // ---- Idempotência e invariantes ----

    @Test
    @DisplayName("Transição para o estado atual não muda nada (idempotente)")
    void sameStateIsNoOp() {
        Instant updatedAt = Instant.parse("2026-01-01T00:00:00Z");
        assertFalse(rehydrated(AccountStatus.ACTIVE, null, updatedAt).reactivate());
        assertFalse(rehydrated(AccountStatus.ACTIVE, null, updatedAt).reinstate());
        assertFalse(rehydrated(AccountStatus.DEACTIVATED, null, updatedAt).deactivate());
        assertFalse(rehydrated(AccountStatus.SUSPENDED, null, updatedAt).suspend());

        User deleted = rehydrated(AccountStatus.DELETED, updatedAt, updatedAt);
        assertFalse(deleted.softDelete());
        assertEquals(updatedAt, deleted.getUpdatedAt());
        assertEquals(updatedAt, deleted.getDeletedAt());
    }

    @Test
    @DisplayName("Só ACTIVE opera: isOperational, isActive e isDeleted derivam do estado")
    void operationalIsOnlyActive() {
        for (AccountStatus status : AccountStatus.values()) {
            User user = user(status);
            assertEquals(status == AccountStatus.ACTIVE, user.isOperational(), status.name());
            assertEquals(status == AccountStatus.ACTIVE, user.isActive(), status.name());
            assertEquals(status == AccountStatus.DELETED, user.isDeleted(), status.name());
        }
        assertState(new User(null, "novo@rewit.test", "hash", AuthProvider.LOCAL, null), AccountStatus.ACTIVE);
    }

    @Test
    @DisplayName("Representação anterior: excluída -> DELETED, ativa -> ACTIVE, inativa sem exclusão -> SUSPENDED")
    void legacyMapping() {
        Instant now = Instant.now();
        assertEquals(AccountStatus.ACTIVE, AccountStatus.fromLegacy(true, null));
        assertEquals(AccountStatus.SUSPENDED, AccountStatus.fromLegacy(false, null));
        assertEquals(AccountStatus.DELETED, AccountStatus.fromLegacy(false, now));

        User legacyInactive = User.rehydrate(UUID.randomUUID(), "legado@rewit.test", "hash", AuthProvider.LOCAL, null,
                false, false, Role.USER, null, now, now);
        assertState(legacyInactive, AccountStatus.SUSPENDED);
        assertDenied(legacyInactive::reactivate);
    }

    @Test
    @DisplayName("Reidratação rejeita deleted_at incoerente com o estado")
    void rehydrateRejectsInconsistentDeletedAt() {
        assertThrows(IllegalArgumentException.class, () -> rehydrated(AccountStatus.DELETED, null, Instant.now()));
        assertThrows(IllegalArgumentException.class,
                () -> rehydrated(AccountStatus.SUSPENDED, Instant.now(), Instant.now()));
    }

    private static User user(AccountStatus status) {
        Instant now = Instant.now();
        return rehydrated(status, status == AccountStatus.DELETED ? now : null, now);
    }

    private static User rehydrated(AccountStatus status, Instant deletedAt, Instant updatedAt) {
        return User.rehydrate(UUID.randomUUID(), "conta@rewit.test", "hash", AuthProvider.LOCAL, null,
                status, false, Role.USER, deletedAt, updatedAt, updatedAt);
    }

    private static void assertState(User user, AccountStatus expected) {
        assertEquals(expected, user.getStatus());
        if (expected == AccountStatus.DELETED) {
            assertNotNull(user.getDeletedAt());
        } else {
            assertNull(user.getDeletedAt());
        }
    }

    private static void assertDenied(Executable transition) {
        BusinessException ex = assertThrows(BusinessException.class, transition);
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals("ACCOUNT_STATUS_TRANSITION_DENIED", ex.getErrorCode());
    }
}
