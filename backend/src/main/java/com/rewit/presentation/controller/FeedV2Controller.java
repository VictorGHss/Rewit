package com.rewit.presentation.controller;

import com.rewit.application.dto.feed.FeedV2PageProjection;
import com.rewit.application.service.FeedV2QueryService;
import com.rewit.common.exception.BusinessException;
import com.rewit.presentation.dto.feed.FeedV2PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Controller RESTful para o Feed V2 ranqueado e diversificado (/api/v2/feed) - Step 24.4.3.
 *
 * <p>Controller fino: recebe parâmetros da requisição, extrai a identidade autenticada
 * e delega para a camada de aplicação sem conter regras de negócio, ranking ou persistência.</p>
 */
@RestController
@RequestMapping("/api/v2/feed")
@Tag(name = "Feed V2", description = "Endpoints de feed ranqueado e diversificado")
public class FeedV2Controller {

    private final FeedV2QueryService feedV2QueryService;

    public FeedV2Controller(FeedV2QueryService feedV2QueryService) {
        this.feedV2QueryService = Objects.requireNonNull(feedV2QueryService, "feedV2QueryService must not be null");
    }

    @GetMapping
    @Operation(summary = "Consultar feed V2 com ranking determinístico e diversidade", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<FeedV2PageResponse> getFeed(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "10") int size,
            Authentication authentication
    ) {
        UUID requesterUserId = extractAuthenticatedUserId(authentication);
        Instant referenceTime = Instant.now();

        FeedV2PageProjection pageProjection = feedV2QueryService.getFeed(requesterUserId, page, size, referenceTime);

        return ResponseEntity.ok(FeedV2PageResponse.from(pageProjection));
    }

    private UUID extractAuthenticatedUserId(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            throw new BusinessException("Usuário não autenticado", HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException e) {
            throw new BusinessException("Usuário não autenticado", HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
    }
}
