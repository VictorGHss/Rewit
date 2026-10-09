package com.rewit.presentation.controller;

import com.rewit.application.dto.business.BusinessDtos.BusinessAccountView;
import com.rewit.application.dto.business.BusinessDtos.PlaceClaimView;
import com.rewit.application.dto.common.PageResult;
import com.rewit.application.usecase.CreateBusinessAccountUseCase;
import com.rewit.application.usecase.ListBusinessPlaceClaimsUseCase;
import com.rewit.application.usecase.ListMyBusinessAccountsUseCase;
import com.rewit.application.usecase.RequestPlaceClaimUseCase;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.PlaceClaimStatus;
import com.rewit.presentation.dto.business.BusinessAccountResponse;
import com.rewit.presentation.dto.business.CreateBusinessAccountRequest;
import com.rewit.presentation.dto.business.PlaceClaimResponse;
import com.rewit.presentation.dto.business.RequestPlaceClaimRequest;
import com.rewit.presentation.dto.common.PagedResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Controller RESTful para contas comerciais e solicitações de reivindicação de locais (C9).
 *
 * <p>A identidade do solicitante é extraída exclusivamente do token de autenticação (JWT {@code sub}).
 * Nenhum identificador interno de usuário ou dado fiscal sensível é exposto em caminhos públicos.
 */
@RestController
@RequestMapping("/api/v1/business-accounts")
@Tag(name = "Contas Comerciais", description = "Endpoints para gerenciamento de contas comerciais e reivindicações de locais (C9)")
public class BusinessAccountController {

    private final CreateBusinessAccountUseCase createBusinessAccountUseCase;
    private final ListMyBusinessAccountsUseCase listMyBusinessAccountsUseCase;
    private final RequestPlaceClaimUseCase requestPlaceClaimUseCase;
    private final ListBusinessPlaceClaimsUseCase listBusinessPlaceClaimsUseCase;

    public BusinessAccountController(
            CreateBusinessAccountUseCase createBusinessAccountUseCase,
            ListMyBusinessAccountsUseCase listMyBusinessAccountsUseCase,
            RequestPlaceClaimUseCase requestPlaceClaimUseCase,
            ListBusinessPlaceClaimsUseCase listBusinessPlaceClaimsUseCase
    ) {
        this.createBusinessAccountUseCase = Objects.requireNonNull(createBusinessAccountUseCase,
                "CreateBusinessAccountUseCase must not be null");
        this.listMyBusinessAccountsUseCase = Objects.requireNonNull(listMyBusinessAccountsUseCase,
                "ListMyBusinessAccountsUseCase must not be null");
        this.requestPlaceClaimUseCase = Objects.requireNonNull(requestPlaceClaimUseCase,
                "RequestPlaceClaimUseCase must not be null");
        this.listBusinessPlaceClaimsUseCase = Objects.requireNonNull(listBusinessPlaceClaimsUseCase,
                "ListBusinessPlaceClaimsUseCase must not be null");
    }

    /**
     * POST /api/v1/business-accounts
     * Cadastra uma nova conta comercial vinculada ao usuário autenticado.
     */
    @PostMapping
    @Operation(summary = "Criar conta comercial", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<BusinessAccountResponse> createBusinessAccount(
            @Valid @RequestBody CreateBusinessAccountRequest request,
            Authentication authentication
    ) {
        UUID actorUserId = extractAuthenticatedUserId(authentication);
        BusinessAccountView view = createBusinessAccountUseCase.execute(
                actorUserId,
                request.corporateName(),
                request.taxId()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(BusinessAccountResponse.fromView(view));
    }

    /**
     * GET /api/v1/business-accounts/mine
     * Lista as contas comerciais administradas pelo usuário autenticado.
     */
    @GetMapping("/mine")
    @Operation(summary = "Listar contas comerciais do usuário autenticado", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<List<BusinessAccountResponse>> listMyBusinessAccounts(
            Authentication authentication
    ) {
        UUID actorUserId = extractAuthenticatedUserId(authentication);
        List<BusinessAccountView> views = listMyBusinessAccountsUseCase.execute(actorUserId);
        List<BusinessAccountResponse> response = views.stream()
                .map(BusinessAccountResponse::fromView)
                .toList();
        return ResponseEntity.ok(response);
    }

    /**
     * POST /api/v1/business-accounts/{businessAccountId}/place-claims
     * Solicita a reivindicação de um local para a conta comercial informada.
     */
    @PostMapping("/{businessAccountId}/place-claims")
    @Operation(summary = "Solicitar reivindicação de local", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<PlaceClaimResponse> requestPlaceClaim(
            @PathVariable("businessAccountId") UUID businessAccountId,
            @Valid @RequestBody RequestPlaceClaimRequest request,
            Authentication authentication
    ) {
        UUID actorUserId = extractAuthenticatedUserId(authentication);
        PlaceClaimView view = requestPlaceClaimUseCase.execute(
                actorUserId,
                businessAccountId,
                request.placeId(),
                request.evidenceDescription()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(PlaceClaimResponse.fromView(view));
    }

    /**
     * GET /api/v1/business-accounts/{businessAccountId}/place-claims
     * Lista o histórico de solicitações de reivindicação de uma conta comercial (paginado).
     */
    @GetMapping("/{businessAccountId}/place-claims")
    @Operation(summary = "Listar reivindicações de local de uma conta comercial", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<PagedResponse<PlaceClaimResponse>> listBusinessPlaceClaims(
            @PathVariable("businessAccountId") UUID businessAccountId,
            @RequestParam(name = "status", required = false) PlaceClaimStatus status,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            Authentication authentication
    ) {
        UUID actorUserId = extractAuthenticatedUserId(authentication);
        PageResult<PlaceClaimView> pageResult = listBusinessPlaceClaimsUseCase.execute(
                actorUserId,
                businessAccountId,
                status,
                page,
                size
        );

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

    private UUID extractAuthenticatedUserId(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            throw new BusinessException("Usuário não autenticado", HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        return UUID.fromString(authentication.getName());
    }
}
