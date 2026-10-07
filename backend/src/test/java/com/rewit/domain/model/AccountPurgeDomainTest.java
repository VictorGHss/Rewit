package com.rewit.domain.model;

import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.AccountStatus;
import com.rewit.domain.enums.AuthProvider;
import com.rewit.domain.enums.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

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

    @Test
    @DisplayName("E-mail reservado: determinístico, normalizado, sem o endereço e no domínio reservado")
    void reservedEmail() {
        String reserved = User.reservedEmailFor("  Maria.Silva@Exemplo.com ");

        assertEquals(reserved, User.reservedEmailFor("maria.silva@exemplo.com"));
        assertNotEquals(reserved, User.reservedEmailFor("maria.silva2@exemplo.com"));
        assertTrue(reserved.matches("^[0-9a-f]{64}@deleted\\.invalid$"), reserved);
        assertFalse(reserved.contains("maria"));
        assertTrue(User.isReservedEmail(reserved));
        assertTrue(User.isReservedEmail("QUALQUER@DELETED.INVALID"));
        assertFalse(User.isReservedEmail("maria@exemplo.com"));
    }

    @Test
    @DisplayName("Conta DELETED: e-mail reservado, sem credencial nem id externo; segunda execução sem mudança")
    void purgePersonalDataIsIdempotent() {
        Instant now = Instant.now();
        User user = User.rehydrate(UUID.randomUUID(), "pessoa@exemplo.com", "hash", AuthProvider.GOOGLE, "google-123",
                AccountStatus.DELETED, true, Role.USER, now, now, now);

        assertTrue(user.purgePersonalData());
        assertEquals(User.reservedEmailFor("pessoa@exemplo.com"), user.getEmail());
        assertNull(user.getPasswordHash());
        assertNull(user.getProviderUserId());
        assertEquals(AccountStatus.DELETED, user.getStatus());
        assertEquals(now, user.getDeletedAt());

        Instant updatedAt = user.getUpdatedAt();
        assertFalse(user.purgePersonalData());
        assertEquals(User.reservedEmailFor("pessoa@exemplo.com"), user.getEmail(), "não re-hasheia o valor reservado");
        assertEquals(updatedAt, user.getUpdatedAt());
    }

    @Test
    @DisplayName("Só conta DELETED tem os dados pessoais removidos")
    void purgeRequiresDeleted() {
        Instant now = Instant.now();
        for (AccountStatus status : new AccountStatus[]{AccountStatus.ACTIVE, AccountStatus.DEACTIVATED, AccountStatus.SUSPENDED}) {
            User user = User.rehydrate(UUID.randomUUID(), "ativa@exemplo.com", "hash", AuthProvider.LOCAL, null,
                    status, false, Role.USER, null, now, now);
            BusinessException ex = assertThrows(BusinessException.class, user::purgePersonalData);
            assertEquals(HttpStatus.CONFLICT, ex.getStatus());
            assertEquals("ACCOUNT_NOT_DELETED", ex.getErrorCode());
            assertEquals("ativa@exemplo.com", user.getEmail());
        }
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
