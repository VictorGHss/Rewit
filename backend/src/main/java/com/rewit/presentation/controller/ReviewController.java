package com.rewit.presentation.controller;

import com.rewit.application.dto.ReviewDto.CreateReviewCommand;
import com.rewit.application.dto.ReviewDto.CreateReviewTargetCommand;
import com.rewit.application.dto.ReviewDto.PublicAuthorView;
import com.rewit.application.dto.ReviewDto.ReviewPublicView;
import com.rewit.application.dto.ReviewDto.ReviewTargetView;
import com.rewit.application.service.ReviewService;
import com.rewit.common.exception.BusinessException;
import com.rewit.presentation.dto.review.ReviewPresentationDtos.CreateReviewRequest;
import com.rewit.presentation.dto.review.ReviewPresentationDtos.ReviewAuthorResponse;
import com.rewit.presentation.dto.review.ReviewPresentationDtos.ReviewResponse;
import com.rewit.presentation.dto.review.ReviewPresentationDtos.ReviewTargetResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Controller RESTful para criação e consulta de publicações de avaliação multi-alvo (Step 11.0).
 * A autoria é extraída exclusivamente a partir do contexto de autenticação JWT, prevenindo spoofing e IDOR.
 */
@RestController
@RequestMapping("/api/v1/reviews")
@Tag(name = "Reviews", description = "Operações com publicações de avaliação multi-alvo (Step 11.0)")
public class ReviewController {

    private final ReviewService reviewService;

    public ReviewController(ReviewService reviewService) {
        this.reviewService = Objects.requireNonNull(reviewService, "reviewService must not be null");
    }

    @PostMapping
    @Operation(summary = "Criar nova publicação de avaliação multi-alvo", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ReviewResponse> createReview(
            @Valid @RequestBody CreateReviewRequest request,
            Authentication authentication
    ) {
        if (authentication == null || authentication.getName() == null) {
            throw new BusinessException("Usuário não autenticado", HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        UUID authenticatedUserId = UUID.fromString(authentication.getName());

        List<CreateReviewTargetCommand> targetCommands = request.targets() != null
                ? request.targets().stream()
                .filter(Objects::nonNull)
                .map(t -> new CreateReviewTargetCommand(t.rateableTargetId(), t.rating(), t.specificComment()))
                .toList()
                : List.of();

        CreateReviewCommand cmd = new CreateReviewCommand(
                authenticatedUserId,
                request.contextPlaceId(),
                request.experienceText(),
                request.isAnonymous(),
                request.visibility(),
                request.userLatitude(),
                request.userLongitude(),
                request.locationAccuracyMeters(),
                targetCommands
        );

        ReviewPublicView created = reviewService.createReviewAndGetPublicView(cmd);
        URI location = URI.create("/api/v1/reviews/" + created.id());
        return ResponseEntity.created(location).body(toResponse(created));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Consultar publicação de avaliação por ID", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ReviewResponse> getReviewById(
            @PathVariable("id") UUID id,
            Authentication authentication
    ) {
        UUID requesterUserId = authentication != null && authentication.getName() != null
                ? UUID.fromString(authentication.getName())
                : null;

        ReviewPublicView reviewView = reviewService.getReviewPublicView(id, requesterUserId);
        return ResponseEntity.ok(toResponse(reviewView));
    }

    public static ReviewResponse toResponse(ReviewPublicView view) {
        if (view == null) {
            return null;
        }

        ReviewAuthorResponse authorResponse = toAuthorResponse(view.author());

        List<ReviewTargetResponse> targetResponses = view.targets() != null
                ? view.targets().stream()
                .map(ReviewController::toTargetResponse)
                .filter(Objects::nonNull)
                .toList()
                : List.of();

        return new ReviewResponse(
                view.id(),
                authorResponse,
                view.contextPlaceId(),
                view.experienceText(),
                view.isAnonymous(),
                view.isVerifiedOnSite(),
                view.visibility(),
                view.status(),
                view.createdAt(),
                view.updatedAt(),
                targetResponses
        );
    }

    private static ReviewAuthorResponse toAuthorResponse(PublicAuthorView authorView) {
        if (authorView == null || authorView.isAnonymous()) {
            return ReviewAuthorResponse.anonymous();
        }
        return ReviewAuthorResponse.of(
                authorView.id(),
                authorView.handle(),
                authorView.displayName(),
                authorView.avatarUrl()
        );
    }

    private static ReviewTargetResponse toTargetResponse(ReviewTargetView t) {
        if (t == null) {
            return null;
        }
        return new ReviewTargetResponse(
                t.id(),
                t.targetId(),
                t.rating(),
                t.specificComment(),
                t.createdAt()
        );
    }
}
