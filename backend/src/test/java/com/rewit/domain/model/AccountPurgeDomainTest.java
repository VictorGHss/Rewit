package com.rewit.domain.model;

import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.AccountStatus;
import com.rewit.domain.enums.AuthProvider;
import com.rewit.domain.enums.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Purge de conta excluída (C2.3): minimização no domínio")
class AccountPurgeDomainTest {

    private static final String RESERVED = "a".repeat(64) + "@deleted.invalid";

    @Test
    @DisplayName("Domínio reservado: reconhecido sem diferença de caixa; endereço comum não")
    void reservedDomain() {
        assertTrue(User.isReservedEmail(RESERVED));
        assertTrue(User.isReservedEmail("QUALQUER@DELETED.INVALID"));
        assertFalse(User.isReservedEmail("maria@exemplo.com"));
        assertFalse(User.isReservedEmail("maria@deleted.invalid.com"));
    }

    @Test
    @DisplayName("Período de arrependimento: elegível a partir de exatamente 30 dias após a exclusão")
    void purgeGracePeriod() {
        Instant deletedAt = Instant.parse("2026-01-01T00:00:00Z");
        User user = deleted(deletedAt);

        assertFalse(user.isPurgeEligible(deletedAt));
        assertFalse(user.isPurgeEligible(deletedAt.plus(Duration.ofDays(30)).minusMillis(1)));
        assertTrue(user.isPurgeEligible(deletedAt.plus(Duration.ofDays(30))), "o limite exato já é elegível");
        assertTrue(user.isPurgeEligible(deletedAt.plus(Duration.ofDays(31))));
        assertEquals(Duration.ofDays(30), User.PURGE_GRACE_PERIOD);
    }

    @Test
    @DisplayName("Conta DELETED: e-mail reservado, sem credencial nem id externo; segunda execução sem mudança")
    void purgePersonalDataIsIdempotent() {
        Instant now = Instant.now();
        User user = User.rehydrate(UUID.randomUUID(), "pessoa@exemplo.com", "hash", AuthProvider.GOOGLE, "google-123",
                AccountStatus.DELETED, true, Role.USER, now, now, now);

        assertTrue(user.purgePersonalData(RESERVED));
        assertEquals(RESERVED, user.getEmail());
        assertNull(user.getPasswordHash());
        assertNull(user.getProviderUserId());
        assertEquals(AccountStatus.DELETED, user.getStatus());
        assertEquals(now, user.getDeletedAt());

        Instant updatedAt = user.getUpdatedAt();
        assertFalse(user.purgePersonalData("b".repeat(64) + "@deleted.invalid"));
        assertEquals(RESERVED, user.getEmail(), "a reserva já gravada não é substituída");
        assertEquals(updatedAt, user.getUpdatedAt());
    }

    @Test
    @DisplayName("O valor reservado precisa estar no domínio reservado")
    void reservedValueMustBeInReservedDomain() {
        User user = deleted(Instant.now());
        assertThrows(IllegalArgumentException.class, () -> user.purgePersonalData("pessoa@exemplo.com"));
        assertThrows(IllegalArgumentException.class, () -> user.purgePersonalData(null));
        assertEquals("pessoa@exemplo.com", user.getEmail());
    }

    @Test
    @DisplayName("Só conta DELETED tem os dados pessoais removidos ou é avaliada para o purge")
    void purgeRequiresDeleted() {
        Instant now = Instant.now();
        for (AccountStatus status : new AccountStatus[]{AccountStatus.ACTIVE, AccountStatus.DEACTIVATED, AccountStatus.SUSPENDED}) {
            User user = User.rehydrate(UUID.randomUUID(), "ativa@exemplo.com", "hash", AuthProvider.LOCAL, null,
                    status, false, Role.USER, null, now, now);
            BusinessException ex = assertThrows(BusinessException.class, () -> user.purgePersonalData(RESERVED));
            assertEquals(HttpStatus.CONFLICT, ex.getStatus());
            assertEquals("ACCOUNT_NOT_DELETED", ex.getErrorCode());
            assertThrows(BusinessException.class, () -> user.isPurgeEligible(now));
            assertEquals("ativa@exemplo.com", user.getEmail());
        }
    }

    private static User deleted(Instant deletedAt) {
        return User.rehydrate(UUID.randomUUID(), "pessoa@exemplo.com", "hash", AuthProvider.LOCAL, null,
                AccountStatus.DELETED, false, Role.USER, deletedAt, deletedAt, deletedAt);
    }

    @Test
    @DisplayName("Perfil: handle reservado único e fora do padrão do cadastro, nome neutro, sem bio/avatar; idempotente")
    void profileAnonymization() {
        UUID userId = UUID.randomUUID();
        Profile profile = Profile.rehydrate(UUID.randomUUID(), userId, "maria", "Maria Silva", "Bio", "https://cdn/a.png",
                12, false, Instant.now(), Instant.now());

        assertTrue(profile.anonymizeForDeletedAccount());
        String handle = Profile.deletedHandleFor(userId);
        assertEquals(handle, profile.getHandle());
        assertEquals("Usuário excluído", profile.getDisplayName());
        assertNull(profile.getBio());
        assertNull(profile.getAvatarUrl());
        assertEquals(0, profile.getReputationScore());

        assertTrue(handle.length() <= 64, "cabe na coluna");
        assertFalse(handle.matches("^@?[a-zA-Z0-9_]{3,30}$"), "nenhum cadastro ou edição produz esse handle");
        assertNotEquals(handle, Profile.deletedHandleFor(UUID.randomUUID()));

        assertFalse(profile.anonymizeForDeletedAccount());
    }
}
