package com.rewit.presentation.controller;

import com.rewit.application.dto.business.BusinessResponseDtos.AdminRemovedBusinessResponseView;
import com.rewit.application.dto.business.BusinessResponseDtos.BusinessResponseModerationResult;
import com.rewit.application.dto.business.BusinessResponseDtos.ModerateBusinessResponseCommand;
import com.rewit.application.dto.business.BusinessResponseDtos.RemovedBusinessResponseFilter;
import com.rewit.application.dto.common.PageResult;
import com.rewit.application.usecase.ModerateBusinessResponseUseCase;
import com.rewit.application.usecase.QueryAdminRemovedBusinessResponsesUseCase;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.BusinessResponseRemovalSource;
import com.rewit.presentation.dto.admin.AdminBusinessResponseModerationDtos.AdminRemovedBusinessResponseResponse;
import com.rewit.presentation.dto.admin.AdminBusinessResponseModerationDtos.ModerateBusinessResponseRequest;
import com.rewit.presentation.dto.admin.AdminBusinessResponseModerationDtos.ModerateBusinessResponseResponse;
import com.rewit.presentation.dto.common.PagedResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
    private final QueryAdminRemovedBusinessResponsesUseCase queryAdminRemovedBusinessResponsesUseCase;

    public AdminBusinessResponseModerationController(
            ModerateBusinessResponseUseCase moderateBusinessResponseUseCase,
            QueryAdminRemovedBusinessResponsesUseCase queryAdminRemovedBusinessResponsesUseCase
    ) {
        this.moderateBusinessResponseUseCase = Objects.requireNonNull(moderateBusinessResponseUseCase,
                "ModerateBusinessResponseUseCase must not be null");
        this.queryAdminRemovedBusinessResponsesUseCase = Objects.requireNonNull(queryAdminRemovedBusinessResponsesUseCase,
                "QueryAdminRemovedBusinessResponsesUseCase must not be null");
    }

    /**
     * GET /api/v1/admin/business-responses/removed
     * Consulta administrativa das respostas oficiais removidas (C11), com paginação e filtros opcionais.
     */
    @GetMapping("/removed")
    @Operation(summary = "Consultar respostas oficiais removidas (paginado)", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<PagedResponse<AdminRemovedBusinessResponseResponse>> listRemovedBusinessResponses(
            @RequestParam(name = "reviewId", required = false) UUID reviewId,
            @RequestParam(name = "discussionId", required = false) UUID discussionId,
            @RequestParam(name = "businessAccountId", required = false) UUID businessAccountId,
            @RequestParam(name = "placeId", required = false) UUID placeId,
            @RequestParam(name = "removedBy", required = false) BusinessResponseRemovalSource removedBy,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            Authentication authentication
    ) {
        UUID actorUserId = extractAuthenticatedUserId(authentication);

        RemovedBusinessResponseFilter filter = new RemovedBusinessResponseFilter(
                reviewId,
                discussionId,
                businessAccountId,
                placeId,
                removedBy
        );

        PageResult<AdminRemovedBusinessResponseView> result =
                queryAdminRemovedBusinessResponsesUseCase.execute(actorUserId, filter, page, size);

        PagedResponse<AdminRemovedBusinessResponseResponse> response = PagedResponse.of(
                result.content().stream()
                        .map(AdminRemovedBusinessResponseResponse::fromView)
                        .toList(),
                result.pageNumber(),
                result.pageSize(),
                result.totalElements()
        );

        return ResponseEntity.ok(response);
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
