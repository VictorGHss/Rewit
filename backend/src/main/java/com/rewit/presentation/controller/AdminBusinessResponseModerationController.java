package com.rewit.presentation.controller;

import com.rewit.application.dto.business.BusinessResponseDtos.BusinessResponseModerationResult;
import com.rewit.application.dto.business.BusinessResponseDtos.ModerateBusinessResponseCommand;
import com.rewit.application.usecase.ModerateBusinessResponseUseCase;
import com.rewit.common.exception.BusinessException;
import com.rewit.presentation.dto.admin.AdminBusinessResponseModerationDtos.ModerateBusinessResponseRequest;
import com.rewit.presentation.dto.admin.AdminBusinessResponseModerationDtos.ModerateBusinessResponseResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Controller RESTful para moderação administrativa de respostas oficiais de empresas (C11).
 *
 * <p>Acesso restrito a usuários com privilégios {@code MODERATOR} ou {@code ADMIN}.
 * A identidade do moderador é extraída exclusivamente do contexto JWT e revalidada no banco
 * pelo caso de uso.
 */
@RestController
@RequestMapping("/api/v1/admin/business-responses")
@PreAuthorize("hasAnyRole('MODERATOR', 'ADMIN')")
@Tag(name = "Moderação Administrativa - Respostas Oficiais", description = "Endpoints administrativos para moderação de respostas oficiais de empresas (C11)")
public class AdminBusinessResponseModerationController {

    private final ModerateBusinessResponseUseCase moderateBusinessResponseUseCase;

    public AdminBusinessResponseModerationController(ModerateBusinessResponseUseCase moderateBusinessResponseUseCase) {
        this.moderateBusinessResponseUseCase = Objects.requireNonNull(moderateBusinessResponseUseCase,
                "ModerateBusinessResponseUseCase must not be null");
    }

    /**
     * POST /api/v1/admin/business-responses/{responseId}/moderate
     * Executa ação administrativa de moderação (remoção ou restauração) sobre uma resposta oficial.
     */
    @PostMapping("/{responseId}/moderate")
    @Operation(summary = "Moderar resposta oficial de empresa", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ModerateBusinessResponseResponse> moderateBusinessResponse(
            @PathVariable("responseId") UUID responseId,
            @Valid @RequestBody ModerateBusinessResponseRequest request,
            Authentication authentication
    ) {
        UUID moderatorUserId = extractAuthenticatedUserId(authentication);

        ModerateBusinessResponseCommand command = new ModerateBusinessResponseCommand(
                responseId,
                moderatorUserId,
                request.action(),
                request.reasonCode(),
                request.justification(),
                Instant.now()
        );

        BusinessResponseModerationResult result = moderateBusinessResponseUseCase.execute(command);
        return ResponseEntity.ok(ModerateBusinessResponseResponse.fromResult(result));
    }

    private UUID extractAuthenticatedUserId(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            throw new BusinessException("Usuário não autenticado", HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        return UUID.fromString(authentication.getName());
    }
}
