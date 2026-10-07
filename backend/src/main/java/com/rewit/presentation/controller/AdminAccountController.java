package com.rewit.presentation.controller;

import com.rewit.application.usecase.AdminAccountLifecycleUseCase;
import com.rewit.application.usecase.AdminAccountLifecycleUseCase.Action;
import com.rewit.common.exception.BusinessException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.UUID;

/**
 * Ciclo de vida de contas por ação administrativa (C2): suspensão, reversão da suspensão e exclusão lógica.
 *
 * <p>Restrito a {@code ADMIN} (sanção de conta, mais ampla que a moderação de conteúdo de MODERATOR). A role do
 * JWT é só a primeira barreira: o caso de uso confirma o estado e a role atuais do ator no banco.
 */
@RestController
@RequestMapping("/api/v1/admin/users")
@Tag(name = "Contas (Administração)", description = "Suspensão, reversão da suspensão e exclusão lógica de contas (C2)")
public class AdminAccountController {

    private final AdminAccountLifecycleUseCase adminAccountLifecycleUseCase;

    public AdminAccountController(AdminAccountLifecycleUseCase adminAccountLifecycleUseCase) {
        this.adminAccountLifecycleUseCase = Objects.requireNonNull(adminAccountLifecycleUseCase,
                "adminAccountLifecycleUseCase must not be null");
    }

    @PostMapping("/{userId}/suspend")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Suspender conta (também uma conta desativada) e revogar as sessões",
            security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<Void> suspend(@PathVariable("userId") UUID userId, Authentication authentication) {
        adminAccountLifecycleUseCase.execute(extractAuthenticatedUserId(authentication), userId, Action.SUSPEND);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{userId}/reinstate")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Reverter a suspensão de uma conta e revogar as sessões",
            security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<Void> reinstate(@PathVariable("userId") UUID userId, Authentication authentication) {
        adminAccountLifecycleUseCase.execute(extractAuthenticatedUserId(authentication), userId, Action.REINSTATE);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{userId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Excluir logicamente uma conta (definitivo) e revogar as sessões",
            security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<Void> delete(@PathVariable("userId") UUID userId, Authentication authentication) {
        adminAccountLifecycleUseCase.execute(extractAuthenticatedUserId(authentication), userId, Action.DELETE);
        return ResponseEntity.noContent().build();
    }

    private UUID extractAuthenticatedUserId(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            throw new BusinessException("Usuário não autenticado", HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        return UUID.fromString(authentication.getName());
    }
}
