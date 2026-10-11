package com.rewit.presentation.controller;

import com.rewit.application.dto.business.BusinessResponseDtos.BusinessResponseEligibilityView;
import com.rewit.application.dto.business.BusinessResponseDtos.BusinessResponseView;
import com.rewit.application.dto.business.BusinessResponseDtos.PublishDiscussionBusinessResponseCommand;
import com.rewit.application.dto.business.BusinessResponseDtos.PublishReviewBusinessResponseCommand;
import com.rewit.application.dto.common.PageResult;
import com.rewit.application.usecase.GetBusinessResponseEligibilityUseCase;
import com.rewit.application.usecase.ListDiscussionBusinessResponsesUseCase;
import com.rewit.application.usecase.ListReviewBusinessResponsesUseCase;
import com.rewit.application.usecase.PublishDiscussionBusinessResponseUseCase;
import com.rewit.application.usecase.PublishReviewBusinessResponseUseCase;
import com.rewit.application.usecase.RemoveBusinessResponseUseCase;
import com.rewit.common.exception.BusinessException;
import com.rewit.presentation.dto.business.BusinessResponseEligibilityResponse;
import com.rewit.presentation.dto.business.BusinessResponseResponse;
import com.rewit.presentation.dto.business.PublishBusinessResponseRequest;
import com.rewit.presentation.dto.common.PagedResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Controller RESTful para respostas oficiais de empresas (C11).
 *
 * <p>Disponibiliza publicação e consulta de respostas oficiais associadas a avaliações e
 * comentários raiz, consulta de elegibilidade e remoção da própria resposta pela empresa.
 * A identidade do solicitante é extraída exclusivamente do contexto de autenticação JWT.
 */
@RestController
@Tag(name = "Respostas Oficiais", description = "Endpoints para publicação, consulta e remoção de respostas oficiais de empresas (C11)")
public class BusinessResponseController {

    private final PublishReviewBusinessResponseUseCase publishReviewBusinessResponseUseCase;
    private final ListReviewBusinessResponsesUseCase listReviewBusinessResponsesUseCase;
    private final GetBusinessResponseEligibilityUseCase getBusinessResponseEligibilityUseCase;
    private final PublishDiscussionBusinessResponseUseCase publishDiscussionBusinessResponseUseCase;
    private final ListDiscussionBusinessResponsesUseCase listDiscussionBusinessResponsesUseCase;
    private final RemoveBusinessResponseUseCase removeBusinessResponseUseCase;

    public BusinessResponseController(
            PublishReviewBusinessResponseUseCase publishReviewBusinessResponseUseCase,
            ListReviewBusinessResponsesUseCase listReviewBusinessResponsesUseCase,
            GetBusinessResponseEligibilityUseCase getBusinessResponseEligibilityUseCase,
            PublishDiscussionBusinessResponseUseCase publishDiscussionBusinessResponseUseCase,
            ListDiscussionBusinessResponsesUseCase listDiscussionBusinessResponsesUseCase,
            RemoveBusinessResponseUseCase removeBusinessResponseUseCase
    ) {
        this.publishReviewBusinessResponseUseCase = Objects.requireNonNull(publishReviewBusinessResponseUseCase,
                "PublishReviewBusinessResponseUseCase must not be null");
        this.listReviewBusinessResponsesUseCase = Objects.requireNonNull(listReviewBusinessResponsesUseCase,
                "ListReviewBusinessResponsesUseCase must not be null");
        this.getBusinessResponseEligibilityUseCase = Objects.requireNonNull(getBusinessResponseEligibilityUseCase,
                "GetBusinessResponseEligibilityUseCase must not be null");
        this.publishDiscussionBusinessResponseUseCase = Objects.requireNonNull(publishDiscussionBusinessResponseUseCase,
                "PublishDiscussionBusinessResponseUseCase must not be null");
        this.listDiscussionBusinessResponsesUseCase = Objects.requireNonNull(listDiscussionBusinessResponsesUseCase,
                "ListDiscussionBusinessResponsesUseCase must not be null");
        this.removeBusinessResponseUseCase = Objects.requireNonNull(removeBusinessResponseUseCase,
                "RemoveBusinessResponseUseCase must not be null");
    }

