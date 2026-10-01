package com.rewit.presentation.controller;

import com.rewit.application.dto.common.PageResult;
import com.rewit.application.dto.report.ReportDtos.AdminReportView;
import com.rewit.application.dto.report.ReportDtos.ModerateReviewCommand;
import com.rewit.application.dto.report.ReportDtos.ModerateReviewResult;
import com.rewit.application.usecase.ModerateReviewUseCase;
import com.rewit.application.usecase.QueryAdminReportsUseCase;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.ReportReason;
import com.rewit.domain.enums.ReportStatus;
import com.rewit.presentation.dto.admin.AdminModerationDtos.AdminReportResponse;
import com.rewit.presentation.dto.admin.AdminModerationDtos.ModerateReviewRequest;
import com.rewit.presentation.dto.admin.AdminModerationDtos.ModerateReviewResponse;
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
 * Controller RESTful para moderação administrativa de avaliações e triagem de denúncias (Step 26.3).
 *
 * <p>Acesso restrito a usuários com role MODERATOR ou ADMIN, validado via @PreAuthorize.
 * A identidade do moderador é extraída exclusivamente do token JWT autenticado (sub claim).
 * A camada HTTP é mantida fina: nenhuma regra de negócio reside aqui.
 */
@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "Moderação Administrativa", description = "Endpoints de moderação e triagem de denúncias para MODERATOR/ADMIN (Step 26.3)")
public class AdminModerationController {

    private final ModerateReviewUseCase moderateReviewUseCase;
    private final QueryAdminReportsUseCase queryAdminReportsUseCase;

    public AdminModerationController(
            ModerateReviewUseCase moderateReviewUseCase,
            QueryAdminReportsUseCase queryAdminReportsUseCase
    ) {
        this.moderateReviewUseCase = Objects.requireNonNull(moderateReviewUseCase, "moderateReviewUseCase must not be null");
        this.queryAdminReportsUseCase = Objects.requireNonNull(queryAdminReportsUseCase, "queryAdminReportsUseCase must not be null");
    }

    /**
     * GET /api/v1/admin/reports
     * Consulta paginada da fila de denúncias para triagem administrativa.
     * Suporta filtros opcionais por status, reason, reviewId e reporterUserId.
     */
    @GetMapping("/reports")
    @PreAuthorize("hasAnyRole('MODERATOR', 'ADMIN')")
    @Operation(
            summary = "Listar denúncias para triagem administrativa (paginado)",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    public ResponseEntity<PagedResponse<AdminReportResponse>> listReports(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "status", required = false) ReportStatus status,
            @RequestParam(name = "reason", required = false) ReportReason reason,
            @RequestParam(name = "reviewId", required = false) UUID reviewId,
            @RequestParam(name = "reporterUserId", required = false) UUID reporterUserId,
            @RequestParam(name = "sort", defaultValue = "asc") String sort,
            Authentication authentication
    ) {
        extractAuthenticatedUserId(authentication);

        PageResult<AdminReportView> result = queryAdminReportsUseCase.execute(
                page, size, status, reason, reviewId, reporterUserId, sort
        );

        List<AdminReportResponse> responses = result.content().stream()
                .map(AdminModerationController::toAdminReportResponse)
                .toList();

        PagedResponse<AdminReportResponse> pagedResponse = PagedResponse.of(
                responses,
                result.pageNumber(),
                result.pageSize(),
                result.totalElements()
        );

        return ResponseEntity.ok(pagedResponse);
    }

    /**
     * POST /api/v1/admin/reviews/{reviewId}/moderate
     * Executa uma ação administrativa de moderação sobre uma avaliação específica.
     * Ações suportadas: REMOVE_REVIEW, RESTORE_REVIEW.
     */
    @PostMapping("/reviews/{reviewId}/moderate")
    @PreAuthorize("hasAnyRole('MODERATOR', 'ADMIN')")
    @Operation(
            summary = "Executar ação administrativa de moderação sobre uma avaliação",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    public ResponseEntity<ModerateReviewResponse> moderateReview(
            @PathVariable("reviewId") UUID reviewId,
            @Valid @RequestBody ModerateReviewRequest request,
            Authentication authentication
    ) {
        UUID moderatorUserId = extractAuthenticatedUserId(authentication);

        ModerateReviewCommand command = new ModerateReviewCommand(
                reviewId,
                moderatorUserId,
                request.action(),
                request.reasonCode(),
                request.justification(),
                Instant.now()
        );

        ModerateReviewResult result = moderateReviewUseCase.execute(command);

        ModerateReviewResponse response = new ModerateReviewResponse(
                result.auditLog().getId(),
                result.review().getId(),
                result.auditLog().getAction(),
                result.auditLog().getReasonCode(),
                result.auditLog().getJustification(),
                result.auditLog().getPreviousReviewStatus(),
                result.auditLog().getNewReviewStatus(),
                result.auditLog().getReportsAffectedCount(),
                result.auditLog().getCreatedAt()
        );

        return ResponseEntity.ok(response);
    }

    private UUID extractAuthenticatedUserId(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            throw new BusinessException("Usuário não autenticado", HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        return UUID.fromString(authentication.getName());
    }

    private static AdminReportResponse toAdminReportResponse(AdminReportView view) {
        return new AdminReportResponse(
                view.id(),
                view.reviewId(),
                view.reviewAuthorUserId(),
                view.reviewStatus(),
                view.reporterUserId(),
                view.reason(),
                view.detail(),
                view.status(),
                view.createdAt(),
                view.updatedAt()
        );
    }
}
