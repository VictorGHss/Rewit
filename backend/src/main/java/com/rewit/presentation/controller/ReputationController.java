package com.rewit.presentation.controller;

import com.rewit.application.dto.reputation.ReputationDtos;
import com.rewit.application.service.ReputationService;
import com.rewit.common.exception.BusinessException;
import com.rewit.presentation.dto.reputation.ReputationResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.UUID;

/**
 * Controller RESTful para o subsistema de Reputação V1 (Step 23.0).
 *
 * <p>Endpoint: {@code GET /api/v1/users/{userId}/reputation}
 *
 * <p>Autenticação obrigatória (Bearer JWT).
 * Identidade do solicitante inferida do token — nunca do path ou body.
 *
 * <p>Não expõe: score numérico, PII, localização, reports, reviews individuais,
 * reviews anônimas, identidade de denunciantes.
 */
@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "Reputação", description = "Consulta de reputação pública de usuários (Step 23.0)")
public class ReputationController {

    private final ReputationService reputationService;

    public ReputationController(ReputationService reputationService) {
        this.reputationService = Objects.requireNonNull(reputationService,
            "reputationService must not be null");
    }

    @GetMapping("/{userId}/reputation")
    @Operation(
        summary = "Consultar sinais de reputação pública do usuário",
        description = "Retorna o snapshot de reputação V1 do usuário. "
            + "Nao expoe score numerico (ADR-008 nao define pesos formais). "
            + "Reviews anonimas excluidas dos sinais (ADR-008, Secao 3). "
            + "Reports nao sao usados como penalidade nesta versao.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    public ResponseEntity<ReputationResponse> getReputation(
            @PathVariable UUID userId,
            Authentication authentication
    ) {
        // Autenticação obrigatória — identidade do solicitante inferida do JWT
        if (authentication == null || authentication.getName() == null) {
            throw new BusinessException("Usuario nao autenticado", HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }

        ReputationDtos.ReputationView view = reputationService.getReputation(userId);
        return ResponseEntity.ok(ReputationResponse.from(view));
    }
}
