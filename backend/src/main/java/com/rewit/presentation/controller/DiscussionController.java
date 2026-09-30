package com.rewit.presentation.controller;

import com.rewit.application.dto.common.PageResult;
import com.rewit.application.dto.discussion.DiscussionDtos.CreateDiscussionCommand;
import com.rewit.application.dto.discussion.DiscussionDtos.DiscussionView;
import com.rewit.application.service.DiscussionService;
import com.rewit.common.exception.BusinessException;
import com.rewit.presentation.dto.common.PagedResponse;
import com.rewit.presentation.dto.discussion.CreateDiscussionRequest;
import com.rewit.presentation.dto.discussion.DiscussionResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Controller RESTful para discussões e comentários em avaliações (Step 20.0).
 * O autor é derivado estritamente do token JWT autenticado para prevenção total de IDOR e spoofing.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Discussões de Avaliações", description = "Endpoints para comentários e respostas em avaliações")
public class DiscussionController {

    private final DiscussionService discussionService;

    public DiscussionController(DiscussionService discussionService) {
        this.discussionService = Objects.requireNonNull(discussionService, "DiscussionService must not be null");
    }

    @PostMapping("/reviews/{reviewId}/discussions")
    @Operation(summary = "Adicionar comentário ou resposta a uma avaliação", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<DiscussionResponse> createDiscussion(
            @PathVariable("reviewId") UUID reviewId,
            @Valid @RequestBody CreateDiscussionRequest request,
            Authentication authentication
    ) {
        UUID authenticatedUserId = extractAuthenticatedUserId(authentication);

        CreateDiscussionCommand command = new CreateDiscussionCommand(
                reviewId,
                authenticatedUserId,
                request.parentId(),
                request.content()
        );

        DiscussionView view = discussionService.createDiscussion(command);
        return ResponseEntity.status(HttpStatus.CREATED).body(DiscussionResponse.fromView(view));
    }

    @GetMapping("/reviews/{reviewId}/discussions")
    @Operation(summary = "Listar comentários de uma avaliação de forma cronológica", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<PagedResponse<DiscussionResponse>> getDiscussions(
            @PathVariable("reviewId") UUID reviewId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication
    ) {
        UUID authenticatedUserId = extractAuthenticatedUserId(authentication);

        PageResult<DiscussionView> pageResult = discussionService.findDiscussionsByReviewId(reviewId, authenticatedUserId, page, size);

        List<DiscussionResponse> responseList = pageResult.content().stream()
                .map(DiscussionResponse::fromView)
                .toList();

        return ResponseEntity.ok(PagedResponse.of(
                responseList,
                pageResult.pageNumber(),
                pageResult.pageSize(),
                pageResult.totalElements()
        ));
    }

    @DeleteMapping("/discussions/{discussionId}")
    @Operation(summary = "Remover comentário de avaliação (soft delete)", security = @SecurityRequirement(name = "bearerAuth"))
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<Void> deleteDiscussion(
            @PathVariable("discussionId") UUID discussionId,
            Authentication authentication
    ) {
        UUID authenticatedUserId = extractAuthenticatedUserId(authentication);

        discussionService.deleteDiscussion(discussionId, authenticatedUserId);
        return ResponseEntity.noContent().build();
    }

    private UUID extractAuthenticatedUserId(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            throw new BusinessException("Usuário não autenticado", HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        return UUID.fromString(authentication.getName());
    }
}
