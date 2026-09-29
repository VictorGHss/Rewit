package com.rewit.presentation.controller;

import com.rewit.application.service.ReviewHelpfulService;
import com.rewit.common.exception.BusinessException;
import com.rewit.presentation.dto.review.HelpfulResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Objects;
import java.util.UUID;

/**
 * Controlador REST para validação de utilidade (Helpful) em publicações de avaliação (Step 16.0).
 */
@RestController
@RequestMapping("/api/v1/reviews/{id}/helpful")
@Tag(name = "Reviews", description = "Endpoints de avaliações e interações sociais")
public class ReviewHelpfulController {

    private final ReviewHelpfulService reviewHelpfulService;

    public ReviewHelpfulController(ReviewHelpfulService reviewHelpfulService) {
        this.reviewHelpfulService = Objects.requireNonNull(reviewHelpfulService, "reviewHelpfulService must not be null");
    }

    @PostMapping
    @Operation(summary = "Marcar avaliação como útil (Helpful)", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<HelpfulResponse> addHelpful(
            @PathVariable("id") UUID id,
            Authentication authentication
    ) {
        UUID requesterUserId = extractAuthenticatedUserId(authentication);
        ReviewHelpfulService.HelpfulResult result = reviewHelpfulService.addHelpful(id, requesterUserId);
        return ResponseEntity.ok(new HelpfulResponse(result.helpful(), result.helpfulCount()));
    }

    @DeleteMapping
    @Operation(summary = "Remover marcação de útil da avaliação", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<HelpfulResponse> removeHelpful(
            @PathVariable("id") UUID id,
            Authentication authentication
    ) {
        UUID requesterUserId = extractAuthenticatedUserId(authentication);
        ReviewHelpfulService.HelpfulResult result = reviewHelpfulService.removeHelpful(id, requesterUserId);
        return ResponseEntity.ok(new HelpfulResponse(result.helpful(), result.helpfulCount()));
    }

    private UUID extractAuthenticatedUserId(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            throw new BusinessException("Usuário não autenticado", HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        return UUID.fromString(authentication.getName());
    }
}
