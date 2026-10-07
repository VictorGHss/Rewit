package com.rewit.application.usecase;

import com.rewit.application.port.AuthSessionRepository;
import com.rewit.application.port.UserRepository;
import com.rewit.application.service.AccountStatusPolicy;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.Role;
import com.rewit.domain.model.User;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

/**
 * Ciclo de vida da conta por ação administrativa (C2): suspensão, reversão da suspensão e exclusão lógica.
 *
 * <p>Regras:
 * <ul>
 *   <li>o ator precisa estar operacional e ser {@code ADMIN} no estado atual do banco: a role do JWT pode ser
 *       anterior a um rebaixamento;</li>
 *   <li>o ator não age sobre a própria conta;</li>
 *   <li>a linha da conta alvo é travada e a transição validada pelo domínio sobre o estado já confirmado
 *       (409 {@code ACCOUNT_STATUS_TRANSITION_DENIED} se proibida; idempotente se já no estado de destino);</li>
 *   <li>uma transição que muda o estado revoga as sessões do alvo na mesma transação.</li>
 * </ul>
 * Motivo, histórico e auditoria das decisões ficam para uma etapa posterior.
 */
@Service
public class AdminAccountLifecycleUseCase {

    public enum Action {
        SUSPEND,
        REINSTATE,
        DELETE
    }

    private final UserRepository userRepository;
    private final AuthSessionRepository authSessionRepository;

    public AdminAccountLifecycleUseCase(UserRepository userRepository, AuthSessionRepository authSessionRepository) {
        this.userRepository = Objects.requireNonNull(userRepository, "userRepository must not be null");
        this.authSessionRepository = Objects.requireNonNull(authSessionRepository, "authSessionRepository must not be null");
    }

    @Transactional
    public void execute(UUID actorUserId, UUID targetUserId, Action action) {
        if (actorUserId == null) {
            throw AccountStatusPolicy.accountDisabled();
        }
        if (targetUserId == null) {
            throw new BusinessException("Identificador de usuário obrigatório", HttpStatus.BAD_REQUEST, "MISSING_USER_ID");
        }
        Objects.requireNonNull(action, "action must not be null");

        // Estado e role atuais do ator, sem lock (mesma semântica de AccountStatusPolicy)
        User actor = userRepository.findById(actorUserId)
                .filter(User::isOperational)
                .orElseThrow(AccountStatusPolicy::accountDisabled);
        if (actor.getRole() != Role.ADMIN) {
            throw new BusinessException("Acesso negado", HttpStatus.FORBIDDEN, "FORBIDDEN");
        }
        if (actorUserId.equals(targetUserId)) {
            throw new BusinessException("O administrador não pode alterar o estado da própria conta",
                    HttpStatus.FORBIDDEN, "SELF_ACCOUNT_LIFECYCLE_FORBIDDEN");
        }

        User target = userRepository.findByIdForUpdate(targetUserId)
                .orElseThrow(() -> new BusinessException("Usuário não encontrado", HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));

        boolean changed = switch (action) {
            case SUSPEND -> target.suspend();
            case REINSTATE -> target.reinstate();
            case DELETE -> target.softDelete();
        };
        if (changed) {
            userRepository.save(target);
            authSessionRepository.revokeAllByUserId(targetUserId);
        }
    }
}
