package com.rewit.presentation.controller;

import com.rewit.application.dto.common.PageResult;
import com.rewit.application.dto.discussion.AdminDiscussionModerationDtos.AdminDiscussionReportView;
import com.rewit.application.dto.discussion.AdminDiscussionModerationDtos.ModerateDiscussionCommand;
import com.rewit.application.dto.discussion.AdminDiscussionModerationDtos.ModerateDiscussionResult;
import com.rewit.application.usecase.GetAdminDiscussionContextUseCase;
import com.rewit.application.usecase.ModerateDiscussionUseCase;
import com.rewit.application.usecase.QueryAdminDiscussionReportsUseCase;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.ReportReason;
import com.rewit.domain.enums.ReportStatus;
import com.rewit.presentation.dto.admin.AdminDiscussionModerationDtos.AdminDiscussionContextResponse;
import com.rewit.presentation.dto.admin.AdminDiscussionModerationDtos.AdminDiscussionReportResponse;
import com.rewit.presentation.dto.admin.AdminDiscussionModerationDtos.ModerateDiscussionRequest;
import com.rewit.presentation.dto.admin.AdminDiscussionModerationDtos.ModerateDiscussionResponse;
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
 * Moderação administrativa de discussões (C3), restrita a MODERATOR e ADMIN. Camada HTTP fina: as regras
 * ficam nos casos de uso.
 */
@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "Moderação Administrativa de Discussões", description = "Fila, contexto e moderação de comentários em análise")
public class AdminDiscussionModerationController {

    private final QueryAdminDiscussionReportsUseCase queryAdminDiscussionReportsUseCase;
    private final GetAdminDiscussionContextUseCase getAdminDiscussionContextUseCase;
    private final ModerateDiscussionUseCase moderateDiscussionUseCase;

    public AdminDiscussionModerationController(QueryAdminDiscussionReportsUseCase queryAdminDiscussionReportsUseCase,
                                               GetAdminDiscussionContextUseCase getAdminDiscussionContextUseCase,
                                               ModerateDiscussionUseCase moderateDiscussionUseCase) {
        this.queryAdminDiscussionReportsUseCase = Objects.requireNonNull(queryAdminDiscussionReportsUseCase,
                "QueryAdminDiscussionReportsUseCase must not be null");
        this.getAdminDiscussionContextUseCase = Objects.requireNonNull(getAdminDiscussionContextUseCase,
                "GetAdminDiscussionContextUseCase must not be null");
        this.moderateDiscussionUseCase = Objects.requireNonNull(moderateDiscussionUseCase,
                "ModerateDiscussionUseCase must not be null");
    }

    @GetMapping("/discussion-reports")
    @PreAuthorize("hasAnyRole('MODERATOR', 'ADMIN')")
    @Operation(summary = "Listar denúncias de comentários (paginado)", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<PagedResponse<AdminDiscussionReportResponse>> listDiscussionReports(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "status", required = false) ReportStatus status,
            @RequestParam(name = "reason", required = false) ReportReason reason,
            @RequestParam(name = "discussionId", required = false) UUID discussionId,
            @RequestParam(name = "sort", defaultValue = "asc") String sort,
            Authentication authentication
    ) {
        requireAuthenticatedUser(authentication);
        PageResult<AdminDiscussionReportView> result =
                queryAdminDiscussionReportsUseCase.execute(page, size, status, reason, discussionId, sort);
        return ResponseEntity.ok(PagedResponse.of(
                result.content().stream().map(AdminDiscussionReportResponse::fromView).toList(),
                result.pageNumber(),
                result.pageSize(),
                result.totalElements()));
    }

    @GetMapping("/discussions/{discussionId}")
    @PreAuthorize("hasAnyRole('MODERATOR', 'ADMIN')")
    @Operation(summary = "Obter contexto de moderação de um comentário", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<AdminDiscussionContextResponse> getDiscussionContext(
            @PathVariable("discussionId") UUID discussionId,
            Authentication authentication
    ) {
        requireAuthenticatedUser(authentication);
        return ResponseEntity.ok(AdminDiscussionContextResponse.fromView(getAdminDiscussionContextUseCase.execute(discussionId)));
    }

    @PostMapping("/discussions/{discussionId}/moderate")
    @PreAuthorize("hasAnyRole('MODERATOR', 'ADMIN')")
    @Operation(summary = "Remover ou restaurar um comentário em análise", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ModerateDiscussionResponse> moderateDiscussion(
            @PathVariable("discussionId") UUID discussionId,
            @Valid @RequestBody ModerateDiscussionRequest request,
            Authentication authentication
    ) {
        UUID moderatorUserId = requireAuthenticatedUser(authentication);
        ModerateDiscussionResult result = moderateDiscussionUseCase.execute(new ModerateDiscussionCommand(
                discussionId, moderatorUserId, request.action(), request.reasonCode(), request.justification(), Instant.now()));
        return ResponseEntity.ok(ModerateDiscussionResponse.fromAuditLog(result.auditLog()));
    }

    private static UUID requireAuthenticatedUser(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            throw new BusinessException("Usuário não autenticado", HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        return UUID.fromString(authentication.getName());
    }
}
