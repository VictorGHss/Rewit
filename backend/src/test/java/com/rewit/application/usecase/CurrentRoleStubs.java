package com.rewit.application.usecase;

import com.rewit.application.port.UserRepository;
import com.rewit.domain.enums.AccountStatus;
import com.rewit.domain.enums.AuthProvider;
import com.rewit.domain.enums.Role;
import com.rewit.domain.model.User;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Cargo atual do ator para testes de unidade que usam a ModeratorRolePolicy real sobre um UserRepository simulado.
 */
final class CurrentRoleStubs {

    private static final Instant CREATED_AT = Instant.parse("2026-10-01T00:00:00Z");

    private CurrentRoleStubs() {
    }

    /** Papel padrão do ator para todos os testes da classe; tolerado mesmo nos testes que falham antes da política. */
    static void defaultRole(UserRepository userRepository, UUID actorUserId, Role role) {
        lenient().when(userRepository.findById(actorUserId)).thenReturn(Optional.of(user(actorUserId, role)));
    }

    static void currentRole(UserRepository userRepository, UUID actorUserId, Role role) {
        when(userRepository.findById(actorUserId)).thenReturn(Optional.of(user(actorUserId, role)));
    }

    private static User user(UUID actorUserId, Role role) {
        return User.rehydrate(actorUserId, "ator@rewit.test", "hash", AuthProvider.LOCAL, null, AccountStatus.ACTIVE, true,
                role, null, CREATED_AT, CREATED_AT);
    }
}
