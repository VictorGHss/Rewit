package com.rewit.presentation.controller;

import com.rewit.application.dto.business.BusinessDtos.PlaceClaimView;
import com.rewit.application.dto.common.PageResult;
import com.rewit.application.usecase.DecidePlaceClaimUseCase;
import com.rewit.application.usecase.QueryAdminPlaceClaimsUseCase;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.PlaceClaimStatus;
import com.rewit.presentation.dto.business.DecidePlaceClaimRequest;
import com.rewit.presentation.dto.business.PlaceClaimResponse;
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
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Controller RESTful para moderação administrativa de reivindicações de local (C9).
 *
 * <p>Acesso restrito a usuários com privilégios {@code MODERATOR} ou {@code ADMIN}.
 * A identidade do moderador é extraída exclusivamente do contexto de autenticação JWT.
 */
@RestController
@RequestMapping("/api/v1/admin/place-claims")
@PreAuthorize("hasAnyRole('MODERATOR', 'ADMIN')")
@Tag(name = "Moderação Administrativa - Reivindicações", description = "Endpoints administrativos para análise e decisão de reivindicações de locais (C9)")
public class AdminPlaceClaimController {

    private final QueryAdminPlaceClaimsUseCase queryAdminPlaceClaimsUseCase;
    private final DecidePlaceClaimUseCase decidePlaceClaimUseCase;

    public AdminPlaceClaimController(
            QueryAdminPlaceClaimsUseCase queryAdminPlaceClaimsUseCase,
            DecidePlaceClaimUseCase decidePlaceClaimUseCase
    ) {
        this.queryAdminPlaceClaimsUseCase = Objects.requireNonNull(queryAdminPlaceClaimsUseCase,
                "QueryAdminPlaceClaimsUseCase must not be null");
        this.decidePlaceClaimUseCase = Objects.requireNonNull(decidePlaceClaimUseCase,
                "DecidePlaceClaimUseCase must not be null");
    }

    /**
     * GET /api/v1/admin/place-claims
     * Fila administrativa de solicitações de reivindicação (paginado).
     */
    @GetMapping
    @Operation(summary = "Listar fila administrativa de reivindicações (paginado)", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<PagedResponse<PlaceClaimResponse>> listAdminPlaceClaims(
            @RequestParam(name = "status", required = false) PlaceClaimStatus status,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            Authentication authentication
    ) {
        extractAuthenticatedUserId(authentication);
        PageResult<PlaceClaimView> pageResult = queryAdminPlaceClaimsUseCase.execute(page, size, status);

        List<PlaceClaimResponse> content = pageResult.content().stream()
                .map(PlaceClaimResponse::fromView)
                .toList();

        PagedResponse<PlaceClaimResponse> response = PagedResponse.of(
                content,
                pageResult.pageNumber(),
                pageResult.pageSize(),
                pageResult.totalElements()
        );

        return ResponseEntity.ok(response);
    }

    /**
     * POST /api/v1/admin/place-claims/{claimId}/decision
     * Aplica uma decisão (aprovar ou rejeitar) a uma solicitação de reivindicação com justificativa obrigatória.
     */
    @PostMapping("/{claimId}/decision")
    @Operation(summary = "Decidir solicitação de reivindicação de local", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<PlaceClaimResponse> decidePlaceClaim(
            @PathVariable("claimId") UUID claimId,
            @Valid @RequestBody DecidePlaceClaimRequest request,
            Authentication authentication
    ) {
        UUID actorUserId = extractAuthenticatedUserId(authentication);
        PlaceClaimView view = decidePlaceClaimUseCase.execute(
                actorUserId,
                claimId,
                request.decision(),
                request.justification(),
                Instant.now()
        );
        return ResponseEntity.ok(PlaceClaimResponse.fromView(view));
    }

    private UUID extractAuthenticatedUserId(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            throw new BusinessException("Usuário não autenticado", HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        return UUID.fromString(authentication.getName());
    }
}
