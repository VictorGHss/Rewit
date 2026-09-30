package com.rewit.presentation.controller;

import com.rewit.application.dto.ReviewDto.TargetStatsView;
import com.rewit.application.service.ReviewService;
import com.rewit.presentation.dto.target.TargetStatsResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.UUID;

import com.rewit.application.dto.ReviewDto.ReviewPublicView;
import com.rewit.application.dto.common.PageResult;
import com.rewit.presentation.dto.common.PagedResponse;
import com.rewit.presentation.dto.review.ReviewPresentationDtos.ReviewResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * Controlador REST para consulta de estatísticas agregadas e avaliações de alvos avaliáveis (Steps 13.0 e 14.0).
 */
@RestController
@RequestMapping("/api/v1/targets/{id}")
public class TargetStatsController {

    private final ReviewService reviewService;

    public TargetStatsController(ReviewService reviewService) {
        this.reviewService = Objects.requireNonNull(reviewService, "reviewService must not be null");
    }

    @GetMapping("/stats")
    public ResponseEntity<TargetStatsResponse> getTargetStats(@PathVariable UUID id) {
        TargetStatsView view = reviewService.getTargetStats(id);
        return ResponseEntity.ok(new TargetStatsResponse(
                view.targetId(),
                view.averageRating(),
                view.reviewsCount(),
                view.lastCalculatedAt()
        ));
    }

    @GetMapping("/reviews")
    @Operation(summary = "Listar publicações de avaliação paginadas de um RateableTarget", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<PagedResponse<ReviewResponse>> getTargetReviews(
            @PathVariable("id") UUID id,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "10") int size,
            @RequestParam(name = "sort", defaultValue = "newest") String sort,
            @RequestParam(name = "verifiedOnly", defaultValue = "false") boolean verifiedOnly,
            Authentication authentication
    ) {
        UUID requesterUserId = authentication != null && authentication.getName() != null
                ? UUID.fromString(authentication.getName())
                : null;

        PageResult<ReviewPublicView> pageResult = reviewService.findReviewsByTarget(
                id, page, size, sort, verifiedOnly, requesterUserId
        );

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
}
