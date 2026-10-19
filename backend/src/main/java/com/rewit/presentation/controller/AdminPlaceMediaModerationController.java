package com.rewit.presentation.controller;

import com.rewit.application.dto.common.PageResult;
import com.rewit.application.dto.media.MediaDtos.MediaDownloadResult;
import com.rewit.application.dto.media.PlaceMediaModerationDtos.PlaceMediaModerationResult;
import com.rewit.application.dto.media.PlaceMediaModerationDtos.PlaceMediaReportView;
import com.rewit.application.service.ModeratorRolePolicy;
import com.rewit.application.service.PlaceMediaService;
import com.rewit.common.exception.BusinessException;
import com.rewit.presentation.dto.admin.AdminPlaceMediaModerationDtos.AdminPlaceMediaReportResponse;
import com.rewit.presentation.dto.admin.AdminPlaceMediaModerationDtos.AdminPlaceMediaUnderReviewResponse;
import com.rewit.presentation.dto.admin.AdminPlaceMediaModerationDtos.DecidePlaceMediaReportRequest;
import com.rewit.presentation.dto.admin.AdminPlaceMediaModerationDtos.PlaceMediaModerationActionResponse;
import com.rewit.presentation.dto.admin.AdminPlaceMediaModerationDtos.RemovePlaceMediaRequest;
import com.rewit.presentation.dto.admin.AdminPlaceMediaModerationDtos.RestorePlaceMediaRequest;
import com.rewit.presentation.dto.common.PagedResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * Moderação administrativa de fotografias de locais (PlaceMedia), restrita a MODERATOR e ADMIN.
 * Expõe a fila operacional de denúncias, decisões de deferimento/indeferimento e ações diretas de remoção/restauração.
 * Revalida dinamicamente a autoridade e status operacional do moderador via ModeratorRolePolicy contra stale JWTs.
 */
@RestController
@RequestMapping("/api/v1/admin/place-media")
@Tag(name = "Moderação Administrativa de Fotos de Locais", description = "Fila, decisão e controle do ciclo de vida das fotos")
public class AdminPlaceMediaModerationController {

    private final PlaceMediaService placeMediaService;
    private final ModeratorRolePolicy moderatorRolePolicy;

    @Autowired
    public AdminPlaceMediaModerationController(PlaceMediaService placeMediaService,
                                              @Autowired(required = false) ModeratorRolePolicy moderatorRolePolicy) {
        this.placeMediaService = Objects.requireNonNull(placeMediaService, "PlaceMediaService must not be null");
        this.moderatorRolePolicy = moderatorRolePolicy;
    }

    public AdminPlaceMediaModerationController(PlaceMediaService placeMediaService) {
        this(placeMediaService, null);
    }

