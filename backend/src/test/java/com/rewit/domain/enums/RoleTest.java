package com.rewit.domain.enums;

import com.rewit.domain.model.User;
import com.rewit.infrastructure.security.RewitUserPrincipal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Testes de Unidade: Enum Role e RewitUserPrincipal (Step 26.1)")
class RoleTest {

    @Test
    @DisplayName("Role deve gerar as authorities padronizadas do Spring Security")
    void shouldGenerateStandardSpringSecurityAuthorities() {
        assertEquals("ROLE_USER", Role.USER.getAuthority());
        assertEquals("ROLE_MODERATOR", Role.MODERATOR.getAuthority());
        assertEquals("ROLE_ADMIN", Role.ADMIN.getAuthority());
    }

    @Test
    @DisplayName("RewitUserPrincipal deve mapear ROLE_USER para usuário comum por padrão")
    void shouldMapRoleUserByDefault() {
        UUID userId = UUID.randomUUID();
        User user = new User(userId, "user@rewit.com", "hash", AuthProvider.LOCAL, null);

        assertEquals(Role.USER, user.getRole());

        RewitUserPrincipal principal = RewitUserPrincipal.fromUser(user);

        assertEquals(userId, principal.getId());
        assertEquals(userId.toString(), principal.getName());
        assertEquals("user@rewit.com", principal.getUsername());
        assertEquals(1, principal.getAuthorities().size());
        assertTrue(principal.getAuthorities().stream()
                .map(authority -> authority.getAuthority())
                .anyMatch(a -> a.equals("ROLE_USER")));
    }

    @Test
    @DisplayName("RewitUserPrincipal deve mapear ROLE_MODERATOR para usuário com Role.MODERATOR")
    void shouldMapRoleModerator() {
        UUID userId = UUID.randomUUID();
        User user = new User(userId, "mod@rewit.com", "hash", AuthProvider.LOCAL, null);
        user.changeRole(Role.MODERATOR);

        assertEquals(Role.MODERATOR, user.getRole());

        RewitUserPrincipal principal = RewitUserPrincipal.fromUser(user);

        assertEquals(1, principal.getAuthorities().size());
        assertTrue(principal.getAuthorities().stream()
                .map(authority -> authority.getAuthority())
                .anyMatch(a -> a.equals("ROLE_MODERATOR")));
    }

    @Test
    @DisplayName("RewitUserPrincipal deve mapear ROLE_ADMIN para usuário com Role.ADMIN")
    void shouldMapRoleAdmin() {
        UUID userId = UUID.randomUUID();
        User user = new User(userId, "admin@rewit.com", "hash", AuthProvider.LOCAL, null);
        user.changeRole(Role.ADMIN);

        assertEquals(Role.ADMIN, user.getRole());

        RewitUserPrincipal principal = RewitUserPrincipal.fromUser(user);

        assertEquals(1, principal.getAuthorities().size());
        assertTrue(principal.getAuthorities().stream()
                .map(authority -> authority.getAuthority())
                .anyMatch(a -> a.equals("ROLE_ADMIN")));
    }

    @Test
    @DisplayName("RewitUserPrincipal construtor com role nula deve aplicar fallback para ROLE_USER")
    void shouldFallbackToRoleUserWhenNull() {
        UUID userId = UUID.randomUUID();
        RewitUserPrincipal principal = new RewitUserPrincipal(userId, "nullrole@rewit.com", "hash", true, null);

        assertEquals(1, principal.getAuthorities().size());
        assertTrue(principal.getAuthorities().stream()
                .map(authority -> authority.getAuthority())
                .anyMatch(a -> a.equals("ROLE_USER")));
    }
}
