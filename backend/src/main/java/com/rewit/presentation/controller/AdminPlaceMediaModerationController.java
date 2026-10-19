package com.rewit.presentation.controller;

import com.rewit.application.dto.common.PageResult;
import com.rewit.application.service.PlaceMediaService;
import com.rewit.common.exception.BusinessException;
import com.rewit.presentation.dto.admin.AdminPlaceMediaModerationDtos.AdminPlaceMediaReportResponse;
import com.rewit.presentation.dto.admin.AdminPlaceMediaModerationDtos.DecidePlaceMediaReportRequest;
import com.rewit.presentation.dto.admin.AdminPlaceMediaModerationDtos.PlaceMediaModerationActionResponse;
import com.rewit.presentation.dto.admin.AdminPlaceMediaModerationDtos.RemovePlaceMediaRequest;
import com.rewit.presentation.dto.admin.AdminPlaceMediaModerationDtos.RestorePlaceMediaRequest;
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

import java.util.Objects;
import java.util.UUID;

/**
 * Moderação administrativa de fotografias de locais (PlaceMedia), restrita a MODERATOR e ADMIN.
 * Expõe a fila operacional de denúncias, decisões de deferimento/indeferimento e ações diretas de remoção/restauração.
 */
@RestController
@RequestMapping("/api/v1/admin/place-media")
@Tag(name = "Moderação Administrativa de Fotos de Locais", description = "Fila, decisão e controle do ciclo de vida das fotos")
public class AdminPlaceMediaModerationController {

    private final PlaceMediaService placeMediaService;

    public AdminPlaceMediaModerationController(PlaceMediaService placeMediaService) {
        this.placeMediaService = Objects.requireNonNull(placeMediaService, "PlaceMediaService must not be null");
    }

    @GetMapping("/reports")
    @PreAuthorize("hasAnyRole('MODERATOR', 'ADMIN')")
    @Operation(summary = "Listar denúncias de fotos de locais (paginado)", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<PagedResponse<AdminPlaceMediaReportResponse>> listReports(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size
    ) {
        PageResult<AdminPlaceMediaReportResponse> result = placeMediaService.listReports(status, page, size);
        return ResponseEntity.ok(PagedResponse.of(
                result.content(),
                result.pageNumber(),
                result.pageSize(),
                result.totalElements()
        ));
    }

    @GetMapping("/reports/{id}")
    @PreAuthorize("hasAnyRole('MODERATOR', 'ADMIN')")
    @Operation(summary = "Detalhe da denúncia e fotografia associada", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<AdminPlaceMediaReportResponse> getReport(
            @PathVariable("id") UUID id
    ) {
        return ResponseEntity.ok(placeMediaService.getReport(id));
    }

    @PostMapping("/reports/{id}/decision")
    @PreAuthorize("hasAnyRole('MODERATOR', 'ADMIN')")
    @Operation(summary = "Decidir denúncia de foto (deferir ou indeferir)", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<AdminPlaceMediaReportResponse> decideReport(
            @PathVariable("id") UUID id,
            @Valid @RequestBody DecidePlaceMediaReportRequest request,
            Authentication authentication
    ) {
        UUID moderatorUserId = requireAuthenticatedUser(authentication);
        AdminPlaceMediaReportResponse response = placeMediaService.decideReport(
                moderatorUserId,
                id,
                request.decision(),
                request.notes()
        );
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{placeId}/{mediaId}/remove")
    @PreAuthorize("hasAnyRole('MODERATOR', 'ADMIN')")
    @Operation(summary = "Remoção administrativa direta de foto inadequada", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<PlaceMediaModerationActionResponse> removeMedia(
            @PathVariable("placeId") UUID placeId,
            @PathVariable("mediaId") UUID mediaId,
            @Valid @RequestBody(required = false) RemovePlaceMediaRequest request,
            Authentication authentication
    ) {
        UUID moderatorUserId = requireAuthenticatedUser(authentication);
        String reason = request != null ? request.reason() : null;
        String notes = request != null ? request.notes() : null;
        PlaceMediaModerationActionResponse response = placeMediaService.removeMediaByModerator(
                moderatorUserId,
                placeId,
                mediaId,
                reason,
                notes
        );
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{placeId}/{mediaId}/restore")
    @PreAuthorize("hasAnyRole('MODERATOR', 'ADMIN')")
    @Operation(summary = "Restauração administrativa de foto removida", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<PlaceMediaModerationActionResponse> restoreMedia(
            @PathVariable("placeId") UUID placeId,
            @PathVariable("mediaId") UUID mediaId,
            @Valid @RequestBody(required = false) RestorePlaceMediaRequest request,
            Authentication authentication
    ) {
        UUID moderatorUserId = requireAuthenticatedUser(authentication);
        String notes = request != null ? request.notes() : null;
        PlaceMediaModerationActionResponse response = placeMediaService.restoreMediaByModerator(
                moderatorUserId,
                placeId,
                mediaId,
                notes
        );
        return ResponseEntity.ok(response);
    }

    private static UUID requireAuthenticatedUser(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            throw new BusinessException("Usuário não autenticado", HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        return UUID.fromString(authentication.getName());
    }
}

