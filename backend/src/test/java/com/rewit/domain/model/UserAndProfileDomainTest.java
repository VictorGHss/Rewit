package com.rewit.domain.model;

import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.AuthProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Testes de Domínio: Regras e Invariantes de User e Profile (Step 3)")
class UserAndProfileDomainTest {

    @Test
    @DisplayName("User: Deve normalizar e-mail com espaços e caracteres maiúsculos")
    void shouldNormalizeEmailProperly() {
        User user = new User(null, "   Usuario.Teste@REWIT.COM   ", "hashedPassword", AuthProvider.LOCAL, null);

        assertEquals("usuario.teste@rewit.com", user.getEmail());
        assertTrue(user.isActive());
        assertFalse(user.isVerified());
        assertNull(user.getDeletedAt());
        assertNotNull(user.getId());
        assertNotNull(user.getCreatedAt());
        assertNotNull(user.getUpdatedAt());
    }

    @Test
    @DisplayName("User: Deve rejeitar e-mail inválido, nulo ou vazio")
    void shouldRejectInvalidOrNullEmail() {
        assertThrows(BusinessException.class, () -> new User(null, null, "pass", AuthProvider.LOCAL, null));
        assertThrows(BusinessException.class, () -> new User(null, "", "pass", AuthProvider.LOCAL, null));
        assertThrows(BusinessException.class, () -> new User(null, "   ", "pass", AuthProvider.LOCAL, null));
        assertThrows(BusinessException.class, () -> new User(null, "invalid-email-without-at", "pass", AuthProvider.LOCAL, null));
    }

    @Test
    @DisplayName("User: Deve executar soft-delete mantendo histórico e desativando usuário")
    void shouldHandleSoftDeleteCorrectly() {
        User user = new User(null, "user@rewit.com", "pass", AuthProvider.LOCAL, null);
        assertFalse(user.isDeleted());
        assertTrue(user.isActive());

        user.softDelete();

        assertTrue(user.isDeleted());
        assertFalse(user.isActive());
        assertNotNull(user.getDeletedAt());
    }

    @Test
    @DisplayName("User: Suporte a identidade federada (Google / Apple) com providerUserId")
    void shouldSupportFederatedIdentity() {
        String googleId = "google-sub-123456";
        User googleUser = new User(null, "google.user@gmail.com", null, AuthProvider.GOOGLE, googleId);

        assertEquals(AuthProvider.GOOGLE, googleUser.getAuthProvider());
        assertEquals(googleId, googleUser.getProviderUserId());
        assertNull(googleUser.getPasswordHash());

        User localUser = new User(null, "local.user@rewit.com", "hash", AuthProvider.LOCAL, null);
        assertEquals(AuthProvider.LOCAL, localUser.getAuthProvider());
        assertNull(localUser.getProviderUserId());
    }

    @Test
    @DisplayName("User: Rehydrate deve restaurar todos os campos preservando integridade")
    void shouldRehydrateUserCorrectly() {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        Instant deleted = now.minusSeconds(3600);

        User rehydrated = User.rehydrate(id, "Rehydrate@Rewit.com", "hash",
                AuthProvider.APPLE, "apple-sub-789", false, true, deleted, now.minusSeconds(7200), now);

        assertEquals(id, rehydrated.getId());
        assertEquals("rehydrate@rewit.com", rehydrated.getEmail());
        assertEquals("apple-sub-789", rehydrated.getProviderUserId());
        assertEquals(AuthProvider.APPLE, rehydrated.getAuthProvider());
        assertFalse(rehydrated.isActive());
        assertTrue(rehydrated.isVerified());
        assertEquals(deleted, rehydrated.getDeletedAt());
    }

    @Test
    @DisplayName("Profile: Deve exigir userId, handle e displayName válidos")
    void shouldEnforceMandatoryFieldsOnProfile() {
        UUID userId = UUID.randomUUID();

        // Falta userId
        BusinessException exUserId = assertThrows(BusinessException.class, () ->
                new Profile(null, null, "usuario", "Nome", "Bio", null));
        assertEquals("MISSING_USER_ID", exUserId.getErrorCode());

        // Falta handle
        BusinessException exHandle = assertThrows(BusinessException.class, () ->
                new Profile(null, userId, null, "Nome", "Bio", null));
        assertEquals("INVALID_HANDLE", exHandle.getErrorCode());

        // Falta displayName
        BusinessException exDisplayName = assertThrows(BusinessException.class, () ->
                new Profile(null, userId, "usuario", null, "Bio", null));
        assertEquals("INVALID_DISPLAY_NAME", exDisplayName.getErrorCode());
    }

    @Test
    @DisplayName("Profile: Deve normalizar handle (@ inicial, espaços e maiúsculas)")
    void shouldNormalizeHandleProperly() {
        UUID userId = UUID.randomUUID();
        Profile profile = new Profile(null, userId, "   @MeuHandle_123   ", "Meu Nome", "Bio aqui", null);

        assertEquals("meuhandle_123", profile.getHandle());
        assertEquals("Meu Nome", profile.getDisplayName());
        assertEquals(0, profile.getReputationScore());
        assertFalse(profile.isAnonymousDefault());
    }

    @Test
    @DisplayName("Profile: reputationScore não pode ser negativo")
    void shouldPreventNegativeReputationScore() {
        UUID userId = UUID.randomUUID();
        Profile profile = new Profile(null, userId, "usuario", "Nome", null, null);

        assertEquals(0, profile.getReputationScore());

        profile.adjustReputation(10);
        assertEquals(10, profile.getReputationScore());

        // Redução válida
        profile.adjustReputation(-5);
        assertEquals(5, profile.getReputationScore());

        // Redução além de zero deve ser rejeitada
        BusinessException ex = assertThrows(BusinessException.class, () -> profile.adjustReputation(-10));
        assertEquals("INVALID_REPUTATION", ex.getErrorCode());

        // Rehydrate com score negativo também deve falhar
        assertThrows(BusinessException.class, () ->
                Profile.rehydrate(UUID.randomUUID(), userId, "user", "User", null, null, -1, false, null, null));
    }
}
