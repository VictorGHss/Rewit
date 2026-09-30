package com.rewit.presentation.controller;

import com.rewit.application.dto.report.ReportDtos.CreateReportCommand;
import com.rewit.application.service.ReportService;
import com.rewit.application.service.ReportService.CreateReportResult;
import com.rewit.common.exception.BusinessException;
import com.rewit.presentation.dto.report.CreateReportRequest;
import com.rewit.presentation.dto.report.ReportResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.UUID;

/**
 * Controller RESTful para submissão de denúncias comunitárias de avaliações (Step 19.0).
 * O denunciante é identificado exclusivamente através do token JWT no contexto de segurança.
 */
@RestController
@RequestMapping("/api/v1/reports")
@Tag(name = "Denúncias e Moderação", description = "Endpoints para denúncia e moderação comunitária preventiva")
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = Objects.requireNonNull(reportService, "ReportService must not be null");
    }

    @PostMapping
    @Operation(summary = "Criar denúncia de avaliação", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ReportResponse> createReport(
            @Valid @RequestBody CreateReportRequest request,
            Authentication authentication
    ) {
        UUID authenticatedUserId = extractAuthenticatedUserId(authentication);

        CreateReportCommand command = new CreateReportCommand(
                authenticatedUserId,
                request.reviewId(),
                request.reason(),
                request.detail()
        );

        CreateReportResult result = reportService.createReport(command);
        ReportResponse response = ReportResponse.fromDomain(result.report());

        if (result.newlyCreated()) {
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } else {
            return ResponseEntity.ok(response);
        }
    }

    private UUID extractAuthenticatedUserId(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            throw new BusinessException("Usuário não autenticado", HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        return UUID.fromString(authentication.getName());
    }
}
