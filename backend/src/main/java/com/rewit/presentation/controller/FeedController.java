package com.rewit.presentation.controller;

import com.rewit.application.dto.ReviewDto.ReviewPublicView;
import com.rewit.application.dto.common.PageResult;
import com.rewit.application.service.ReviewService;
import com.rewit.common.exception.BusinessException;
import com.rewit.presentation.dto.common.PagedResponse;
import com.rewit.presentation.dto.review.ReviewPresentationDtos.ReviewResponse;
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

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Controller RESTful para a timeline social dos usuários seguidos (/api/v1/feed) - Step 17.0.
 * A identidade é inferida exclusivamente a partir do token JWT do contexto de segurança.
 */
@RestController
@RequestMapping("/api/v1/feed")
@Tag(name = "Feed", description = "Endpoints de timeline social e descobertas")
public class FeedController {

    private final ReviewService reviewService;

    public FeedController(ReviewService reviewService) {
        this.reviewService = Objects.requireNonNull(reviewService, "reviewService must not be null");
    }

    @GetMapping
    @Operation(summary = "Consultar timeline social dos usuários seguidos", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<PagedResponse<ReviewResponse>> getFeed(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "10") int size,
            @RequestParam(name = "sort", required = false) String sort,
            Authentication authentication
    ) {
        UUID requesterUserId = extractAuthenticatedUserId(authentication);
        PageResult<ReviewPublicView> pageResult = reviewService.findFeed(requesterUserId, page, size, sort);

        List<ReviewResponse> responseContent = pageResult.content().stream()
                .map(ReviewController::toResponse)
                .toList();

        return ResponseEntity.ok(new PagedResponse<>(
                responseContent,
                pageResult.pageNumber(),
                pageResult.pageSize(),
                pageResult.totalElements(),
                pageResult.totalPages(),
                pageResult.isLast()
        ));
    }

    private UUID extractAuthenticatedUserId(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            throw new BusinessException("Usuário não autenticado", HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        return UUID.fromString(authentication.getName());
    }
}
