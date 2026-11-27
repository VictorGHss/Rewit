package com.rewit.application.usecase;

import com.rewit.application.port.AccountModerationAuditLogRepository;
import com.rewit.application.port.AuthSessionRepository;
import com.rewit.application.port.UserRepository;
import com.rewit.application.service.AccountStatusPolicy;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.AccountStatus;
import com.rewit.domain.enums.Role;
import com.rewit.domain.model.AccountModerationAuditLog;
import com.rewit.domain.model.User;
import com.rewit.infrastructure.persistence.routing.ModerationTransactional;
import com.rewit.infrastructure.security.ModerationAuditMacService;
import com.rewit.infrastructure.security.ModerationAuditPayload;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
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
    private final AccountModerationAuditLogRepository accountModerationAuditLogRepository;
    private final ModerationAuditMacService moderationAuditMacService;

    @Autowired
    public AdminAccountLifecycleUseCase(UserRepository userRepository,
                                        AuthSessionRepository authSessionRepository,
                                        AccountModerationAuditLogRepository accountModerationAuditLogRepository,
                                        ModerationAuditMacService moderationAuditMacService) {
        this.userRepository = Objects.requireNonNull(userRepository, "userRepository must not be null");
        this.authSessionRepository = Objects.requireNonNull(authSessionRepository, "authSessionRepository must not be null");
        this.accountModerationAuditLogRepository = Objects.requireNonNull(accountModerationAuditLogRepository,
                "accountModerationAuditLogRepository must not be null");
        this.moderationAuditMacService = Objects.requireNonNull(moderationAuditMacService,
                "moderationAuditMacService must not be null");
    }

    public AdminAccountLifecycleUseCase(UserRepository userRepository, AuthSessionRepository authSessionRepository) {
        this.userRepository = Objects.requireNonNull(userRepository, "userRepository must not be null");
        this.authSessionRepository = Objects.requireNonNull(authSessionRepository, "authSessionRepository must not be null");
        this.accountModerationAuditLogRepository = null;
        this.moderationAuditMacService = null;
    }

    @ModerationTransactional
    public void execute(UUID actorUserId, UUID targetUserId, Action action) {
        execute(actorUserId, targetUserId, action, null);
    }

    @ModerationTransactional
    public void execute(UUID actorUserId, UUID targetUserId, Action action, String reason) {
        if (actorUserId == null) {
            throw AccountStatusPolicy.accountDisabled();
        }
        if (targetUserId == null) {
            throw new BusinessException("Identificador de usuário obrigatório", HttpStatus.BAD_REQUEST, "MISSING_USER_ID");
        }
        Objects.requireNonNull(action, "action must not be null");

        String normalizedReason = normalizeReason(action, reason);

        // Estado e role atuais do ator, sem lock (mesma semântica de AccountStatusPolicy)
        User actor = userRepository.findById(actorUserId)
                .filter(account -> account.isOperational())
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

        AccountStatus previousStatus = target.getStatus();
        boolean changed = switch (action) {
            case SUSPEND -> target.suspend();
            case REINSTATE -> target.reinstate();
            case DELETE -> target.softDelete();
        };
        if (changed) {
            userRepository.save(target);
            authSessionRepository.revokeAllByUserId(targetUserId);

            if (accountModerationAuditLogRepository != null) {
                AccountModerationAuditLog.Action auditAction = switch (action) {
                    case SUSPEND -> AccountModerationAuditLog.Action.SUSPEND;
                    case REINSTATE -> AccountModerationAuditLog.Action.REINSTATE;
                    case DELETE -> AccountModerationAuditLog.Action.DELETE;
                };
                Instant occurredAt = ModerationAuditPayload.canonicalTimestamp(Instant.now());
                UUID auditId = UUID.randomUUID();
                String authenticityMac = moderationAuditMacService != null
                        ? moderationAuditMacService.computeMac(ModerationAuditPayload.forAccountLifecycleAudit(
                                auditId,
                                targetUserId,
                                actorUserId,
                                auditAction.name(),
                                previousStatus.name(),
                                target.getStatus().name(),
                                normalizedReason,
                                occurredAt))
                        : null;
                accountModerationAuditLogRepository.save(new AccountModerationAuditLog(
                        auditId,
                        targetUserId,
                        actorUserId,
                        auditAction,
                        previousStatus,
                        target.getStatus(),
                        normalizedReason,
                        occurredAt,
                        authenticityMac));
            }
        }
    }

    private static String normalizeReason(Action action, String reason) {
        if (reason != null && !reason.isBlank()) {
            String trimmed = reason.trim();
            if (trimmed.length() > AccountModerationAuditLog.MAX_REASON_LENGTH) {
                throw new BusinessException("O motivo não pode exceder 1000 caracteres",
                        HttpStatus.BAD_REQUEST, "INVALID_REASON_LENGTH");
            }
            return trimmed;
        }
        return switch (action) {
            case SUSPEND -> "Suspensão administrativa de conta";
            case REINSTATE -> "Reintegração administrativa de conta";
            case DELETE -> "Exclusão administrativa de conta";
        };
    }
}
