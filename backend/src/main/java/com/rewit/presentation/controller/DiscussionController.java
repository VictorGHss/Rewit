package com.rewit.presentation.controller;

import com.rewit.application.dto.common.PageResult;
import com.rewit.application.dto.discussion.DiscussionDtos.CreateDiscussionCommand;
import com.rewit.application.dto.discussion.DiscussionDtos.DiscussionView;
import com.rewit.application.dto.discussion.DiscussionReportDtos.ReportDiscussionCommand;
import com.rewit.application.dto.discussion.DiscussionThreadDtos.DiscussionItemView;
import com.rewit.application.dto.discussion.DiscussionThreadDtos.DiscussionThreadView;
import com.rewit.application.service.DiscussionService;
import com.rewit.application.service.DiscussionThreadQueryService;
import com.rewit.application.usecase.ReportDiscussionUseCase;
import com.rewit.common.exception.BusinessException;
import com.rewit.presentation.dto.common.PagedResponse;
import com.rewit.presentation.dto.discussion.CreateDiscussionRequest;
import com.rewit.presentation.dto.discussion.DiscussionReportReceiptResponse;
import com.rewit.presentation.dto.discussion.DiscussionItemResponse;
import com.rewit.presentation.dto.discussion.DiscussionResponse;
import com.rewit.presentation.dto.discussion.DiscussionThreadResponse;
import com.rewit.presentation.dto.discussion.ReportDiscussionRequest;
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
    private final ReportDiscussionUseCase reportDiscussionUseCase;
    private final DiscussionThreadQueryService discussionThreadQueryService;

    public DiscussionController(DiscussionService discussionService,
                                ReportDiscussionUseCase reportDiscussionUseCase,
                                DiscussionThreadQueryService discussionThreadQueryService) {
        this.discussionService = Objects.requireNonNull(discussionService, "DiscussionService must not be null");
        this.reportDiscussionUseCase = Objects.requireNonNull(reportDiscussionUseCase, "ReportDiscussionUseCase must not be null");
        this.discussionThreadQueryService = Objects.requireNonNull(discussionThreadQueryService, "DiscussionThreadQueryService must not be null");
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
    @Operation(summary = "Listar a thread de comentários de uma avaliação (raízes paginadas com respostas)",
            security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<PagedResponse<DiscussionThreadResponse>> getDiscussions(
            @PathVariable("reviewId") UUID reviewId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication
    ) {
        UUID authenticatedUserId = extractAuthenticatedUserId(authentication);
        PageResult<DiscussionThreadView> pageResult =
                discussionThreadQueryService.getThread(reviewId, authenticatedUserId, page, size);
        List<DiscussionThreadResponse> responseList = pageResult.content().stream()
                .map(DiscussionThreadResponse::fromView)
                .toList();
        return ResponseEntity.ok(PagedResponse.of(
                responseList,
                pageResult.pageNumber(),
                pageResult.pageSize(),
                pageResult.totalElements()
        ));
    }

    @GetMapping("/discussions/{discussionId}/replies")
    @Operation(summary = "Listar respostas de um comentário raiz (paginado)", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<PagedResponse<DiscussionItemResponse>> getReplies(
            @PathVariable("discussionId") UUID discussionId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication
    ) {
        UUID authenticatedUserId = extractAuthenticatedUserId(authentication);
        PageResult<DiscussionItemView> pageResult =
                discussionThreadQueryService.getReplies(discussionId, authenticatedUserId, page, size);
        return ResponseEntity.ok(PagedResponse.of(
                pageResult.content().stream().map(DiscussionItemResponse::fromView).toList(),
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

    @PostMapping("/discussions/{discussionId}/reports")
    @Operation(summary = "Denunciar comentário ou resposta", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<DiscussionReportReceiptResponse> reportDiscussion(
            @PathVariable("discussionId") UUID discussionId,
            @Valid @RequestBody ReportDiscussionRequest request,
            Authentication authentication
    ) {
        UUID authenticatedUserId = extractAuthenticatedUserId(authentication);
        reportDiscussionUseCase.execute(new ReportDiscussionCommand(
                authenticatedUserId, discussionId, request.reason(), request.detail()));
        // Mesma resposta para denúncia nova, repetida ou que coloca o comentário em análise
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(DiscussionReportReceiptResponse.received());
    }

    private UUID extractAuthenticatedUserId(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            throw new BusinessException("Usuário não autenticado", HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        return UUID.fromString(authentication.getName());
    }
}
