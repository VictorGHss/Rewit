package com.rewit.application.usecase;

import com.rewit.application.port.AuthSessionRepository;
import com.rewit.application.port.UserRepository;
import com.rewit.application.service.AccountStatusPolicy;
import com.rewit.domain.model.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

/**
 * Desativação da própria conta (C2): {@code ACTIVE -> DEACTIVATED}, revertida pelo próprio usuário em
 * {@code POST /api/v1/auth/reactivate}.
 *
 * <p>A linha da conta é travada e o estado relido depois do lock: uma suspensão ou exclusão concorrente confirmada
 * antes vence, e a resposta é o {@code 401 ACCOUNT_DISABLED} genérico (nunca um código que revele o estado). As
 * sessões são revogadas na mesma transação da transição.
 */
@Service
public class DeactivateAccountUseCase {

    private final UserRepository userRepository;
    private final AuthSessionRepository authSessionRepository;

    public DeactivateAccountUseCase(UserRepository userRepository, AuthSessionRepository authSessionRepository) {
        this.userRepository = Objects.requireNonNull(userRepository, "userRepository must not be null");
        this.authSessionRepository = Objects.requireNonNull(authSessionRepository, "authSessionRepository must not be null");
    }

    @Transactional
    public void execute(UUID userId) {
        if (userId == null) {
            throw AccountStatusPolicy.accountDisabled();
        }

        User user = userRepository.findByIdForUpdate(userId)
                .filter(User::isOperational)
                .orElseThrow(AccountStatusPolicy::accountDisabled);

        user.deactivate();
        userRepository.save(user);
        authSessionRepository.revokeAllByUserId(userId);
    }
}