    @GetMapping("/reports")
    @PreAuthorize("hasAnyRole('MODERATOR', 'ADMIN')")
    @Operation(summary = "Listar denúncias de fotos de locais (paginado)", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<PagedResponse<AdminPlaceMediaReportResponse>> listReports(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            Authentication authentication
    ) {
        UUID moderatorUserId = requireAuthenticatedUser(authentication);
        if (moderatorRolePolicy != null) {
            moderatorRolePolicy.requireCurrentModerator(moderatorUserId);
        }

        PageResult<PlaceMediaReportView> result = placeMediaService.listReports(moderatorUserId, status, page, size);
        List<AdminPlaceMediaReportResponse> content = result.content().stream()
                .map(AdminPlaceMediaReportResponse::fromView)
                .toList();

        return ResponseEntity.ok(PagedResponse.of(
                content,
                result.pageNumber(),
                result.pageSize(),
                result.totalElements()
        ));
    }

    @GetMapping(value = {"/under-review", "/queue"})
    @PreAuthorize("hasAnyRole('MODERATOR', 'ADMIN')")
    @Operation(summary = "Fila operacional de fotografias sob revisão (UNDER_REVIEW)", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<PagedResponse<AdminPlaceMediaUnderReviewResponse>> listUnderReview(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            Authentication authentication
    ) {
        UUID moderatorUserId = requireAuthenticatedUser(authentication);
        if (moderatorRolePolicy != null) {
            moderatorRolePolicy.requireCurrentModerator(moderatorUserId);
        }

        var result = placeMediaService.listUnderReviewQueue(moderatorUserId, page, size);
        List<AdminPlaceMediaUnderReviewResponse> content = result.content().stream()
                .map(AdminPlaceMediaUnderReviewResponse::fromView)
                .toList();

        return ResponseEntity.ok(PagedResponse.of(
                content,
                result.pageNumber(),
                result.pageSize(),
                result.totalElements()
        ));
    }

    @GetMapping("/reports/{id}")
    @PreAuthorize("hasAnyRole('MODERATOR', 'ADMIN')")
    @Operation(summary = "Detalhe da denúncia e fotografia associada", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<AdminPlaceMediaReportResponse> getReport(
            @PathVariable("id") UUID id,
            Authentication authentication
    ) {
        UUID moderatorUserId = requireAuthenticatedUser(authentication);
        if (moderatorRolePolicy != null) {
            moderatorRolePolicy.requireCurrentModerator(moderatorUserId);
        }

        PlaceMediaReportView view = placeMediaService.getReport(moderatorUserId, id);
        return ResponseEntity.ok(AdminPlaceMediaReportResponse.fromView(view));
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
        if (moderatorRolePolicy != null) {
            moderatorRolePolicy.requireCurrentModerator(moderatorUserId);
        }

        PlaceMediaReportView view = placeMediaService.decideReport(
                moderatorUserId,
                id,
                request.decision(),
                request.notes()
        );
        return ResponseEntity.ok(AdminPlaceMediaReportResponse.fromView(view));
    }

    @PostMapping("/{placeId}/{mediaId}/remove")
    @PreAuthorize("hasAnyRole('MODERATOR', 'ADMIN')")
    @Operation(summary = "Remoção administrativa direta de foto inadequada (POST)", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<PlaceMediaModerationActionResponse> removeMedia(
            @PathVariable("placeId") UUID placeId,
            @PathVariable("mediaId") UUID mediaId,
            @Valid @RequestBody(required = false) RemovePlaceMediaRequest request,
            Authentication authentication
    ) {
        return executeRemoveMedia(placeId, mediaId, request, authentication);
    }

    @DeleteMapping("/{placeId}/{mediaId}")
    @PreAuthorize("hasAnyRole('MODERATOR', 'ADMIN')")
    @Operation(summary = "Remoção administrativa direta de foto inadequada (DELETE)", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<PlaceMediaModerationActionResponse> deleteMedia(
            @PathVariable("placeId") UUID placeId,
            @PathVariable("mediaId") UUID mediaId,
            @Valid @RequestBody(required = false) RemovePlaceMediaRequest request,
            Authentication authentication
    ) {
        return executeRemoveMedia(placeId, mediaId, request, authentication);
    }

    private ResponseEntity<PlaceMediaModerationActionResponse> executeRemoveMedia(
            UUID placeId,
            UUID mediaId,
            RemovePlaceMediaRequest request,
            Authentication authentication
    ) {
        UUID moderatorUserId = requireAuthenticatedUser(authentication);
        if (moderatorRolePolicy != null) {
            moderatorRolePolicy.requireCurrentModerator(moderatorUserId);
        }

        String reason = request != null ? request.reason() : null;
        String notes = request != null ? request.notes() : null;
        PlaceMediaModerationResult result = placeMediaService.removeMediaByModerator(
                moderatorUserId,
                placeId,
                mediaId,
                reason,
                notes
        );
        return ResponseEntity.ok(PlaceMediaModerationActionResponse.fromResult(result));
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
        if (moderatorRolePolicy != null) {
            moderatorRolePolicy.requireCurrentModerator(moderatorUserId);
        }

        String notes = request != null ? request.notes() : null;
        PlaceMediaModerationResult result = placeMediaService.restoreMediaByModerator(
                moderatorUserId,
                placeId,
                mediaId,
                notes
        );
        return ResponseEntity.ok(PlaceMediaModerationActionResponse.fromResult(result));
    }

    @GetMapping(value = {"/{placeId}/{mediaId}/content", "/{placeId}/{mediaId}/download"})
    @PreAuthorize("hasAnyRole('MODERATOR', 'ADMIN')")
    @Operation(summary = "Inspeção visual administrativa de foto em análise ou removida", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<byte[]> getMediaContentForModerator(
            @PathVariable("placeId") UUID placeId,
            @PathVariable("mediaId") UUID mediaId,
            Authentication authentication
    ) {
        UUID moderatorUserId = requireAuthenticatedUser(authentication);
        if (moderatorRolePolicy != null) {
            moderatorRolePolicy.requireCurrentModerator(moderatorUserId);
        }

        MediaDownloadResult download = placeMediaService.downloadMediaForModerator(moderatorUserId, placeId, mediaId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, no-cache, no-store, must-revalidate")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header(HttpHeaders.EXPIRES, "0")
                .contentType(MediaType.parseMediaType(download.mimeType()))
                .body(download.bytes());
    }

    private static UUID requireAuthenticatedUser(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            throw new BusinessException("Usuário não autenticado", HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        return UUID.fromString(authentication.getName());
    }
}
