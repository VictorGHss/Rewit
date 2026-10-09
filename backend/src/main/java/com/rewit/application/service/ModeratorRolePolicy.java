package com.rewit.application.service;

import com.rewit.application.port.UserRepository;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.Role;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.UUID;

/**
 * Exige que o ator seja, agora, MODERATOR ou ADMIN com conta operacional.
 *
 * <p>A rota HTTP já filtra pela role do JWT ({@code @PreAuthorize}), mas o token carrega a role da emissão: um
 * moderador rebaixado continua com a autoridade antiga até o token expirar. Os casos de uso administrativos que
 * expõem ou alteram dados sensíveis revalidam aqui a role atual no banco.
 *
 * <p>Conta inoperante responde {@code 401 ACCOUNT_DISABLED} (AccountStatusPolicy); role insuficiente,
 * {@code 403 FORBIDDEN}.
 */
@Component
public class ModeratorRolePolicy {

    private final AccountStatusPolicy accountStatusPolicy;
    private final UserRepository userRepository;

    public ModeratorRolePolicy(AccountStatusPolicy accountStatusPolicy, UserRepository userRepository) {
        this.accountStatusPolicy = Objects.requireNonNull(accountStatusPolicy, "AccountStatusPolicy must not be null");
        this.userRepository = Objects.requireNonNull(userRepository, "UserRepository must not be null");
    }

    public void requireCurrentModerator(UUID actorUserId) {
        accountStatusPolicy.requireOperational(actorUserId);
        boolean moderator = userRepository.findById(actorUserId)
                .map(user -> user.getRole() == Role.MODERATOR || user.getRole() == Role.ADMIN)
                .orElse(false);
        if (!moderator) {
            throw new BusinessException("Acesso restrito a moderadores e administradores", HttpStatus.FORBIDDEN,
                    "FORBIDDEN");
        }
    }
}