    /**
     * POST /api/v1/reviews/{reviewId}/business-responses
     * Publica uma resposta oficial de empresa a uma avaliação.
     */
    @PostMapping("/api/v1/reviews/{reviewId}/business-responses")
    @Operation(summary = "Publicar resposta oficial à avaliação", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<BusinessResponseResponse> publishReviewBusinessResponse(
            @PathVariable("reviewId") UUID reviewId,
            @Valid @RequestBody PublishBusinessResponseRequest request,
            Authentication authentication
    ) {
        UUID actorUserId = extractAuthenticatedUserId(authentication);
        PublishReviewBusinessResponseCommand command = new PublishReviewBusinessResponseCommand(
                actorUserId,
                request.businessAccountId(),
                reviewId,
                request.placeId(),
                request.content()
        );
        BusinessResponseView view = publishReviewBusinessResponseUseCase.execute(command);
        return ResponseEntity.status(HttpStatus.CREATED).body(BusinessResponseResponse.fromView(view));
    }

    /**
     * GET /api/v1/reviews/{reviewId}/business-responses
     * Lista respostas oficiais ativas associadas a uma avaliação (paginado).
     */
    @GetMapping("/api/v1/reviews/{reviewId}/business-responses")
    @Operation(summary = "Listar respostas oficiais ativas da avaliação", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<PagedResponse<BusinessResponseResponse>> listReviewBusinessResponses(
            @PathVariable("reviewId") UUID reviewId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            Authentication authentication
    ) {
        UUID viewerUserId = extractAuthenticatedUserId(authentication);
        PageResult<BusinessResponseView> pageResult = listReviewBusinessResponsesUseCase.execute(
                viewerUserId,
                reviewId,
                page,
                size
        );
        List<BusinessResponseResponse> responses = pageResult.content().stream()
                .map(BusinessResponseResponse::fromView)
                .toList();
        return ResponseEntity.ok(PagedResponse.of(
                responses,
                pageResult.pageNumber(),
                pageResult.pageSize(),
                pageResult.totalElements()
        ));
    }

    /**
     * GET /api/v1/reviews/{reviewId}/business-responses/eligibility
     * Consulta as contas aprovadas administradas pelo usuário autenticado que podem responder à avaliação.
     */
    @GetMapping("/api/v1/reviews/{reviewId}/business-responses/eligibility")
    @Operation(summary = "Consultar elegibilidade para responder oficialmente à avaliação", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<BusinessResponseEligibilityResponse> getBusinessResponseEligibility(
            @PathVariable("reviewId") UUID reviewId,
            Authentication authentication
    ) {
        UUID actorUserId = extractAuthenticatedUserId(authentication);
        BusinessResponseEligibilityView view = getBusinessResponseEligibilityUseCase.execute(actorUserId, reviewId);
        return ResponseEntity.ok(BusinessResponseEligibilityResponse.fromView(view));
    }

    /**
     * POST /api/v1/discussions/{discussionId}/business-responses
     * Publica uma resposta oficial de empresa a um comentário raiz de avaliação.
     */
    @PostMapping("/api/v1/discussions/{discussionId}/business-responses")
    @Operation(summary = "Publicar resposta oficial ao comentário raiz", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<BusinessResponseResponse> publishDiscussionBusinessResponse(
            @PathVariable("discussionId") UUID discussionId,
            @Valid @RequestBody PublishBusinessResponseRequest request,
            Authentication authentication
    ) {
        UUID actorUserId = extractAuthenticatedUserId(authentication);
        PublishDiscussionBusinessResponseCommand command = new PublishDiscussionBusinessResponseCommand(
                actorUserId,
                request.businessAccountId(),
                discussionId,
                request.placeId(),
                request.content()
        );
        BusinessResponseView view = publishDiscussionBusinessResponseUseCase.execute(command);
        return ResponseEntity.status(HttpStatus.CREATED).body(BusinessResponseResponse.fromView(view));
    }

    /**
     * GET /api/v1/discussions/{discussionId}/business-responses
     * Lista respostas oficiais ativas associadas a um comentário raiz (paginado).
     */
    @GetMapping("/api/v1/discussions/{discussionId}/business-responses")
    @Operation(summary = "Listar respostas oficiais ativas do comentário raiz", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<PagedResponse<BusinessResponseResponse>> listDiscussionBusinessResponses(
            @PathVariable("discussionId") UUID discussionId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            Authentication authentication
    ) {
        UUID viewerUserId = extractAuthenticatedUserId(authentication);
        PageResult<BusinessResponseView> pageResult = listDiscussionBusinessResponsesUseCase.execute(
                viewerUserId,
                discussionId,
                page,
                size
        );
        List<BusinessResponseResponse> responses = pageResult.content().stream()
                .map(BusinessResponseResponse::fromView)
                .toList();
        return ResponseEntity.ok(PagedResponse.of(
                responses,
                pageResult.pageNumber(),
                pageResult.pageSize(),
                pageResult.totalElements()
        ));
    }

    /**
     * DELETE /api/v1/business-responses/{responseId}
     * Remove a própria resposta oficial pela empresa.
     */
    @DeleteMapping("/api/v1/business-responses/{responseId}")
    @Operation(summary = "Remover a própria resposta oficial pela empresa", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<Void> removeBusinessResponse(
            @PathVariable("responseId") UUID responseId,
            Authentication authentication
    ) {
        UUID actorUserId = extractAuthenticatedUserId(authentication);
        removeBusinessResponseUseCase.execute(actorUserId, responseId);
        return ResponseEntity.noContent().build();
    }

    private UUID extractAuthenticatedUserId(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            throw new BusinessException("Usuário não autenticado", HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        return UUID.fromString(authentication.getName());
    }
}
