package com.rewit.presentation.controller;

import com.rewit.application.dto.media.MediaDtos.MediaDownloadResult;
import com.rewit.application.dto.media.MediaDtos.ReviewMediaView;
import com.rewit.application.dto.media.MediaDtos.UploadMediaCommand;
import com.rewit.application.service.ReviewMediaService;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.model.ReviewMedia;
import com.rewit.presentation.dto.media.ReviewMediaResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Controlador REST para upload, recuperação, listagem e exclusão de mídias de avaliações (Step 21.0).
 */
@RestController
@RequestMapping("/api/v1/reviews/{reviewId}/media")
@Tag(name = "Review Media", description = "Endpoints para gerenciamento de imagens anexadas a avaliações")
public class ReviewMediaController {

    private final ReviewMediaService reviewMediaService;

    public ReviewMediaController(ReviewMediaService reviewMediaService) {
        this.reviewMediaService = Objects.requireNonNull(reviewMediaService, "ReviewMediaService must not be null");
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Realizar upload de anexo de imagem sanitizada para uma avaliação", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ReviewMediaResponse> uploadMedia(
            @PathVariable("reviewId") UUID reviewId,
            @RequestParam("file") MultipartFile file,
            Authentication authentication
    ) {
        UUID authenticatedUserId = extractAuthenticatedUserId(authentication);

        if (file == null || file.isEmpty()) {
            throw new BusinessException("O arquivo de mídia não pode ser vazio", HttpStatus.BAD_REQUEST, "EMPTY_MEDIA_FILE");
        }

        if (file.getSize() > ReviewMedia.MAX_FILE_SIZE_BYTES) {
            throw new BusinessException(
                    "O tamanho do arquivo excede o limite máximo permitido de 10 MB",
                    HttpStatus.CONTENT_TOO_LARGE,
                    "MEDIA_SIZE_EXCEEDED"
            );
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new BusinessException("Falha ao ler os bytes do arquivo enviado", HttpStatus.BAD_REQUEST, "FILE_READ_ERROR");
        }

        UploadMediaCommand command = new UploadMediaCommand(
                reviewId,
                authenticatedUserId,
                bytes,
                file.getOriginalFilename()
        );

        ReviewMediaView view = reviewMediaService.uploadMedia(command);
        return ResponseEntity.status(HttpStatus.CREATED).body(ReviewMediaResponse.fromView(view));
    }

    @GetMapping
    @Operation(summary = "Listar mídias ativas de uma avaliação", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<List<ReviewMediaResponse>> getMediaByReviewId(
            @PathVariable("reviewId") UUID reviewId,
            Authentication authentication
    ) {
        UUID authenticatedUserId = extractOptionalUserId(authentication);
        List<ReviewMediaView> views = reviewMediaService.getMediaByReviewId(reviewId, authenticatedUserId);

        List<ReviewMediaResponse> responses = views.stream()
                .map(entity -> ReviewMediaResponse.fromView(entity))
                .toList();

        return ResponseEntity.ok(responses);
    }

    @GetMapping("/{mediaId}")
    @Operation(summary = "Baixar ou visualizar imagem sanitizada da avaliação", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<byte[]> downloadMedia(
            @PathVariable("reviewId") UUID reviewId,
            @PathVariable("mediaId") UUID mediaId,
            Authentication authentication
    ) {
        UUID authenticatedUserId = extractOptionalUserId(authentication);
        MediaDownloadResult result = reviewMediaService.downloadMedia(reviewId, mediaId, authenticatedUserId);

        // Só mídia de avaliação PUBLIC pode ir para cache compartilhado; PRIVATE/FOLLOWERS dependem de quem lê,
        // e "public" anularia a proteção que o HTTP dá por padrão a respostas de requisições com Authorization
        String cacheControl = result.publiclyCacheable() ? "public, max-age=86400" : "private, no-store";

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(result.mimeType()))
                .contentLength(result.bytes().length)
                .header(HttpHeaders.CACHE_CONTROL, cacheControl)
                .body(result.bytes());
    }

    @DeleteMapping("/{mediaId}")
    @Operation(summary = "Excluir mídia de avaliação (autor da avaliação apenas)", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<Void> deleteMedia(
            @PathVariable("reviewId") UUID reviewId,
            @PathVariable("mediaId") UUID mediaId,
            Authentication authentication
    ) {
        UUID authenticatedUserId = extractAuthenticatedUserId(authentication);
        reviewMediaService.deleteMedia(reviewId, mediaId, authenticatedUserId);
        return ResponseEntity.noContent().build();
    }

    private UUID extractAuthenticatedUserId(Authentication authentication) {
        if (authentication == null || authentication.getName() == null || "anonymousUser".equalsIgnoreCase(authentication.getName())) {
            throw new BusinessException("Usuário não autenticado", HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException e) {
            throw new BusinessException("Identificador de usuário inválido", HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
    }

    private UUID extractOptionalUserId(Authentication authentication) {
        if (authentication == null || authentication.getName() == null || "anonymousUser".equalsIgnoreCase(authentication.getName())) {
            return null;
        }
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
